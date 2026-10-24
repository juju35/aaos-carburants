#!/usr/bin/env bash
# Runs the debug APK on the booted emulator: install, location in Rennes, launch, then
# screenshots of the list, a station and the fuel picker. Fails on a crash of the app.
# The templates host draws the app in a surface: no view tree to read, taps go by position
# (fractions of the screen, from the template layout).
# Usage: emulator-shots.sh <apk> <out dir> <name prefix>
set -uo pipefail
APK=$1 OUT=$2 PREFIX=$3
PKG=me.jstephan.carburants
mkdir -p "$OUT"

# Main display (the emulator also has the instrument cluster).
DISPLAY_ID=$(adb shell dumpsys SurfaceFlinger --display-id 2>/dev/null | sed -n 's/^Display \([0-9]*\).*/\1/p' | head -1)
read -r W H < <(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
echo "::notice::display ${DISPLAY_ID:-default} ${W}x${H}"

shot() {
  sleep "${2:-3}"
  adb exec-out screencap ${DISPLAY_ID:+-d $DISPLAY_ID} -p > "$OUT/$PREFIX-$1.png"
  echo "::notice::screenshot $PREFIX-$1 ($(wc -c < "$OUT/$PREFIX-$1.png") bytes)"
}

# Tap at fractions of the screen: tap 0.25 0.3
tap() {
  adb shell input tap "$(awk -v f="$1" -v w="$W" 'BEGIN{printf "%d", f*w}')" "$(awk -v f="$2" -v h="$H" 'BEGIN{printf "%d", f*h}')"
}

crashed() {
  adb logcat -d -b crash | grep -q "Process: $PKG"
}

# Errors the templates host shows instead of the app ("has encountered an unexpected error").
host_error() {
  adb logcat -d | grep -q -E "CarApp\.H[^:]*: Error:"
}

report_host_error() {
  adb logcat -d | grep -E "CarApp\.H[^:]*: (Error|	at |Caused)" | head -20 | while read -r l; do echo "::error::$l"; done
  exit 1
}

report_crash() {
  # The exception and the app frames, not the 100 lines of binder plumbing.
  adb logcat -d -b crash | grep -E "FATAL|Process:|Exception|Error|Caused by|me\.jstephan" | head -25 \
    | while read -r l; do echo "::error::$l"; done
  exit 1
}

adb install -r -g "$APK" | tail -1
adb shell cmd location set-location-enabled true || true
for _ in 1 2 3; do adb emu geo fix -1.6795 48.1105 >/dev/null; sleep 1; done
adb logcat -c

adb shell am start -W -n "$PKG/androidx.car.app.activity.CarAppActivity" | grep -E "Status|TotalTime" | while read -r l; do echo "::notice::$l"; done
for _ in 1 2 3; do adb emu geo fix -1.6795 48.1105 >/dev/null; sleep 2; done
# Content within 10 s (DR-3); more time for names and logos.
shot list-10s 8
sleep 12
crashed && report_crash
host_error && report_host_error

# Positions in the template, landscape and portrait differ.
# Landscape 1024x768: list panel 18-505 px, first row around y 250; fuel button at (758, 130).
# Portrait 800x1280: panel 18-398 px, first row around y 260; fuel button at (533, 130).
# Back arrow of the header at (56, 126) in both; SP98 switch, 3rd row of the fuel picker.
if [ "$W" -gt "$H" ]; then
  ROW="0.30 0.32"; FUEL="0.74 0.17"; BACK="0.055 0.165"; SP98="0.83 0.60"
else
  ROW="0.32 0.20"; FUEL="0.67 0.10"; BACK="0.07 0.10"; SP98="0.78 0.36"
fi

# Follow SP98 too: the list then shows both prices.
tap $FUEL
sleep 4
tap $SP98
shot fuel 3
tap $BACK
shot list 15
crashed && report_crash
host_error && report_host_error

tap $ROW
shot detail 5
crashed && report_crash
host_error && report_host_error

adb logcat -d | grep -E " (E|W) (StationList|StationDetail|BrandCache|CachedDownload|BrandLogos|AndroidRuntime|CarApp)" | tail -20 \
  | while read -r l; do echo "::warning::$l"; done
exit 0
