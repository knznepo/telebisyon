#!/bin/sh
# Installs NEKPLIK on a TV, or takes it off. Only needs adb (Mac: brew install android-platform-tools).
#
#   sh nekplik.sh <TV_IP>          installs or updates it, and lets it read USB drives and hard disks
#   sh nekplik.sh <TV_IP> off      uninstalls it (the drives and what's on them are left alone)
#
# Uses out/nekplik.apk if you built it (build.sh), else builds it if this computer can (Java 17 and the
# Android SDK), else downloads the latest release ($NEKPLIK_URL to use another).
set -e
cd "$(dirname "$0")"

TARGET="$1"; MODE="${2:-on}"
[ -n "$TARGET" ] || { echo "Usage: sh nekplik.sh <TV_IP> [off]" >&2; exit 1; }
command -v adb >/dev/null 2>&1 || {
  echo "adb is missing. Install it first:" >&2
  echo "  Mac:     brew install android-platform-tools" >&2
  echo "  Windows: https://developer.android.com/tools/releases/platform-tools" >&2
  exit 1; }

case "$TARGET" in
  *.*.*.*:*) TV=$TARGET ;;
  *.*.*.*)   TV=$TARGET:5555 ;;
  *)         TV=$TARGET ;;
esac
case "$TV" in *:*) adb connect "$TV" >/dev/null || true ;; esac
state=$(adb -s "$TV" get-state 2>&1 || true)
case "$state" in
  device) ;;
  *unauthorized*) echo "Your TV is asking \"Allow debugging?\". Press Allow (tick \"Always allow\"), then run this again." >&2; exit 1 ;;
  *) echo "Can't reach $TV. Check the TV is on, on the same Wi-Fi, and has USB/network debugging turned on." >&2; exit 1 ;;
esac
PKG=com.knz.nekplik

if [ "$MODE" = off ]; then
  adb -s "$TV" uninstall "$PKG" >/dev/null && echo "NEKPLIK is off the TV." || echo "NEKPLIK wasn't installed."
  exit 0
fi

APK=out/nekplik.apk
URL=${NEKPLIK_URL:-https://github.com/knznepo/telebisyon/releases/latest/download/nekplik.apk}
if [ ! -f "$APK" ]; then
  if ! { echo "Building NEKPLIK first…"; sh build.sh; }; then
    echo "Downloading the latest NEKPLIK release…"
    mkdir -p out
    curl -fL --progress-bar -o "$APK" "$URL" || {
      rm -f "$APK"
      echo "Couldn't download $URL." >&2
      echo "Check the computer is online. If it says 404, no release is published yet: build it here instead (see build.sh)." >&2
      exit 1; }
  fi
fi

echo "Installing NEKPLIK…"
if ! out=$(adb -s "$TV" install -r "$APK" 2>&1); then
  case "$out" in
    *UPDATE_INCOMPATIBLE*) echo "Replacing an older copy signed with a different key…"
                           adb -s "$TV" uninstall "$PKG" >/dev/null; adb -s "$TV" install "$APK" ;;
    *INSUFFICIENT_STORAGE*) echo "The TV is out of space. Free some up (Settings > Storage), then run this again." >&2; exit 1 ;;
    *) echo "$out" >&2; exit 1 ;;
  esac
fi

# TVs have no screen to ask for reading drives, so adb gives it: "All files access" from Android 11
# (info.json and .srt files aren't media, so the media permission isn't enough), the storage
# permission before.
SDK=$(adb -s "$TV" shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$SDK" -ge 30 ]; then
  adb -s "$TV" shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
elif [ "$SDK" -ge 23 ]; then
  adb -s "$TV" shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE
fi
# On Telebisyon's home screen, NEKPLIK goes at the end of the apps row (the rest stays as it is).
if adb -s "$TV" shell pm path com.knz.telebisyon >/dev/null 2>&1; then
  adb -s "$TV" shell am start -n com.knz.telebisyon/.MainActivity --es addapp "$PKG" --es stamp "nekplik-$(date +%s)" >/dev/null
  echo "NEKPLIK is on the TV, at the end of the apps row on Telebisyon's home screen."
else
  echo "NEKPLIK is on the TV. Open it from the TV's apps."
fi
echo "Put movies on a USB drive or hard disk: Movies/<name>/ and Series/<name>/ (see docs/library.md)."
