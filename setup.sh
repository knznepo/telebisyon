#!/bin/sh
# Everything in one go: builds the home screen, installs it on the TV, turns on the TV's web page
# (messages, wallpapers, the home screen copy and Home Assistant control), sets the clock to keep
# itself right (clock.sh), and copies any pictures in ./wallpapers. Safe to run again after changes: it just updates what's there.
#
#   sh setup.sh <TV_IP>
#   sh setup.sh <TV_IP> "com.netflix.ninja,com.google.android.youtube.tv"   your own app list
#   WEB=0 sh setup.sh <TV_IP>      without the web page (just the home screen)
#   PIN=123456 FILES=1 sh setup.sh <TV_IP>   a PIN for the web page (pin.sh), and the /files page on (files.sh)
#   REMOTE=1 sh setup.sh <TV_IP>   the /remote page's arrows on (remote.sh)
#
# Needs adb. Java 17 and the Android SDK (see build.sh) only to build it yourself.
set -e
cd "$(dirname "$0")"

TV="$1"
[ -n "$TV" ] || { echo "Usage: sh setup.sh <TV_IP> [\"app1,app2,...\"]" >&2; exit 1; }
shift
IP=${TV%%:*}

echo "== 1/6 Building the home screen"
# Always fresh, so changes made here are what gets installed. Without Java and the Android SDK,
# install.sh downloads the latest release instead.
if ! sh build.sh; then
  echo "Can't build on this computer, so the latest release will be downloaded."
  rm -f out/tvhome.apk; export BUILD=0
fi

echo "== 2/6 Installing it on the TV"
sh install.sh "$TV" "$@"

if [ "$WEB" = 0 ]; then
  echo "== 3/6 Web page: skipped (WEB=0)"
else
  echo "== 3/6 Turning on the TV's web page"
  sh messages.sh "$TV" on
fi

echo "== 4/6 Clock: network time and time zone"
sh clock.sh "$TV" || echo "Couldn't set the clock; run sh clock.sh $IP later."

echo "== 5/6 Wallpaper pictures"
pictures=
for f in wallpapers/*; do
  case "$(printf '%s' "$f" | tr 'A-Z' 'a-z')" in *.jpg|*.jpeg|*.png|*.webp|*.bmp|*.gif) pictures=1; break ;; esac
done
if [ -n "$pictures" ]; then
  sh wallpapers.sh "$TV"
else
  echo "None in ./wallpapers. You can add some later from the web page, or put them there and run this again."
fi

if [ "$WEB" = 0 ]; then
  echo "== 6/6 PIN, files and remote: skipped (WEB=0)"
else
  echo "== 6/6 PIN, files and remote"
  if [ -n "$PIN" ]; then sh pin.sh "$TV" "$PIN"; else echo "No PIN (set one with PIN=123456, or later: sh pin.sh $IP 123456)."; fi
  if [ "$FILES" = 1 ]; then sh files.sh "$TV" on; else echo "Files page off (turn it on with FILES=1, or later: sh files.sh $IP)."; fi
  if [ "$REMOTE" = 1 ]; then sh remote.sh "$TV" on; else echo "Remote's arrows off (turn them on with REMOTE=1, or later: sh remote.sh $IP)."; fi
fi

echo
echo "All set."
if [ "$WEB" != 0 ]; then
  echo "On a phone or computer on the same Wi-Fi, open:"
  echo "  http://$IP:7979             the TV's home screen; tap an app to open it on the TV"
  echo "  http://$IP:7979/message     send a message to the TV"
  echo "  http://$IP:7979/wallpapers  pictures, shuffle and the photo frame"
  if [ "$FILES" = 1 ]; then echo "  http://$IP:7979/files       the TV's files and USB drives"; fi
  echo "  http://$IP:7979/remote      the TV's remote"
  echo "  http://$IP:7979/call        call the TV"
fi
