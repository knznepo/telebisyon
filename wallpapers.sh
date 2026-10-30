#!/bin/sh
# Copies your own pictures to the TV as wallpapers. Only needs adb, and the home screen installed.
#
#   sh wallpapers.sh <TV_IP>                 copies the pictures in ./wallpapers
#   sh wallpapers.sh <TV_IP> ~/Pictures/tv   copies the pictures in another folder
#   CLEAR=1 sh wallpapers.sh <TV_IP>         removes the ones on the TV first
#
# Then on the TV: Settings tile > Wallpaper. The pictures come after the colours, by file name.
# JPEG, PNG, WebP, BMP or GIF. A 1920x1080 (or 3840x2160) landscape picture fits best.
set -e

TARGET="$1"; DIR="${2:-$(dirname "$0")/wallpapers}"
[ -n "$TARGET" ] || { echo "Usage: sh wallpapers.sh <TV_IP> [folder]" >&2; exit 1; }
[ -d "$DIR" ] || { echo "No folder $DIR. Make it and put your pictures in it, or name another folder." >&2; exit 1; }
command -v adb >/dev/null 2>&1 || { echo "adb is missing. Mac: brew install android-platform-tools" >&2; exit 1; }

case "$TARGET" in
  *.*.*.*:*) TV=$TARGET ;;
  *.*.*.*)   TV=$TARGET:5555 ;;
  *)         TV=$TARGET ;;
esac
case "$TV" in *:*) adb connect "$TV" >/dev/null || true ;; esac
[ "$(adb -s "$TV" get-state 2>/dev/null)" = device ] || { echo "Can't reach $TV. Check the TV is on and debugging is allowed." >&2; exit 1; }

# The home screen's own folder: it can read it without asking for any permission.
DEST=/sdcard/Android/data/com.knz.telebisyon/files/wallpapers
adb -s "$TV" shell mkdir -p "$DEST"
[ "$CLEAR" = 1 ] && adb -s "$TV" shell "rm -f '$DEST'/*"

n=0
for f in "$DIR"/*; do
  case "$(printf '%s' "$f" | tr 'A-Z' 'a-z')" in
    *.jpg|*.jpeg|*.png|*.webp|*.bmp|*.gif) ;;
    *) continue ;;
  esac
  adb -s "$TV" push "$f" "$DEST/" >/dev/null
  echo "Copied $(basename "$f")"
  n=$((n + 1))
done
[ "$n" -gt 0 ] || { echo "No pictures found in $DIR." >&2; exit 1; }
echo "Done. On the TV: Settings tile > Wallpaper, then pick one."
