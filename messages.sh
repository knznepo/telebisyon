#!/bin/sh
# Turns the TV's little web server on or off (port 7979): messages over whatever is playing, like
# PiPup (same API), the /wallpapers page, and control for Home Assistant (/status, /open, /media,
# /volume; see Messages.java). Anything on your network can use it while it's on.
# Only needs adb, and the home screen installed.
#
#   sh messages.sh <TV_IP>        turns them on
#   sh messages.sh <TV_IP> off    turns them off
#
# Then send one with send.sh, or from Home Assistant's PiPup setup pointed at this TV.
set -e

TARGET="$1"; MODE="${2:-on}"
[ -n "$TARGET" ] || { echo "Usage: sh messages.sh <TV_IP> [on|off]" >&2; exit 1; }
command -v adb >/dev/null 2>&1 || { echo "adb is missing. Mac: brew install android-platform-tools" >&2; exit 1; }

case "$TARGET" in
  *.*.*.*:*) TV=$TARGET ;;
  *.*.*.*)   TV=$TARGET:5555 ;;
  *)         TV=$TARGET ;;
esac
case "$TV" in *:*) adb connect "$TV" >/dev/null || true ;; esac
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach $TV. Check the TV is on and debugging is allowed." >&2; exit 1; }
adb -s "$TV" shell pm path com.knz.telebisyon >/dev/null 2>&1 || { echo "The home screen isn't installed. Run install.sh first." >&2; exit 1; }

PKG=com.knz.telebisyon
case "$MODE" in
  off)
    adb -s "$TV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW deny
    adb -s "$TV" shell appops set "$PKG" GET_USAGE_STATS deny
    adb -s "$TV" shell cmd notification disallow_listener "$PKG/$PKG.MediaListener" >/dev/null 2>&1 || true
    # Restarting the home screen stops the message service; without the permission it stays off.
    adb -s "$TV" shell am force-stop "$PKG"
    adb -s "$TV" shell input keyevent KEYCODE_HOME
    echo "Messages are off." ;;
  on)
    # "Display over other apps": TVs have no screen to turn this on, so adb does it.
    adb -s "$TV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
    case "$(adb -s "$TV" shell appops get "$PKG" SYSTEM_ALERT_WINDOW | tr -d '\r')" in
      *allow*) ;;
      *) echo "This TV wouldn't allow showing messages over other apps." >&2; exit 1 ;;
    esac
    # For control (/status, /media): which app is in front, and what's playing. Optional: without
    # them, /status leaves those out and /media sends media keys instead.
    adb -s "$TV" shell appops set "$PKG" GET_USAGE_STATS allow || echo "Couldn't give usage access: /status won't say which app is open."
    adb -s "$TV" shell cmd notification allow_listener "$PKG/$PKG.MediaListener" >/dev/null 2>&1 \
      || echo "Couldn't turn on media access: /status won't say what's playing."
    # Starting the home screen starts the message service with it. Restart it, since one already
    # running wouldn't start it.
    adb -s "$TV" shell am force-stop "$PKG"
    adb -s "$TV" shell am start -n "$PKG/.MainActivity" >/dev/null
    ip=${TV%%:*}
    echo "Messages are on. Try: sh send.sh $ip \"Hello\" \"It works\""
    echo "Anyone on your network can send them; turn off with: sh messages.sh $ip off" ;;
  *) echo "Usage: sh messages.sh <TV_IP> [on|off]" >&2; exit 1 ;;
esac
