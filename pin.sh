#!/bin/sh
# Puts the TV's web pages behind a PIN: browsers ask for it once, then remember it. Scripts and
# Home Assistant send it as an "X-Pin: 1234" header. Messages (POST /notify) never need it, so
# whatever sends PiPup messages keeps working. A new PIN signs every browser out.
#
#   sh pin.sh <TV_IP> 1234     sets the PIN (4 or more digits or letters; 6 or more is better)
#   sh pin.sh <TV_IP> off      takes it away
set -e

TARGET="$1"; PIN="$2"
[ -n "$TARGET" ] && [ -n "$PIN" ] || { echo "Usage: sh pin.sh <TV_IP> <PIN|off>" >&2; exit 1; }
case "$PIN" in
  off) ;;
  *[!A-Za-z0-9]*) echo "Use only digits and letters for the PIN." >&2; exit 1 ;;
  ???*) [ ${#PIN} -ge 4 ] || { echo "Use 4 or more digits or letters." >&2; exit 1; } ;;
  *) echo "Use 4 or more digits or letters." >&2; exit 1 ;;
esac
command -v adb >/dev/null 2>&1 || { echo "adb is missing. Mac: brew install android-platform-tools" >&2; exit 1; }

case "$TARGET" in
  *.*.*.*:*) TV=$TARGET ;;
  *.*.*.*)   TV=$TARGET:5555 ;;
  *)         TV=$TARGET ;;
esac
case "$TV" in *:*) adb connect "$TV" >/dev/null || true ;; esac
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach $TV. Check the TV is on and debugging is allowed." >&2; exit 1; }
PKG=com.knz.telebisyon
adb -s "$TV" shell pm path "$PKG" >/dev/null 2>&1 || { echo "The home screen isn't installed. Run install.sh first." >&2; exit 1; }

adb -s "$TV" shell am start -n "$PKG/.MainActivity" --es pin "$PIN" --es stamp "pin-$(date +%s)" >/dev/null
if [ "$PIN" = off ]; then echo "No PIN now: anyone on your Wi-Fi can use the TV's web pages."
else echo "PIN set. Browsers will ask for it once. Home Assistant: add the header  X-Pin: $PIN"; fi
