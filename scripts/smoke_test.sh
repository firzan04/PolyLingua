#!/usr/bin/env bash
# Emulator smoke test used by CI (x86_64 Android 11 image with ARM64 binary translation):
# installs the release APK, opens every screen through the launcher-shortcut "route" extra,
# captures screenshots, runs a monkey stress pass and fails on any crash or ANR.
#   scripts/smoke_test.sh <apk> <output-dir>
set -u
APK="$1"
OUT="$2"
PKG="com.polylingua.edge"
mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
: > "$SUMMARY"
log() { echo "$*" | tee -a "$SUMMARY"; }

adb wait-for-device
adb shell getprop ro.product.cpu.abilist | tee -a "$SUMMARY"
adb shell getprop ro.dalvik.vm.native.bridge | tee -a "$SUMMARY"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

log "== install"
if ! adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; then
  cat "$OUT/install.txt" | tee -a "$SUMMARY"
  log "RESULT: FAIL (install)"
  exit 1
fi
adb logcat -c

alive() { adb shell pidof "$PKG" > /dev/null 2>&1; }

shot() {
  sleep "$2"
  adb exec-out screencap -p > "$OUT/$1.png"
  if alive; then log "screen $1: running"; else log "screen $1: PROCESS DEAD"; fi
}

log "== cold start"
adb shell am start -W -n "$PKG/.MainActivity" | tee -a "$SUMMARY"
shot 01_home 25

i=2
for route in "setup" "arena/drill?caseId=-1" "arena/mock?caseId=-1" "elsa?caseId=-1" "tandem" "cake" "quiz/quiz?packages=" "quiz/flash?packages=" "dashboard" "home"; do
  name=$(printf '%02d_%s' "$i" "$(echo "$route" | tr '/?=&' '____' | cut -c1-24)")
  adb shell am start -n "$PKG/.MainActivity" --es route "'$route'" > /dev/null
  shot "$name" 7
  i=$((i + 1))
done

log "== memory"
adb shell dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|TOTAL RSS|Native Heap|Java Heap" | head -n 6 | tee -a "$SUMMARY"
adb shell dumpsys gfxinfo "$PKG" | grep -E "Total frames|Janky frames|90th|99th" | tee -a "$SUMMARY"

log "== monkey stress (1200 events)"
# System UI (e.g. the notification shade) can ANR on the software-rendered emulator; keep going
# and judge only this app's crashes/ANRs below.
adb shell monkey -p "$PKG" --pct-syskeys 0 --pct-appswitch 0 --pct-anyevent 0 --throttle 200 -s 4107 \
  --ignore-timeouts --ignore-native-crashes -v 1200 > "$OUT/monkey.txt" 2>&1
tail -n 5 "$OUT/monkey.txt" | tee -a "$SUMMARY"
shot 99_after_monkey 3

adb logcat -d > "$OUT/logcat-full.txt"
grep -E "AndroidRuntime|FATAL EXCEPTION|ANR in|PolyLingua|libc  |DEBUG   :" "$OUT/logcat-full.txt" | grep -v "PolyLinguaCores" | head -n 400 > "$OUT/logcat-app.txt"
APP_JAVA_CRASH=$(grep -A2 "FATAL EXCEPTION" "$OUT/logcat-full.txt" | grep -c "Process: $PKG" || true)
APP_NATIVE_CRASH=$(grep "Fatal signal" "$OUT/logcat-full.txt" | grep -c "polylingua" || true)
APP_ANR=$(grep -c "ANR in $PKG" "$OUT/logcat-full.txt" || true)
CRASHES=$((APP_JAVA_CRASH + APP_NATIVE_CRASH + APP_ANR))
MONKEY_CRASH=$(grep -cE "// (CRASH|NOT RESPONDING): $PKG" "$OUT/monkey.txt" || true)
OTHER=$(grep -cE "// (CRASH|NOT RESPONDING): " "$OUT/monkey.txt" || true)
log "other-process monkey reports (ignored): $((OTHER - MONKEY_CRASH))"
log "fatal/anr in logcat: $CRASHES · monkey crash markers: $MONKEY_CRASH"
if [ "$CRASHES" -gt 0 ] || [ "$MONKEY_CRASH" -gt 0 ]; then
  grep -E -A 30 "FATAL EXCEPTION|Fatal signal" "$OUT/logcat-full.txt" | head -n 120 > "$OUT/crash.txt"
  log "RESULT: FAIL"
  exit 1
fi
log "RESULT: PASS"
