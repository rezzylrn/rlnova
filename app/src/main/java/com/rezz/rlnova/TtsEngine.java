package com.rezz.rlnova;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * v1.1.8: TTS on-device via sherpa-onnx (Piper VITS id_ID news_tts int8).
 * TIDAK memakai API sistem android.speech.tts sama sekali — sintesis jalan
 * di ONNX Runtime on-device (libsherpa-onnx-jni.so + libonnxruntime.so).
 *
 * Threading (sama polanya dengan WhisperEngine):
 *  - loadLibrary(): sekali, background thread.
 *  - init(): background thread (blocking; model 18 MB termuat ke RAM).
 *  - generate(): blocking — WAJIB dari worker thread; hasil float[] PCM.
 *  - free(): background thread.
 *
 * Audio diputar via AudioTrack (ENCODED_STREAM tidak perlu — PCM 22050 mono
 * 16-bit, format yang dihasilkan model Piper).
 */
public final class TtsEngine {

    private static final String TAG = "TtsEngine";
    private static volatile boolean loaded = false;

    /** Load libsherpa-onnx-jni.so (+ libonnxruntime.so via NEEDED). Background thread. */
    public static synchronized void loadLibrary() throws UnsatisfiedLinkError {
        if (loaded) return;
        System.loadLibrary("sherpa-onnx-jni");
        loaded = true;
    }

    public static boolean libraryLoaded() { return loaded; }

    private OfflineTts tts; // nullable — null berarti belum dimuat / sudah di-free

    /** True bila engine native termuat dan siap sintesis. */
    public synchronized boolean isReady() { return tts != null; }

