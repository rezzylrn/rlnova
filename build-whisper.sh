#!/bin/bash
# Rlnova — build whisper.cpp (CPU-only) menjadi SATU librlnova-whisper.so statik.
#
# Kenapa statik: whisper.cpp vendor ggml-nya sendiri (0.27), sedangkan llama.cpp
# di APK ini vendor ggml 0.16 sebagai shared libs (libggml*.so). Kalau dua-duanya
# di-ship sebagai .so terpisah dengan soname sama, linker Android bakal resolve
# simbol ggml_* lintas versi (interposition) — hampir pasti crash. Dengan link
# statik + visibility hidden, ggml whisper tidak terekspor sama sekali.
#
# Output: app/src/main/jniLibs/arm64-v8a/librlnova-whisper.so
# NEEDED eksternal: liblog, libm, libdl, libc++_shared (disalin manual dari NDK).
set -e

PROJ=$HOME/workspace/offline-ai-app
NDK=$HOME/workspace/tools/android-sdk/ndk/23.1.7779620
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
WS=$PROJ/third_party/whisper.cpp
BLD=$PROJ/build-whisper-static
JNI=$PROJ/app/src/main/jniLibs/arm64-v8a

# 1. configure (idempotent)
mkdir -p $BLD 2>/dev/null || true
if [ ! -f $BLD/Makefile ]; then
  cmake -S $WS -B $BLD \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DGGML_OPENMP=OFF \
    -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_SERVER=OFF \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON > /dev/null
fi

# 2. build static libs
make -C $BLD -j$(nproc) whisper > /dev/null

# 3. compile JNI bridge + link statik (hidden visibility)
cd $PROJ
$TOOLCHAIN/aarch64-linux-android26-clang++ -std=c++17 -fPIC -O2 -fvisibility=hidden \
  -I$WS/include -I$WS/ggml/include \
  -c app/src/main/cpp/whisper-jni.cpp -o $BLD/whisper-jni.o

$TOOLCHAIN/aarch64-linux-android26-clang++ -std=c++17 -fPIC -O2 -fvisibility=hidden \
  -shared $BLD/whisper-jni.o \
  $BLD/src/libwhisper.a \
  $BLD/ggml/src/libggml.a \
  $BLD/ggml/src/libggml-base.a \
  $BLD/ggml/src/libggml-cpu.a \
  -llog -lm -latomic \
  -o app/src/main/jniLibs/arm64-v8a/librlnova-whisper.so

# 4. libc++_shared (dibutuhkan oleh whisper-jni; llama chain tidak memakainya)
cp $TOOLCHAIN/../sysroot/usr/lib/aarch64-linux-android/libc++_shared.so \
   app/src/main/jniLibs/arm64-v8a/libc++_shared.so

echo WHISPER_BUILD_OK
ls -la app/src/main/jniLibs/arm64-v8a/librlnova-whisper.so
