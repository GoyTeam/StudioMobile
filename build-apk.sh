#!/usr/bin/env bash
set -euo pipefail
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Xmx384m}"
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/data/android-sdk}}"
TOOLS="$SDK/build-tools/35.0.0"
ANDROID="$SDK/platforms/android-35/android.jar"
BUILD="$PWD/build"
mkdir -p "$BUILD" "$BUILD/classes" "$BUILD/generated" "$BUILD/dex"
rm -rf "$BUILD/classes"/* "$BUILD/generated"/* "$BUILD/dex"/*
(cd app/libs; sha256sum -c SHA256SUMS >/dev/null)
"$TOOLS/aapt2" compile --dir app/src/main/res -o "$BUILD/resources.zip"
sed 's/<manifest /<manifest package="dev.falix.studiomobile" /' app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
"$TOOLS/aapt2" link -I "$ANDROID" -A app/src/main/assets --manifest "$BUILD/AndroidManifest.xml" --java "$BUILD/generated" --min-sdk-version 26 --target-sdk-version 35 --version-code 5 --version-name 0.5.0 -o "$BUILD/resources.apk" "$BUILD/resources.zip"
find app/src/main/java "$BUILD/generated" -name '*.java' > "$BUILD/sources.txt"
javac --release 8 -encoding UTF-8 -cp "$ANDROID:app/libs/*" -d "$BUILD/classes" @"$BUILD/sources.txt"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$TOOLS/d8" --lib "$ANDROID" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar" app/libs/*.jar
cp "$BUILD/resources.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex"; zip -q -j "$BUILD/unsigned.apk" classes*.dex)
"$TOOLS/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
# Shared GoyTeam debug certificate (committed): local script, Gradle debug builds and CI all
# sign with the same identity so a new APK installs over the previous one without uninstalling.
KEYSTORE="${STUDIO_KEYSTORE:-keystore/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  echo "Missing shared debug keystore: $KEYSTORE (run keytool -genkeypair as documented in CONTRIBUTING.md)" >&2
  exit 1
fi
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias goyteam-debug --ks-pass pass:android --key-pass pass:android --out "$BUILD/StudioMobile-GoyTeam-0.5.0-debug.apk" "$BUILD/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$BUILD/StudioMobile-GoyTeam-0.5.0-debug.apk"
echo 'Debug-signed sideload APK: build/StudioMobile-GoyTeam-0.5.0-debug.apk'
