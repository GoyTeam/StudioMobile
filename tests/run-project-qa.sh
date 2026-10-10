#!/usr/bin/env bash
set -euo pipefail
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--Xmx256m}"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-/data/android-sdk}";TOOLS="$SDK/build-tools/35.0.0";ANDROID="$SDK/platforms/android-35/android.jar"
mkdir -p build/qa/classes build/qa/dex screenshots-v030
"$TOOLS/aapt2" link -I "$ANDROID" --manifest tests/AndroidManifest.xml -o build/qa/resources.apk
javac --release 8 -encoding UTF-8 -cp "$ANDROID:build/classes:app/libs/*" -d build/qa/classes tests/*.java
jar cf build/qa/classes.jar -C build/qa/classes .
"$TOOLS/d8" --lib "$ANDROID" --classpath build/classes.jar --min-api 26 --output build/qa/dex build/qa/classes.jar
cp build/qa/resources.apk build/qa/unsigned.apk
(cd build/qa/dex;zip -q -j ../unsigned.apk classes*.dex)
"$TOOLS/zipalign" -f 4 build/qa/unsigned.apk build/qa/aligned.apk
"$TOOLS/apksigner" sign --ks "$HOME/.android/studio-mobile-test.keystore" --ks-key-alias studio-test --ks-pass pass:android --key-pass pass:android --out build/qa/qa.apk build/qa/aligned.apk
"$SDK/platform-tools/adb" install --no-incremental -r build/StudioMobile-0.3.0-test.apk
"$SDK/platform-tools/adb" install --no-incremental -r build/qa/qa.apk
API=$("$SDK/platform-tools/adb" shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$API" -lt 30 ]; then
 "$SDK/platform-tools/adb" shell pm grant dev.falix.studiomobile android.permission.READ_EXTERNAL_STORAGE
 "$SDK/platform-tools/adb" shell pm grant dev.falix.studiomobile android.permission.WRITE_EXTERNAL_STORAGE
else
 "$SDK/platform-tools/adb" shell appops set --uid dev.falix.studiomobile MANAGE_EXTERNAL_STORAGE allow
 if [ "$API" -ge 33 ]; then "$SDK/platform-tools/adb" shell pm grant dev.falix.studiomobile android.permission.POST_NOTIFICATIONS; fi
fi
"$SDK/platform-tools/adb" shell settings put system screen_off_timeout 1800000
"$SDK/platform-tools/adb" shell svc power stayon true
"$SDK/platform-tools/adb" shell settings put global animator_duration_scale 1
"$SDK/platform-tools/adb" shell wm size 720x1280
"$SDK/platform-tools/adb" shell wm density 240
"$SDK/platform-tools/adb" shell am instrument -w dev.falix.studiomobile.qa/dev.falix.studiomobile.ProjectQa
"$SDK/platform-tools/adb" pull /sdcard/Android/data/dev.falix.studiomobile/files/qa030/. screenshots-v030/
