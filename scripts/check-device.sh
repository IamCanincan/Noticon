#!/usr/bin/env bash
# Verify the module on a connected device and dump its log.
#
# Usage (Git Bash, from the repo root):
#   ./scripts/check-device.sh
#
# Messages are ASCII-only on purpose: Windows consoles mangle UTF-8 in .sh/.bat.

set -uo pipefail

APK="Noticon-v1.1.0-release.apk"
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
echo "==== MANUAL STEPS (cannot be done over adb) ===="
echo "1. Open your Xposed manager (LSPosed / Vector)"
echo "2. Modules -> enable Noticon"
echo "   (no scope to tick: the module declares staticScope=true and ships a"
echo "    fixed scope.list containing only com.android.systemui)"
echo "3. Optional: open Noticon in the launcher to pick an icon mode."
echo "   Defaults apply when the app was never opened."
echo "==============================================="
read -r -p "Press Enter when done... "

# --- restart ----------------------------------------------------------------
# Reboot only. Do NOT use `am force-stop com.android.systemui` here:
# the static wallpaper engine (ImageWallpaper) runs inside the SystemUI process,
# and WallpaperManagerService treats a force-stopped package as an uninstalled
# one, so it clears the wallpaper and the user is left with the default gradient.
# `shell kill <pid>` is not an option either - the shell user may not signal
# SystemUI ("Operation not permitted").
echo
echo "==> rebooting to reload SystemUI (force-stop would wipe the wallpaper)"
adb logcat -c
adb reboot
adb wait-for-device

# --- collect ----------------------------------------------------------------
# Poll while the system boots instead of sleeping first and dumping once.
# Each logcat buffer is only 256 KiB, and a busy boot can roll it over within a
# minute - a single `logcat -d` after a fixed sleep then comes back empty even
# though the module logged everything fine. Polling catches the lines while
# they are still in the buffer; the dedup pass below removes the repeats.
echo "==> collecting log (up to ~90s, stops early once the hooks are reported)"
: > "$OUT"
for _ in $(seq 1 30); do
  adb logcat -s Noticon -d >> "$OUT" 2>&1 || true
  if grep -q "inflateViews hooked" "$OUT" 2>/dev/null; then break; fi
  sleep 2
done
awk '!seen[$0]++' "$OUT" > "$OUT.tmp" && mv "$OUT.tmp" "$OUT"

echo
echo "----- $OUT -----"
cat "$OUT"
echo "----------------"
LINES=$(grep -c . "$OUT" || true)
if [ "${LINES:-0}" -eq 0 ]; then
  echo "No output. Likely causes:"
  echo "  - module not enabled in the manager"
  echo "  - framework does not support LibXposed API 102"
  echo "  - SystemUI had not finished restarting yet"
  echo "  - the log buffer rolled over before we read it. Re-check right after a"
  echo "    fresh event: adb -P $ADB_PORT logcat -c, then trigger a notification,"
  echo "    then adb -P $ADB_PORT logcat -s Noticon -d"
else
  echo "Saved to $OUT - paste it back for analysis."
fi
