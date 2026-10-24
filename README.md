# Rlnova

Offline AI chat for Android. Runs large language models fully on-device via llama.cpp + GGUF — no account, no network, no tracking. Your conversations never leave the phone.

## Features

- **Model library with RAM tiers** — the app detects your device RAM and only lets you download models your phone can actually run (LOW ≤ 4.5 GB, MID ≤ 8.5 GB, HIGH above).
- **Download manager** — fetch GGUF models from Hugging Face with pause/resume (HTTP Range), a persistent notification that survives the app being closed, and ETA + speed readout.
- **Streaming chat** — token-by-token responses, stop button, per-model chat templates (ChatML, Llama 3, Gemma), conversation history in SQLite.
- **Optimization panel** — benchmark prefill/decode tok/s, tune threads / GPU layers / context size, one-tap auto-tune.
- **English UI, dark theme**, zero third-party dependencies — pure AOSP APIs, native Android Views, Java + JNI.

## Model tiers

| Tier | RAM | Models (Q4_K_M) |
|------|-----|-----------------|
| LOW | ≤ 4.5 GB | Qwen3-0.6B, SmolLM2-360M, Llama-3.2-1B |
| MID | ≤ 8.5 GB | Gemma-2-2B-it, Llama-3.2-3B, Qwen2.5-3B |
| HIGH | > 8.5 GB | Llama-3.1-8B, Qwen2.5-7B, Gemma-3-4B |

The catalog lives in `app/src/main/assets/models.json` — add your own entries following the same schema.

## Building

Requirements: Linux, Android SDK (build-tools 34, platform android-34), JDK 17+.
The native libraries (`libllama.so` etc.) are prebuilt for arm64-v8a and live in
`app/src/main/jniLibs/` — no NDK needed for a normal build. (To rebuild them
from source you need Android NDK r23 + CMake 3.28+: compile llama.cpp
`--target llama` with `-DGGML_OPENMP=OFF`, then drop the `.so` files into
`app/src/main/jniLibs/arm64-v8a/`.)

```bash
# Build the APK (no Gradle — build.sh drives aapt2 + d8 + apksigner directly)
./build.sh
# -> ./Rlnova.apk
```

`build.sh` generates a debug keystore on first run. For a release build, sign with your own key.

## Project layout

- `app/src/main/java/com/rezz/rlnova/` — all app code (Java)
- `app/src/main/cpp/bridge.cpp` — JNI bridge over the llama.cpp C API
- `app/src/main/assets/models.json` — model catalog
- `SPEC.md` — full technical spec
- `design/` — design system notes, research, and the app logo

## License

MIT — see [LICENSE](LICENSE).
