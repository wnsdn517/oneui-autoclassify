#!/bin/bash
# Waits for a fresh adb connection and a finished boot, then does everything that needs adb in one burst.
export MSYS_NO_PATHCONV=1
APK='C:\dev\folder\app\build\outputs\apk\debug\app-debug.apk'
adb kill-server >/dev/null 2>&1; adb start-server >/dev/null 2>&1
until [ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
sleep 3
echo "connected $(date +%T)"
timeout 60 adb install -r "$APK" && echo "install ok"
timeout 15 adb push 'C:\dev\folder\tools\ac.sh' /data/local/tmp/ac.sh && echo "push ok"
echo "done $(date +%T)"
