#!/bin/sh
# Sets the TV's clock right and keeps it right: turns on network time (Android then corrects the
# time by itself whenever the TV is online), and sets the time zone to where the internet
# connection says you are. Only needs adb and curl.
#
#   sh clock.sh <TV_IP>
#   sh clock.sh <TV_IP> Europe/London     a time zone of your choice instead
set -e

TARGET="$1"; ZONE="$2"
[ -n "$TARGET" ] || { echo "Usage: sh clock.sh <TV_IP> [time zone]" >&2; exit 1; }
case "$TARGET" in
  *.*.*.*:*) TV=$TARGET ;;
  *.*.*.*)   TV=$TARGET:5555 ;;
  *)         TV=$TARGET ;;
esac
case "$TV" in *:*) adb connect "$TV" >/dev/null || true ;; esac
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach $TV." >&2; exit 1; }
sh_tv() { adb -s "$TV" shell "$@" | tr -d '\r'; }

# Network time. TVs have no mobile network to take the time zone from, so that part is below.
sh_tv settings put global auto_time 1
echo "Network time is on: the TV sets its own clock whenever it's online."

if [ -z "$ZONE" ]; then
  # Where this internet connection is, which is where the TV is (same Wi-Fi).
  ZONE=$(curl -fsS -m 10 "http://ip-api.com/line/?fields=timezone" 2>/dev/null || true)
fi
case "$ZONE" in
  */*) ;;
  *) echo "Couldn't tell your time zone, so it's left as it is ($(sh_tv getprop persist.sys.timezone))."; exit 0 ;;
esac

now=$(sh_tv getprop persist.sys.timezone)
if [ "$now" = "$ZONE" ]; then
  echo "Time zone is already $ZONE."
  exit 0
fi
# setTimeZone on the alarm service; adb's shell may call it on Android 11 and earlier.
if [ "$(sh_tv getprop ro.build.version.sdk)" -le 30 ]; then
  sh_tv service call alarm 3 s16 "$ZONE" >/dev/null || true
fi
if [ "$(sh_tv getprop persist.sys.timezone)" = "$ZONE" ]; then
  echo "Time zone set to $ZONE (was $now)."
else
  echo "Couldn't set the time zone to $ZONE here. On the TV: Settings > Device Preferences > Date & time > Set time zone."
fi
