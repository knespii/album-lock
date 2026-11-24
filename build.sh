#!/usr/bin/env bash
# Builds and signs Album Lock with the Android SDK command-line tools (no Gradle).
# Usage: ./build.sh            -> build/AlbumLock.apk
#        ./build.sh install    -> also installs it on the phone connected over adb
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/${BUILD_TOOLS:-36.0.0}"
JAR="$SDK/platforms/${PLATFORM:-android-35}/android.jar"
# Any JDK 17+ works; JAVA_HOME wins, otherwise whatever javac is on PATH.
if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
for tool in "$BT/aapt2" "$JAR"; do
    [ -e "$tool" ] || { echo "Missing $tool (set ANDROID_HOME, BUILD_TOOLS or PLATFORM)" >&2; exit 1; }
done
command -v javac >/dev/null || { echo "javac not found (set JAVA_HOME to a JDK 17+)" >&2; exit 1; }

rm -rf build
mkdir -p build/res build/classes build/dex build/gen

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
    -o build/unsigned.apk build/res.zip

javac -nowarn --release 11 -classpath "$JAR" \
    -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --min-api 29 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

KS=signing.keystore
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass albumlock -keypass albumlock \
        -alias albumlock -dname "CN=Album Lock" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:albumlock --out build/AlbumLock.apk build/aligned.apk
echo "Built build/AlbumLock.apk"

if [ "${1:-}" = "install" ]; then
    adb install -r build/AlbumLock.apk
fi
