#!/usr/bin/env bash
# Verify the module on a connected device and dump its log.
#
# Usage (Git Bash, from the repo root):
#   ./scripts/check-device.sh
#
# Messages are ASCII-only on purpose: Windows consoles mangle UTF-8 in .sh/.bat.

set -uo pipefail

APK="Noticon-v1.0.2-release.apk"
OUT="noticon.log"

# adb server port. The default 5037 falls inside the Windows excluded port range
# on some machines (netsh interface ipv4 show excludedportrange protocol=tcp),
# where bind() fails with 10013 and the server never comes up. 5039 is outside
# that range. Override with: ADB_PORT=5037 ./scripts/check-device.sh
ADB_PORT="${ADB_PORT:-5039}"

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

# Every call must carry -P <port>, otherwise adb talks to the default-port
# server (which may be a different, dead one) instead of the one we started.
adb() { "$ADB" -P "$ADB_PORT" "$@"; }

echo "adb: $ADB  (port $ADB_PORT)"

# --- device present? --------------------------------------------------------
DEVICE=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
if [ -z "$DEVICE" ]; then
  echo "No authorized device. Check: USB debugging on, RSA prompt accepted."
  adb devices
  exit 1
fi
echo "device: $DEVICE"

# --- install ----------------------------------------------------------------
if [ ! -f "$APK" ]; then
  echo "$APK not found. Run ./gradlew assembleRelease and sign it first."
  exit 1
fi
echo "==> installing $APK"
adb install -r "$APK" || { echo "install failed"; exit 1; }

# --- manual step ------------------------------------------------------------
echo
echo "==== MANUAL STEP (cannot be done over adb) ===="
echo "1. Open your Xposed manager (LSPosed / Vector)"
echo "2. Modules -> enable Noticon"
echo "   (no scope to tick: the module declares staticScope=true and ships a"
echo "    fixed scope.list containing only com.android.systemui)"
echo "==============================================="
read -r -p "Press Enter when done... "

# --- restart ----------------------------------------------------------------
echo
echo "How to reload SystemUI?"
echo "  1) restart SystemUI only  (fast, usually enough)"
echo "  2) full reboot            (more reliable)"
read -r -p "Choose 1 or 2 [1]: " MODE
MODE=${MODE:-1}

adb logcat -c

if [ "$MODE" = "2" ]; then
  echo "==> rebooting, wait for the device to come back..."
  adb reboot
  adb wait-for-device
  sleep 25
else
  # Note: do NOT use `shell kill <pid>` here. The shell user is not allowed to
  # signal the SystemUI process ("Operation not permitted"); force-stop lets the
  # framework kill and respawn it instead.
  echo "==> restarting SystemUI (force-stop)"
  adb shell am force-stop com.android.systemui || {
    echo "force-stop failed, falling back to reboot"
    adb reboot
    adb wait-for-device
    sleep 25
  }
  sleep 12
fi

# --- collect ----------------------------------------------------------------
echo "==> collecting log"
adb logcat -s Noticon -d > "$OUT" 2>&1

echo
echo "----- $OUT -----"
cat "$OUT"
echo "----------------"
LINES=$(grep -c . "$OUT" || true)
if [ "${LINES:-0}" -eq 0 ]; then
  echo "No output. Likely causes:"
  echo "  - module not enabled in the manager"
  echo "  - framework does not support LibXposed API 102"
  echo "  - SystemUI had not finished restarting yet (try again: adb -P $ADB_PORT logcat -s Noticon)"
else
  echo "Saved to $OUT - paste it back for analysis."
fi
