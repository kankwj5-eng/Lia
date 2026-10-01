#!/usr/bin/env bash
set -euo pipefail

PACKAGE="org.lia.accessibility"
SERVICE="org.lia.accessibility/org.lia.accessibility.accessibility.LiaAccessibilityService"
APK="app/build/outputs/apk/debug/app-debug.apk"
DIAG="build/emulator-diagnostics"

mkdir -p "$DIAG"

adb wait-for-device
adb logcat -c

echo "== Instrumented tests =="
gradle :app:connectedDebugAndroidTest --stacktrace

echo "== Install and launch Lía =="
adb install -r "$APK" >/dev/null

# CI emulator only: enable the accessibility service so Android registration is tested.
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
sleep 2

adb shell dumpsys accessibility > "$DIAG/accessibility.txt"
if ! grep -q "LiaAccessibilityService" "$DIAG/accessibility.txt"; then
  echo "Lía accessibility service was not registered by Android."
  exit 1
fi

adb shell am force-stop "$PACKAGE"
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 3

echo "== Capture diagnostics =="
adb shell dumpsys activity activities > "$DIAG/activity.txt"
adb shell dumpsys window windows > "$DIAG/window.txt"
adb shell uiautomator dump /sdcard/lia-window.xml >/dev/null 2>&1 || true
adb pull /sdcard/lia-window.xml "$DIAG/window.xml" >/dev/null 2>&1 || true
adb exec-out screencap -p > "$DIAG/main-screen.png" || true
adb logcat -d > "$DIAG/logcat.txt"

if ! grep -q "$PACKAGE" "$DIAG/activity.txt"; then
  echo "Lía is not present in the activity stack after launch."
  exit 1
fi

if grep -E -q "FATAL EXCEPTION|ANR in org\.lia\.accessibility|Process: org\.lia\.accessibility.*(has died|Fatal signal)" "$DIAG/logcat.txt"; then
  echo "Crash or ANR detected in Lía."
  grep -E -n "FATAL EXCEPTION|ANR in org\.lia\.accessibility|Process: org\.lia\.accessibility|Fatal signal" "$DIAG/logcat.txt" || true
  exit 1
fi

echo "Emulator smoke test passed."
