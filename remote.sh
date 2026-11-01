#!/bin/sh
# Turns the /remote page's remote on or off: arrows, OK, Back, Home and the power menu. It's an
# accessibility service ("Telebisyon remote"), which only adb or the TV's Accessibility settings can turn on.
# Volume and play/pause work without it. Needs the web page on (messages.sh). Set a PIN too (pin.sh).
#
#   sh remote.sh <TV_IP>        turns it on
#   sh remote.sh <TV_IP> off    turns it off
set -e

TARGET="$1"; MODE="${2:-on}"
[ -n "$TARGET" ] || { echo "Usage: sh remote.sh <TV_IP> [on|off]" >&2; exit 1; }
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
SERVICE=$PKG/$PKG.Remote
ip=${TV%%:*}

# The TV's list of accessibility services that are on, without this one. Others stay as they are.
others=$(adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r' \
  | tr ':' '\n' | grep -v -x -e "$SERVICE" -e "null" -e "" | tr '\n' ':' | sed 's/:$//')

case "$MODE" in
  off)
    if [ -n "$others" ]; then adb -s "$TV" shell settings put secure enabled_accessibility_services "$others"
    else adb -s "$TV" shell settings delete secure enabled_accessibility_services >/dev/null; fi
    echo "The remote is off. Volume and play/pause still work on http://$ip:7979/remote" ;;
  on)
    # Right after an install or update, Android can drop a service it hasn't registered yet, so this
    # checks it stayed on, and sets it again if not.
    on=
    for _try in 1 2 3 4 5; do
      adb -s "$TV" shell settings put secure enabled_accessibility_services "${others:+$others:}$SERVICE"
      adb -s "$TV" shell settings put secure accessibility_enabled 1
      sleep 2
      if adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r' | tr ':' '\n' | grep -q -x "$SERVICE"; then
        on=1; break
      fi
    done
    if [ -z "$on" ]; then
      echo "The TV didn't keep the remote on. Turn it on there: Settings > Accessibility > Telebisyon remote." >&2
      exit 1
    fi
    echo "The remote is on: http://$ip:7979/remote"
    if ! adb -s "$TV" shell appops get "$PKG" SYSTEM_ALERT_WINDOW | grep -q allow; then
      echo "The web page itself is off. Turn it on with: sh messages.sh $ip"
    fi
    echo "Anyone on your Wi-Fi can use it unless you set a PIN: sh pin.sh $ip 1234" ;;
  *) echo "Usage: sh remote.sh <TV_IP> [on|off]" >&2; exit 1 ;;
esac
