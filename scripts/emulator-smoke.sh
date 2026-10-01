#!/usr/bin/env bash
set -euo pipefail

PACKAGE="org.lia.accessibility"
SERVICE="org.lia.accessibility/org.lia.accessibility.accessibility.LiaAccessibilityService"
APK="app/build/outputs/apk/debug/app-debug.apk"
DIAG="build/emulator-diagnostics"

mkdir -p "$DIAG"

adb_retry() {
  local attempts=0
  until "$@"; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 6 ]; then
      return 1
    fi
    sleep 2
  done
}

echo "== Wait for Android =="
adb start-server >/dev/null
adb_retry adb wait-for-device

booted=""
for _ in $(seq 1 60); do
  booted="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [ "$booted" = "1" ]; then
    break
  fi
  sleep 2
done

if [ "$booted" != "1" ]; then
  echo "Android did not finish booting."
  exit 1
fi

adb_retry adb shell input keyevent 82 >/dev/null 2>&1 || true
adb_retry adb logcat -c

echo "== Instrumented tests =="
gradle :app:connectedDebugAndroidTest --stacktrace

echo "== Install and verify Lía package =="
adb_retry adb install -r "$APK" >/dev/null

adb shell dumpsys package "$PACKAGE" > "$DIAG/package.txt"
if ! grep -q "LiaAccessibilityService" "$DIAG/package.txt"; then
  echo "Android package manager did not register LiaAccessibilityService."
  exit 1
fi

echo "== Enable Lía accessibility service in CI emulator =="
# Android 13+ puede marcar como restringidos los ajustes de accesibilidad de
# aplicaciones instaladas fuera de Play. En el emulador CI autorizamos esa
# operación explícitamente antes de escribir enabled_accessibility_services.
adb shell appops set "$PACKAGE" ACCESS_RESTRICTED_SETTINGS allow >/dev/null 2>&1 || \
  adb shell appops set "$PACKAGE" android:access_restricted_settings allow >/dev/null 2>&1 || true

adb_retry adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb_retry adb shell settings put secure accessibility_enabled 1

enabled=""
for _ in $(seq 1 15); do
  enabled="$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r' || true)"
  if printf '%s' "$enabled" | grep -q "LiaAccessibilityService"; then
    break
  fi
  sleep 1
done

adb shell dumpsys accessibility > "$DIAG/accessibility.txt" || true
adb shell settings get secure enabled_accessibility_services > "$DIAG/enabled-accessibility-services.txt" || true

if ! printf '%s' "$enabled" | grep -q "LiaAccessibilityService"; then
  echo "Android did not persist Lía as an enabled accessibility service."
  exit 1
fi

echo "== Launch Lía =="
adb_retry adb shell am force-stop "$PACKAGE"
adb_retry adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null
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
