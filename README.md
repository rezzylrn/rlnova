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

## Offline Research (v1.2.0)

Vertical slice: ask questions against a bundled knowledge package, answered
fully on-device with citations.

- **Model:** Qwen3-0.6B, Q4_K_M quantization (single ~397 MB GGUF file).
- **Knowledge package** (`local-ai-privacy-v1`, "AI Lokal, Privasi, dan
  Inferensi On-Device" v1.0.0, 6 documents / 31 chunks, ~17 KB): a mix of
  CC0 1.0 original documents and Wikipedia excerpts (CC BY-SA 4.0 / CC BY-SA
  3.0, attributed per document). Full license details per document are in
  `app/src/main/assets/knowledge/manifest.json` and
  `research-package/manifest.md`.
- **Install a knowledge package:** the manifest + `docs/` live in
  `app/src/main/assets/knowledge/`. To swap packages, replace that folder
  (same `manifest.json` schema) and rebuild — the chunker/index is built
  from it at runtime.
- **Size budget:** APK ~50 MB, model ~397 MB, knowledge package ~17 KB,
  retrieval index built in memory at startup (no separate on-disk index).
  Total on-device footprint ≈ 447 MB.
- **Test offline:** enable airplane mode (or disable all networks), then run
  the Research screen. Retrieval, index build, and inference make no network
  calls; downloads of the model/package are the only networked steps. The
  P2 headless suite ran the identical retrieval + prompt-build code path on
  the host with all networking disabled.
- **Known limitations** (from the P2 headless results, see
  `tests/P2-RESULTS.md`): answers can be truncated at the generation cap —
  worst on long COMPARE tasks — because Qwen3's thinking trace consumes
  part of the token budget; out-of-scope declines depend on the small model
  echoing the retriever's decline message; synthesis depth is limited by the
  0.6B model (repeated paraphrases instead of integrated claims). Device QA
  pending: airplane-mode behavior, load time, tok/s, and peak memory are all
  unverified pending rezz's on-device QA. No claim is made of exceeding a
  "50% internet + frontier" bar.

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
- `app/src/main/assets/knowledge/` — bundled knowledge package (manifest + docs)
- `tests/` — headless tests; `tests/P2-RESULTS.md` = offline research eval results
- `research-package/` — source-of-truth knowledge package (same content as the asset)
- `SPEC.md` — full technical spec
- `design/` — design system notes, research, and the app logo

## Reproducing the P2 test

The test runs the same retrieval + prompt-build Java code path on the host
JVM, then generates with the host llama.cpp build — no Gradle, no device:

```bash
# 1. Host llama.cpp runner
cd third_party/llama.cpp && cmake -B build-host -DLLAMA_CURL=OFF && \
  cmake --build build-host --target llama-completion -j

# 2. Retrieval + prompt build (same Java code path as on-device)
JSON_JAR=~/m2repo/org/json/json/20180813/json-20180813.jar
cd tests && javac -cp "$JSON_JAR" P2Prompts.java TestResearch.java
java -cp .:"$JSON_JAR" P2Prompts ../research-package ../research-test/prompts

# 3. Generation: for each prompt file, run
third_party/llama.cpp/build-host/bin/llama-completion \
  -m research-test/qwen3-0.6b-q4km.gguf -f research-test/prompts/T1-factual.txt
```

Outputs land in `tests/p2-outputs/` (per-test answers + `answers.json` with
perf). Full verdicts: `tests/P2-RESULTS.md`.

## License

MIT — see [LICENSE](LICENSE).
