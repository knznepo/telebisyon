#!/bin/sh
# Shows a message on the TV (turn messages on first with messages.sh). Only needs curl.
#
#   sh send.sh <TV_IP> "Title" "Message"
#   sh send.sh <TV_IP> "" "Just a message"
#   DURATION=10 POSITION=4 sh send.sh <TV_IP> "Dinner" "It's ready"
#   IMAGE=https://example.com/camera.jpg sh send.sh <TV_IP> "Front door" ""
#   SOUND=1 sh send.sh <TV_IP> "Door" "Someone's here"   plays a chime too
#   BOX=0 IMAGE_SIZE=50 IMAGE=https://example.com/logo.png sh send.sh <TV_IP> "" ""
#                                              no box, the picture half the screen wide
#   sh send.sh <TV_IP> --cancel                takes the message away
#
# DURATION in seconds (default 30). POSITION: 0 top right (default), 1 top left, 2 bottom right,
# 3 bottom left, 4 centre. BOX=0 leaves out the box behind it, so a transparent PNG floats on the
# picture. IMAGE_SIZE is the picture's width in percent of the screen (5 to 100).
set -e

TV="$1"
[ -n "$TV" ] || { echo "Usage: sh send.sh <TV_IP> \"Title\" \"Message\"" >&2; exit 1; }
TV=${TV%%:*}

if [ "$2" = "--cancel" ]; then
  curl -fsS -X DELETE "http://$TV:7979/cancel" >/dev/null && echo "Cleared."
  exit
fi

# Makes a JSON string: backslashes, quotes and new lines escaped.
json() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | awk 'NR > 1 { printf "\\n" } { printf "%s", $0 }'; }

body="{\"title\":\"$(json "$2")\",\"message\":\"$(json "$3")\",\"duration\":${DURATION:-30},\"position\":${POSITION:-0}"
[ "$SOUND" = 1 ] && body="$body,\"sound\":true"
[ "$BOX" = 0 ] && body="$body,\"box\":false"
[ -n "$IMAGE_SIZE" ] && body="$body,\"imageSize\":$IMAGE_SIZE"
[ -n "$IMAGE" ] && body="$body,\"media\":{\"image\":{\"uri\":\"$(json "$IMAGE")\",\"width\":480}}"
body="$body}"

if curl -fsS -m 20 -X POST "http://$TV:7979/notify" -H "Content-Type: application/json" -d "$body" >/dev/null; then
  echo "Sent."
else
  echo "Couldn't reach the TV. Is it on, and were messages turned on with messages.sh?" >&2
  exit 1
fi
