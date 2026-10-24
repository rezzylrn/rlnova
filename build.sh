#!/bin/bash
# Rlnova - M2 build pipeline (manual aapt2/d8, tanpa Gradle)
set -e

PROJ=$HOME/workspace/offline-ai-app
SDK=$HOME/workspace/tools/android-sdk
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
APP=$PROJ/app/src/main
OUT=$PROJ/build
JNI=$PROJ/app/src/main/jniLibs/arm64-v8a
SHERPA_AAR=$PROJ/third_party/sherpa-onnx/sherpa-onnx-1.13.8.aar
SHERPA_JAR=$PROJ/third_party/sherpa-onnx/sherpa-onnx-classes.jar
SHERPA_LIBS=$PROJ/third_party/sherpa-onnx/arm64-v8a

export JAVA_HOME=$HOME/jdk17/jdk-17.0.11+9
export PATH=$JAVA_HOME/bin:$PATH

# Wajib ada native lib sebelum pack
test -f $JNI/libllama.so || { echo "ERROR: $JNI/libllama.so tidak ada"; exit 1; }

rm -rf $OUT/gen $OUT/obj $OUT/dex $OUT/stage $OUT/*.apk
mkdir -p $OUT/gen $OUT/obj $OUT/dex $OUT/stage/lib/arm64-v8a

echo "[1/8] aapt2 compile..."
$BT/aapt2 compile --dir $APP/res -o $OUT/res.zip

echo "[2/8] aapt2 link..."
$BT/aapt2 link -o $OUT/unsigned.apk \
  -I $AJAR \
  --manifest $APP/AndroidManifest.xml \
  --java $OUT/gen \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --auto-add-overlay \
  -A $APP/assets \
  $OUT/res.zip

echo "[3/8] javac..."
find $APP/java -name "*.java" > $OUT/sources.txt
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -cp "$AJAR:$OUT/gen:$SHERPA_JAR" \
  -d $OUT/obj \
  @$OUT/sources.txt

echo "[4/8] d8..."
rm -rf $OUT/dex && mkdir -p $OUT/dex
D8INPUTS=$(find $OUT/obj -name "*.class")
$BT/d8 --lib $AJAR --lib $SHERPA_JAR --min-api 26 --output $OUT/dex $D8INPUTS > $OUT/d8.log 2>&1
D8CODE=$?
grep -vE "^Warning" $OUT/d8.log | head -20
if [ $D8CODE -ne 0 ]; then echo "d8 gagal (exit $D8CODE)"; exit 1; fi
test -f $OUT/dex/classes.dex || { echo "d8 gagal: classes.dex tidak ada"; exit 1; }

echo "[5/8] pack dex + native libs..."
cp $OUT/unsigned.apk $OUT/app-unaligned.apk
(cd $OUT/dex && zip -q -X ../app-unaligned.apk *.dex)
cp $JNI/*.so $OUT/stage/lib/arm64-v8a/
cp $SHERPA_LIBS/*.so $OUT/stage/lib/arm64-v8a/
(cd $OUT/stage && zip -q -0 -X ../app-unaligned.apk -r lib)

echo "[6/8] zipalign..."
$BT/zipalign -p -f 4 $OUT/app-unaligned.apk $OUT/app-aligned.apk

echo "[7/8] sign..."
if [ ! -f $PROJ/debug.keystore ]; then
  keytool -genkeypair -keystore $PROJ/debug.keystore -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
$BT/apksigner sign --ks $PROJ/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out $PROJ/Rlnova.apk $OUT/app-aligned.apk

echo "[8/8] verify..."
$BT/apksigner verify --print-certs $PROJ/Rlnova.apk | head -3
$BT/aapt2 dump badging $PROJ/Rlnova.apk | head -8
ls -la $PROJ/Rlnova.apk
echo BUILD_OK
