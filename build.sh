#!/usr/bin/env bash
# Dawn Patrol — native Android build. No Gradle, no WebView, pure SDK build-tools
# + ONNX Runtime. Adapted from the field-tested metro-hacks raw-SDK pipeline.
# Produces a signed DawnPatrol.apk (arm64-v8a).
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/data/android-sdk}"
BT="$(ls -d "$SDK"/build-tools/34* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-34* 2>/dev/null | sort -V | tail -1)/android.jar"
[ -e "$PLATFORM" ] || PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/data/jdk/jdk-17.0.20.1+1}"
export PATH="$JAVA_HOME/bin:$PATH"

ORT_VER="1.16.3"
ORT_AAR="onnxruntime-android-${ORT_VER}.aar"
ORT_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ORT_VER}/${ORT_AAR}"

echo "== build-tools: $BT"
echo "== platform:    $PLATFORM"

# ---------------------------------------------------------------- assets
A="app/src/main/assets"
mkdir -p "$A"
cp -f /opt/data/dawn-patrol/model/yamnet.onnx "$A/yamnet.onnx"
cp -f /opt/data/dawn-patrol/model/bird-classes.json "$A/bird-classes.json"
cp -f /opt/data/dawn-patrol/model/yamnet_class_map.csv "$A/yamnet_class_map.csv"
cp -f /opt/data/dawn-patrol/samples/dawn-chorus-demo.wav "$A/dawn-chorus-demo.wav"

# ---------------------------------------------------------------- ONNX Runtime AAR
if [ ! -s "ort/$ORT_AAR" ] && [ -s "/opt/data/metro-hacks/android-native/ort/$ORT_AAR" ]; then
  mkdir -p ort && cp "/opt/data/metro-hacks/android-native/ort/$ORT_AAR" "ort/$ORT_AAR"
  echo "== ORT AAR: reused metro-hacks cache"
fi
if [ ! -s "ort/$ORT_AAR" ]; then
  mkdir -p ort
  echo "== downloading $ORT_AAR"
  curl -sL --fail --retry 3 -o "ort/$ORT_AAR" "$ORT_URL"
fi
rm -rf ort/x && mkdir -p ort/x
(cd ort/x && unzip -q "../$ORT_AAR")
cp ort/x/classes.jar ort/classes.jar
# arm64-v8a only: every modern phone. (x86 emulators will not run the model —
# documented in README; real devices are the target.)
mkdir -p nativelib/lib/arm64-v8a
cp ort/x/jni/arm64-v8a/*.so nativelib/lib/arm64-v8a/
echo "== ORT: $(du -sh ort/classes.jar | cut -f1) classes + $(find nativelib/lib -name '*.so' | wc -l) native libs"

# ---------------------------------------------------------------- compile
rm -f res.zip unsigned.apk aligned.apk DawnPatrol.apk
rm -rf obj dexout gen && mkdir -p obj dexout gen

"$BT/aapt2" compile --dir app/src/main/res -o res.zip
"$BT/aapt2" link -o unsigned.apk -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "${VERSION_CODE:-1}" --version-name "1.0" \
  --java gen \
  res.zip

javac -source 11 -target 11 -classpath "$PLATFORM:ort/classes.jar" -d obj \
  $(find app/src/main/java gen -name '*.java')

"$BT/d8" --lib "$PLATFORM" --release --output dexout \
  $(find obj -name '*.class') ort/classes.jar

# ---------------------------------------------------------------- pack
BUILD_DIR="$(pwd)"
(cd dexout && zip -q "$BUILD_DIR/unsigned.apk" classes.dex)
# zip from app/src/main (NOT from inside assets/) so entries land at assets/…
(cd app/src/main && zip -qr "$BUILD_DIR/unsigned.apk" assets)
(cd nativelib && zip -qr "$BUILD_DIR/unsigned.apk" lib)

"$BT/zipalign" -f 4 unsigned.apk aligned.apk

if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias dawnpatrol \
    -storepass dawnpatrol -keypass dawnpatrol \
    -dname "CN=DawnPatrol,O=Hacktoberfest,C=IN" -keyalg RSA -keysize 2048 -validity 10000
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:dawnpatrol \
  --ks-key-alias dawnpatrol --key-pass pass:dawnpatrol \
  --out DawnPatrol.apk aligned.apk

echo "== APK_SIZE: $(du -h DawnPatrol.apk | cut -f1)"
echo "== sha256:   $(sha256sum DawnPatrol.apk | cut -d' ' -f1)"