    /**
     * Muat model Piper VITS dari file .onnx + folder espeak-ng-data.
     * Blocking — WAJIB dari background thread.
     * @return true bila engine siap dipakai.
     */
    public boolean init(Context ctx) {
        if (tts != null) return true;
        File onnx = VoiceCatalog.piperOnnxFile(ctx);
        File espeak = VoiceCatalog.espeakDataDir(ctx);
        if (!onnx.exists() || !new File(espeak, "lang").exists()) return false;

        com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig vits =
                new com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
                        /* model   = */ onnx.getAbsolutePath(),
                        /* lexicon = */ "",
                        /* tokens  = */ tokensPath(ctx),
                        /* dataDir = */ espeak.getAbsolutePath(),
                        /* dictDir = */ "",
                        /* noiseScale  = */ 0.667f,
                        /* noiseScaleW = */ 0.8f,
                        /* lengthScale = */ 1.0f);
        OfflineTtsModelConfig mc = new OfflineTtsModelConfig();
        mc.setVits(vits);
        mc.setNumThreads(2);
        mc.setDebug(false);
        mc.setProvider("cpu");
        OfflineTtsConfig config = new OfflineTtsConfig(
                mc, /* ruleFsts */ "", /* ruleFars */ "", /* maxNumSentences */ 1,
                /* silenceScale */ 0.2f);
        try {
            tts = new OfflineTts(null, config);
            return tts.sampleRate() > 0;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "init TTS gagal", t);
            tts = null;
            return false;
        }
    }

    /**
     * Sintesis teks -> PCM float (22050 Hz mono). Blocking — WAJIB dari
     * background thread. Jangan panggil dari UI thread.
     */
    public GeneratedAudio generate(String text) {
        OfflineTts t = tts;
        if (t == null) return null;
        return t.generate(text, 0, 1.0f);
    }

    /**
     * tokens.txt Piper (161 simbol) di-bundle di APK assets — 1 KB, bukan
     * bagian model yang di-download. Diekstrak ke filesDir agar sherpa-onnx
     * (yang hanya menerima path file) bisa membacanya.
     */
    private static String tokensPath(Context ctx) {
        File out = new File(ctx.getFilesDir(), "tokens-piper-id.txt");
        if (!out.exists() || out.length() == 0) {
            try (InputStream in = ctx.getAssets().open("tokens-piper-id.txt");
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] b = new byte[8192];
                int r;
                while ((r = in.read(b)) > 0) fos.write(b, 0, r);
                fos.flush();
            } catch (Exception ignored) { }
        }
        return out.getAbsolutePath();
    }

    /** Bebaskan engine native. Blocking — panggil dari background thread. */
    public synchronized void free() {
        OfflineTts t = tts;
        tts = null;
        if (t != null) {
            try { t.release(); } catch (Throwable ignored) { }
        }
    }

    // ====================================================== espeak ekstraksi

    /**
     * Setelah espeak-ng-data.zip selesai di-download (folder "models/
     * voice-piper-id-espeak.zip"), ekstrak isinya ke folder
     * filesDir/models/voice-piper-id-espeak/. Idempotent — lewati bila
     * folder sudah berisi data (subfolder "lang" ada).
     * @return true bila folder siap dipakai.
     */
    public static boolean maybeExtractEspeak(Context ctx) {
        File dst = VoiceCatalog.espeakDataDir(ctx);
        if (new File(dst, "lang").exists()) return true;
        File models = new File(ctx.getFilesDir(), "models");
        // v1.1.11 migrasi satu kali: user versi lama bisa punya ".part" espeak
        // yang sudah selesai (DONE tapi tidak pernah di-rename oleh bug lama).
        // Kalau ".zip" belum ada tapi ".part" ada dan ukurannya cocok katalog,
        // rename dulu supaya alur ekstraksi normal jalan tanpa hapus manual.
        File zip = new File(models, VoiceCatalog.PIPER_ESPEAK_ID + ".zip");
        if (!zip.exists()) {
            File part = new File(models, VoiceCatalog.PIPER_ESPEAK_ID + ".part");
            if (part.exists() && part.length() == VoiceCatalog.PIPER_ESPEAK_SIZE_BYTES) {
                File z = new File(models, VoiceCatalog.PIPER_ESPEAK_ID + ".zip");
                if (z.exists()) z.delete();
                if (!part.renameTo(z)) return false;
            }
        }
        if (!zip.exists()) return false;
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        if (tmp.exists()) deleteRecursive(tmp);
        if (!tmp.exists() && !tmp.mkdirs()) return false;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip))) {
            byte[] buf = new byte[64 * 1024];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                // v1.1.11 FIX: entri ditulis ke "tmp" (BUKAN dst) — dulu entri
                // ditulis ke dst tapi verifikasi mengecek tmp/lang (folder tmp
                // kosong) sehingga verifikasi selalu gagal. Zip-slip guard
                // sekarang memvalidasi terhadap folder tmp yang benar.
                File out = new File(tmp, e.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(tmp.getCanonicalPath() + File.separator)
                        && !canonical.equals(tmp.getCanonicalPath())) {
                    zin.closeEntry(); // zip-slip guard
                    continue;
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(out);
                int r;
                while ((r = zin.read(buf)) > 0) fos.write(buf, 0, r);
                fos.close();
                zin.closeEntry();
            }
            zin.close();
            // tandai selesai: folder "lang" harus ada — verifikasi sebelum rename
            if (!new File(tmp, "lang").exists()) {
                deleteRecursive(tmp);
                return false;
            }
            if (dst.exists()) deleteRecursive(dst);
            if (!tmp.renameTo(dst)) {
                deleteRecursive(tmp);
                return false;
            }
            zip.delete();
            return true;
        } catch (Exception ex) {
            android.util.Log.e(TAG, "extract espeak gagal", ex);
            deleteRecursive(tmp);
            return false;
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteRecursive(c);
        f.delete();
    }

    /** Hapus data espeak (folder terekstrak + zip). Dipanggil Library saat Delete. */
    public static void deleteEspeakData(Context ctx) {
        deleteRecursive(VoiceCatalog.espeakDataDir(ctx));
        new File(new File(ctx.getFilesDir(), "models"),
                VoiceCatalog.PIPER_ESPEAK_ID + ".zip").delete();
        new File(new File(ctx.getFilesDir(), "models"),
                VoiceCatalog.PIPER_ESPEAK_ID + ".bin").delete();
        new File(new File(ctx.getFilesDir(), "models"),
                VoiceCatalog.PIPER_ESPEAK_ID + ".part").delete();
    }

    // ============================================================= AudioTrack

    private AudioTrack track;

    /** Mulai memutar PCM 16-bit mono (float sampel di-convert ke 16-bit).
     *  @return true bila AudioTrack berhasil mulai main. */
    public synchronized boolean play(GeneratedAudio audio) {
        stopPlayback();
        if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) return false;
        float[] s = audio.getSamples();
        short[] pcm = new short[s.length];
        for (int i = 0; i < s.length; i++) {
            float v = s[i];
            if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
            pcm[i] = (short) (v * 32767f);
        }
        int sr = audio.getSampleRate();
        int minBuf = AudioTrack.getMinBufferSize(sr,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return false;
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minBuf, sr * 2))
                .build();
        track = t;
        try {
            t.play();
        } catch (Exception e) {
            try { t.release(); } catch (Exception ignored) { }
            track = null;
            return false;
        }
        // write() blocking sampai semua buffer diantre ke mixer.
        int w = t.write(pcm, 0, pcm.length);
        return w > 0;
    }

    /** Berhenti + buang AudioTrack (aman dipanggil dua kali). */
    public synchronized void stopPlayback() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) { }
            try { t.flush(); } catch (Exception ignored) { }
            try { t.stop(); } catch (Exception ignored) { }
            try { t.release(); } catch (Exception ignored) { }
        }
    }
}
