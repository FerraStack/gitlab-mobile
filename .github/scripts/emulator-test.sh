#!/usr/bin/env bash
# Runs inside reactivecircus/android-emulator-runner: installs the debug app and its device
# tests, runs the instant notifications end-to-end test and keeps its shade screenshot.
set -euo pipefail

out=emulator
mkdir -p "$out"
adb wait-for-device
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true

adb install -r -g apk/*.apk
adb install -r apk-test/*.apk
adb logcat -c || true

set +e
adb shell am instrument -w \
  -e class com.cboyxd.gitlab.InstantNotificationsDeviceTest \
  com.cboyxd.gitlab.test/androidx.test.runner.AndroidJUnitRunner | tee "$out/instrumentation.txt"
set -e

adb pull /sdcard/Android/data/com.cboyxd.gitlab/files/emulator_notification_shade.png "$out/" \
  || echo "No shade screenshot was saved."
adb logcat -d -v time > "$out/logcat.txt" || true

if grep -q 'FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed' "$out/instrumentation.txt" \
  || ! grep -q '^OK (' "$out/instrumentation.txt"; then
  echo "::error::The instant notifications device test failed; see instrumentation.txt and logcat.txt in the emulator artifact."
  exit 1
fi
