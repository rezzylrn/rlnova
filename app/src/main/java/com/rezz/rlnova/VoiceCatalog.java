package com.rezz.rlnova;

import android.content.Context;

import java.io.File;

/**
 * Katalog model suara (STT/TTS) — terpisah dari katalog LLM.
 *
 * v1.1.7: STT on-device via whisper.cpp. Model diunduh lewat DownloadService
 * yang sama (progress + notifikasi persisten), disimpan di
 * filesDir/models/<id>.bin (folder "models" = infra download yang sudah ada;
 * ekstensi .bin membedakan dari .gguf milik LLM).
 */
public final class VoiceCatalog {

    /** Model suara whisper tiny multilingual (STT). */
    public static final String WHISPER_TINY_ID = "voice-whisper-tiny";
    public static final String WHISPER_TINY_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin";
    public static final long WHISPER_TINY_SIZE_BYTES = 77691713L; // ggml-tiny.bin (~75 MB)

    // ---- v1.1.8: TTS on-device via sherpa-onnx (Piper VITS id_ID news_tts) ----
    // Satu voice Bahasa Indonesia resmi dari rilis tts-models k2-fsa
    // (vits-piper-id_ID-news_tts-medium, int8). File .onnx + espeak-ng-data
    // di-host ulang di release "rlnova-tts-data-v1" repo rlnova supaya bisa
    // di-download SATU FILE langsung (DownloadService tidak bisa ekstrak
    // arsip tar.bz2 dan zip dari URL langsung gak tersedia sebagai file
    // loose di server upstream — jadi di-host file hasil ekstrak manual di
    // release rlnova). espeak-ng-data (zip) diekstrak ke
    // filesDir/models/voice-piper-id-espeak/ oleh TtsEngine.maybeExtractEspeak
    // setelah download selesai. Jangan pakai API sistem TextToSpeech.
    public static final String PIPER_ID_ID = "voice-piper-id";
    /** ONNX int8 (diekstrak dari arsip resmi k2-fsa tts-models, di-host di release rlnova). */
    public static final String PIPER_ID_ONNX_DIRECT_URL =
            "https://github.com/rezzylrn/rlnova/releases/download/rlnova-tts-data-v1/"
            + "voice-piper-id.onnx";
    public static final long PIPER_ID_ONNX_SIZE_BYTES = 18301730L;   // int8 onnx
    public static final String PIPER_ESPEAK_ID = "voice-piper-id-espeak";
    /** espeak-ng-data resmi (zip dari rilis tts-models k2-fsa), diekstrak ke folder model. */
    public static final String PIPER_ESPEAK_URL =
            "https://github.com/rezzylrn/rlnova/releases/download/rlnova-tts-data-v1/"
            + "voice-piper-id-espeak.zip";
    public static final long PIPER_ESPEAK_SIZE_BYTES = 9037020L;     // espeak-ng-data.zip

    /** Model suara (bukan LLM). */
    public static final class VoiceModel {
        public final String id, name, description;
        public final long sizeBytes;
        public final String url;
        public final int minRamGb;

        VoiceModel(String id, String name, String description, long sizeBytes,
                   String url, int minRamGb) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.sizeBytes = sizeBytes;
            this.url = url;
            this.minRamGb = minRamGb;
        }
    }

    private VoiceCatalog() { }

    /** Satu-satunya model suara STT saat ini: whisper tiny multilingual. */
    public static VoiceModel whisperTiny() {
        return new VoiceModel(WHISPER_TINY_ID,
                "Whisper Tiny (Speech-to-Text)",
                "On-device speech-to-text (multilingual). Tap the mic in chat to dictate instead of typing.",
                WHISPER_TINY_SIZE_BYTES, WHISPER_TINY_URL, 3);
    }

    /** v1.1.8: model suara TTS Bahasa Indonesia (Piper news_tts, int8). */
    public static VoiceModel piperId() {
        return new VoiceModel(PIPER_ID_ID,
                "Piper Indonesian (Text-to-Speech)",
                "On-device text-to-speech in Bahasa Indonesia. Tap the speaker on an AI reply to hear it.",
                PIPER_ID_ONNX_SIZE_BYTES, PIPER_ID_ONNX_DIRECT_URL, 3);
    }

    /** Data phonemizer espeak-ng untuk Piper (diekstrak dari tar.gz setelah download). */
    public static VoiceModel piperEspeak() {
        return new VoiceModel(PIPER_ESPEAK_ID,
                "Piper phonemizer data",
                "Pronunciation data needed by the Piper TTS voice. Downloaded automatically with the voice.",
                PIPER_ESPEAK_SIZE_BYTES, PIPER_ESPEAK_URL, 3);
    }

    /** Kedua bagian TTS siap (onnx + folder espeak-ng-data terekstrak). */
    public static boolean isTtsReady(Context ctx) {
        return piperOnnxFile(ctx).exists()
                && piperIdOnnxSizeOk(ctx)
                && new File(ctx.getFilesDir(),
                        "models/" + PIPER_ESPEAK_ID + "/lang").exists();
    }

    /** File .onnx TTS di filesDir/models/. */
    public static File piperOnnxFile(Context ctx) {
        return localFile(ctx, PIPER_ID_ID);
    }

    private static boolean piperIdOnnxSizeOk(Context ctx) {
        File f = piperOnnxFile(ctx);
        return f.exists() && f.length() == PIPER_ID_ONNX_SIZE_BYTES;
    }

    /** Path folder espeak-ng-data terekstrak. */
    public static File espeakDataDir(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "models"), PIPER_ESPEAK_ID);
    }

    /** Path file model suara (app-private, folder models/ infra download). */
    public static File localFile(Context ctx, String id) {
        return new File(new File(ctx.getFilesDir(), "models"), id + ".bin");
    }

    /** Model suara siap dipakai: file ada + ukuran cocok (bila diketahui). */
    public static boolean isReady(Context ctx, VoiceModel m) {
        File f = localFile(ctx, m.id);
        return f.exists() && (m.sizeBytes <= 0 || f.length() == m.sizeBytes);
    }
}
