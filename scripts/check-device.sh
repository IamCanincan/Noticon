#!/usr/bin/env bash
# Verify the module on a connected device and dump its log.
#
# Usage (Git Bash, from the repo root):
#   ./scripts/check-device.sh
#
# Messages are ASCII-only on purpose: Windows consoles mangle UTF-8 in .sh/.bat.

set -uo pipefail

APK="Noticon-v1.0-release.apk"
OUT="noticon.log"

# --- locate adb -------------------------------------------------------------
ADB=""
for c in \
  "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" \
  "$ANDROID_HOME/platform-tools/adb.exe" \
  "/d/platform-tools/adb.exe" \
  "adb"
do
  if [ -n "${c:-}" ] && command -v "$c" >/dev/null 2>&1; then ADB="$c"; break; fi
  if [ -n "${c:-}" ] && [ -x "$c" ]; then ADB="$c"; break; fi
done
if [ -z "$ADB" ]; then
  echo "adb not found. Install Android SDK platform-tools or set ANDROID_HOME."
  exit 1
fi
echo "adb: $ADB"

# --- device present? --------------------------------------------------------
DEVICE=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
if [ -z "$DEVICE" ]; then
  echo "No authorized device. Check: USB debugging on, RSA prompt accepted."
  "$ADB" devices
  exit 1
fi
echo "device: $DEVICE"

# --- install ----------------------------------------------------------------
if [ ! -f "$APK" ]; then
  echo "$APK not found. Run ./gradlew assembleRelease and sign it first."
  exit 1
fi
echo "==> installing $APK"
"$ADB" install -r "$APK" || { echo "install failed"; exit 1; }

# --- manual step ------------------------------------------------------------
echo
echo "==== MANUAL STEP (cannot be done over adb) ===="
echo "1. Open your Xposed manager (LSPosed / Vector)"
echo "2. Modules -> enable Noticon"
echo "3. Scope -> tick 'SystemUI' / 'com.android.systemui'"
echo "==============================================="
read -r -p "Press Enter when done... "

# --- restart ----------------------------------------------------------------
echo
echo "How to reload SystemUI?"
echo "  1) restart SystemUI only  (fast, usually enough)"
echo "  2) full reboot            (more reliable)"
read -r -p "Choose 1 or 2 [1]: " MODE
MODE=${MODE:-1}

"$ADB" logcat -c

if [ "$MODE" = "2" ]; then
  echo "==> rebooting, wait for the device to come back..."
  "$ADB" reboot
  "$ADB" wait-for-device
  sleep 25
else
  echo "==> restarting SystemUI"
  PID=$("$ADB" shell pidof com.android.systemui | tr -d '\r')
  if [ -n "$PID" ]; then
    "$ADB" shell kill "$PID" || true
  else
    echo "could not find SystemUI pid, falling back to reboot"
    "$ADB" reboot
    "$ADB" wait-for-device
  fi
  sleep 12
fi

# --- collect ----------------------------------------------------------------
echo "==> collecting log"
"$ADB" logcat -s Noticon -d > "$OUT" 2>&1

echo
echo "----- $OUT -----"
cat "$OUT"
echo "----------------"
LINES=$(grep -c . "$OUT" || true)
if [ "${LINES:-0}" -eq 0 ]; then
  echo "No output. Likely causes:"
  echo "  - module not enabled, or SystemUI not in scope"
  echo "  - framework does not support LibXposed API 102"
  echo "  - SystemUI had not finished restarting yet (try again: adb logcat -s Noticon)"
else
  echo "Saved to $OUT - paste it back for analysis."
fi
