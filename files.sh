#!/bin/sh
# Turns the /files page on or off: the TV's storage and USB drives from a browser, like an FTP app,
# plus installing an APK sent from there (the TV still asks, and someone presses Install on it).
# Needs the web page on (messages.sh). Set a PIN too (pin.sh), or anyone on your Wi-Fi can use it.
#
#   sh files.sh <TV_IP>        turns it on
#   sh files.sh <TV_IP> off    turns it off
set -e

TARGET="$1"; MODE="${2:-on}"
[ -n "$TARGET" ] || { echo "Usage: sh files.sh <TV_IP> [on|off]" >&2; exit 1; }
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
SDK=$(adb -s "$TV" shell getprop ro.build.version.sdk | tr -d '\r')
ip=${TV%%:*}

case "$MODE" in
  off)
    if [ "$SDK" -ge 30 ]; then
      adb -s "$TV" shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE default
    elif [ "$SDK" -ge 23 ]; then
      adb -s "$TV" shell pm revoke "$PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
      adb -s "$TV" shell pm revoke "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
    fi
    [ "$SDK" -ge 26 ] && adb -s "$TV" shell appops set "$PKG" REQUEST_INSTALL_PACKAGES default
    adb -s "$TV" shell am force-stop "$PKG"
    adb -s "$TV" shell am start -n "$PKG/.MainActivity" --es files off --es stamp "files-$(date +%s)" >/dev/null
    echo "Files are off." ;;
  on)
    # TVs have no screen for "All files access", so adb gives it (the storage permissions before Android 11).
    if [ "$SDK" -ge 30 ]; then
      adb -s "$TV" shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
    elif [ "$SDK" -ge 23 ]; then
      adb -s "$TV" shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE
      adb -s "$TV" shell pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE
    fi
    # Lets the Install button open Android's install prompt. Optional.
    [ "$SDK" -ge 26 ] && { adb -s "$TV" shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow \
      || echo "Couldn't allow installing apps: the Install button won't work."; }
    # Restarted, so the web server starts with the new permissions.
    adb -s "$TV" shell am force-stop "$PKG"
    adb -s "$TV" shell am start -n "$PKG/.MainActivity" --es files on --es stamp "files-$(date +%s)" >/dev/null
    echo "Files are on: http://$ip:7979/files"
    if ! adb -s "$TV" shell appops get "$PKG" SYSTEM_ALERT_WINDOW | grep -q allow; then
      echo "The web page itself is off. Turn it on with: sh messages.sh $ip"
    fi
    echo "Anyone on your Wi-Fi can use it unless you set a PIN: sh pin.sh $ip 1234" ;;
  *) echo "Usage: sh files.sh <TV_IP> [on|off]" >&2; exit 1 ;;
esac
