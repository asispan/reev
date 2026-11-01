#!/bin/bash
# Build + install Reev on the USB-connected OnePlus, auto-confirming ColorOS's "Install via USB" screen.
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
./gradlew assembleDebug --console=plain -q > /tmp/fk_build.log 2>&1 || { grep -E "^e:|error" /tmp/fk_build.log | head -30; exit 1; }
echo "build ok"
before=$(adb shell dumpsys package com.asis.reev | grep -m1 lastUpdateTime || true)
adb install -r app/build/outputs/apk/debug/app-debug.apk > /tmp/fk_install.log 2>&1 &
pid=$!
for i in $(seq 1 30); do
  sleep 1
  if ! kill -0 $pid 2>/dev/null; then break; fi
  xy=$(adb shell 'uiautomator dump /sdcard/fkui.xml >/dev/null 2>&1; cat /sdcard/fkui.xml' 2>/dev/null | python3 -c '
import sys,re
x=sys.stdin.read()
m=re.search(r"text=\"Install\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",x)
print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}" if m else "")')
  if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped Install"; fi
done
wait $pid || true
cat /tmp/fk_install.log | tail -1
adb shell ime enable com.asis.reev/.ReevService >/dev/null
adb shell ime set com.asis.reev/.ReevService
adb shell input keyevent HOME
