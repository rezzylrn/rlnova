# Rlnova

Offline AI chat for Android. Runs large language models fully on-device via llama.cpp + GGUF — no account, no tracking. Your conversations never leave the phone.

## Features

- **Model library with RAM tiers** — the app detects your device RAM and only lets you download models your phone can actually run (LOW ≤ 4.5 GB, MID ≤ 8.5 GB, HIGH above).
- **Download manager** — fetch GGUF models from Hugging Face with pause/resume (HTTP Range), a persistent notification that survives the app being closed, and ETA + speed readout.
- **Streaming chat** — token-by-token responses, stop button, per-model chat templates (ChatML, Llama 3, Gemma), conversation history in SQLite.
- **Web research toggle (v1.2.2)** — optional per-chat toggle in the composer. When ON, the app searches the web first (keyless DuckDuckGo HTML endpoint, no API key), then the local model answers grounded in the retrieved results with numbered citations. When OFF, chat is fully offline as before. If there is no internet or no results, it says so honestly — never invents an answer.
- **Optimization panel** — benchmark prefill/decode tok/s, tune threads / GPU layers / context size, one-tap auto-tune.
- **English UI, dark theme**, zero third-party dependencies — pure AOSP APIs, native Android Views, Java + JNI.

> Note: the v1.2.0 "Offline Research" tab (bundled knowledge package + BM25
> retrieval) was removed in v1.2.2 — it was replaced by the web research
> toggle. The old `POIDH #31` bounty claim is no longer pursued.

## Web research (v1.2.2)

- **Search endpoint:** `https://html.duckduckgo.com/html/` (HTML form POST,
  `q=<query>&kl=wt-wt`) — keyless, free, no API key anywhere in the code.
  Results (title, real URL after unwrapping DDG's `uddg=` redirect, snippet)
  are parsed with regex; up to 6 hits are injected into the prompt.
- **Answering stays local:** inference is always the on-device llama.cpp
  model; only the evidence source changes (web instead of nothing). The
  system prompt forbids answering outside the evidence and requires `[N]`
  citations matching the numbered results.
- **Honest failure:** no internet, HTTP error, or zero parsed results → the
  chat shows an explicit "No internet connection or no results" error instead
  of a fabricated answer.
- The toggle is per chat session (resets when the room is reopened).

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

- `app/src/main/java/com/rezz/rlnova/` — all app code (Java); `WebSearch.java`
  = keyless DDG search client used by the web research toggle
- `app/src/main/cpp/bridge.cpp` — JNI bridge over the llama.cpp C API
- `app/src/main/assets/models.json` — model catalog
- `tests/` — headless tests; `tests/P2-RESULTS.md` = legacy offline-research eval (v1.2.0, feature removed in v1.2.2)
- `SPEC.md` — full technical spec
- `design/` — design system notes, research, and the app logo

## License

MIT — see [LICENSE](LICENSE).
