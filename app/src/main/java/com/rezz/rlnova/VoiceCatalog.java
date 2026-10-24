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

    /** Model suara whisper tiny multilingual. */
    public static final String WHISPER_TINY_ID = "voice-whisper-tiny";
    public static final String WHISPER_TINY_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin";
    public static final long WHISPER_TINY_SIZE_BYTES = 77691713L; // ggml-tiny.bin (~75 MB)

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

    /** Satu-satunya model suara saat ini: whisper tiny multilingual. */
    public static VoiceModel whisperTiny() {
        return new VoiceModel(WHISPER_TINY_ID,
                "Whisper Tiny (Speech-to-Text)",
                "On-device speech-to-text (multilingual). Tap the mic in chat to dictate instead of typing.",
                WHISPER_TINY_SIZE_BYTES, WHISPER_TINY_URL, 3);
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
