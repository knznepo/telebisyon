#!/bin/sh
# Sets up a TV: builds the home screen, installs it, turns on the TV's web page (messages,
# wallpapers, the home screen copy and Home Assistant control), sets the clock to keep itself right
# (clock.sh), copies any pictures in ./wallpapers, and can add NEKPLIK. Safe to run again after
# changes: it just updates what's there.
#
#   sh setup.sh <TV_IP>
#
# In a terminal it asks first: install everything, or choose what to install and what to remove.
# With any of the settings below, or when it isn't in a terminal, it doesn't ask:
#
#   sh setup.sh <TV_IP> "com.netflix.ninja,com.google.android.youtube.tv"   your own app list
#   ALL=1 sh setup.sh <TV_IP>       everything, NEKPLIK included, without asking
#   WEB=0 sh setup.sh <TV_IP>       without the web page (just the home screen)
#   PIN=123456 FILES=1 sh setup.sh <TV_IP>   a PIN for the web page (pin.sh), and the /files page on (files.sh)
#   REMOTE=1 sh setup.sh <TV_IP>    the /remote page's arrows on (remote.sh)
#   NEKPLIK=1 sh setup.sh <TV_IP>   NEKPLIK too (nekplik/nekplik.sh)
#
# Needs adb. Java 17 and the Android SDK (see build.sh) only to build it yourself.
set -e
cd "$(dirname "$0")"

TARGET="$1"
[ -n "$TARGET" ] || { echo "Usage: sh setup.sh <TV_IP> [\"app1,app2,...\"]" >&2; exit 1; }
shift
IP=${TARGET%%:*}
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

PKG=com.knz.telebisyon
sh_tv() { adb -s "$TV" shell "$@" </dev/null 2>/dev/null | tr -d '\r'; }
SDK=$(sh_tv getprop ro.build.version.sdk)
has() { sh_tv pm path "$1" | grep -q '^package:'; }

# What's on the TV now: 1 on, 0 off.
now_home=0 now_web=0 now_files=0 now_remote=0 now_nekplik=0
if has "$PKG"; then
  now_home=1
  sh_tv appops get "$PKG" SYSTEM_ALERT_WINDOW | grep -q allow && now_web=1
  if [ "${SDK:-0}" -ge 30 ]; then
    sh_tv appops get "$PKG" MANAGE_EXTERNAL_STORAGE | grep -q allow && now_files=1
  else
    sh_tv dumpsys package "$PKG" | grep -q 'WRITE_EXTERNAL_STORAGE: granted=true' && now_files=1
  fi
  sh_tv settings get secure enabled_accessibility_services | tr ':' '\n' | grep -qx "$PKG/$PKG.Remote" && now_remote=1
fi
has com.knz.nekplik && now_nekplik=1

# What to do with each: on, off (remove or turn off), or keep (leave as it is).
# Without the menu, nothing is removed: the settings above only add.
want_home=on want_web=on want_pin=keep want_files=keep want_remote=keep want_clock=on want_walls=on want_nekplik=keep
if [ "$WEB" = 0 ]; then want_web=keep; fi
if [ -n "$PIN" ]; then want_pin=on; fi
if [ "$FILES" = 1 ]; then want_files=on; fi
if [ "$REMOTE" = 1 ]; then want_remote=on; fi
if [ "$NEKPLIK" = 1 ]; then want_nekplik=on; fi
if [ "$ALL" = 1 ]; then
  want_web=on want_files=on want_remote=on want_nekplik=on
fi

# The menu: only in a terminal, and only when nothing above already says what to do.
ask=1
[ -t 0 ] && [ -t 1 ] || ask=
for v in "$ALL" "$WEB" "$PIN" "$FILES" "$REMOTE" "$NEKPLIK" "$1"; do if [ -n "$v" ]; then ask=; fi; done

yes_no() { # yes_no "question" default(y|n)
  printf '%s ' "$1"; read -r _a || _a=
  case "${_a:-$2}" in [Yy]*) return 0 ;; *) return 1 ;; esac
}
ask_pin() {
  while :; do
    printf 'PIN for the phone pages (4 or more digits or letters; Enter for none): '
    read -r PIN || PIN=
    case "$PIN" in
      "") want_pin=keep; return ;;
      *[!A-Za-z0-9]*) echo "  Use only digits and letters." ;;
      ????*) want_pin=on; return ;;
      *) echo "  Use 4 or more." ;;
    esac
  done
}

FEATURES="home web pin files remote clock walls nekplik"
label() {
  case "$1" in
    home)    echo "Home screen       Telebisyon itself; everything but the clock and NEKPLIK needs it" ;;
    web)     echo "Phone pages       the TV's web page: messages, wallpapers, Home Assistant" ;;
    pin)     echo "PIN               the phone pages ask for a PIN" ;;
    files)   echo "Files page        the TV's storage and USB drives from a browser" ;;
    remote)  echo "Remote            arrows, OK, Back and Home on the /remote page" ;;
    clock)   echo "Clock             network time and your time zone" ;;
    walls)   echo "Wallpapers        copies the pictures in ./wallpapers" ;;
    nekplik) echo "NEKPLIK           movies and series from USB drives and computers" ;;
  esac
}
now_of() {
  case "$1" in
    home) [ $now_home = 1 ] && echo "on the TV now" ;;
    web) [ $now_web = 1 ] && echo "on now" ;;
    files) [ $now_files = 1 ] && echo "on now" ;;
    remote) [ $now_remote = 1 ] && echo "on now" ;;
    nekplik) [ $now_nekplik = 1 ] && echo "on the TV now" ;;
  esac
}
get() { eval "echo \$sel_$1"; }
put() { eval "sel_$1=\$2"; }

if [ -n "$ask" ]; then
  echo
  echo "Setting up the TV at $IP"
  echo
  echo "  1  Install everything (the home screen, phone pages, Files page, remote, clock, wallpapers, NEKPLIK)"
  echo "  2  Choose what to install or remove"
  echo "  q  Quit"
  echo
  printf 'Choose 1, 2 or q [1]: '; read -r choice || choice=q
  case "${choice:-1}" in
    1)
      want_web=on want_files=on want_remote=on want_nekplik=on
      ask_pin ;;
    2)
      # Ticked: what's on the TV now; on a TV without Telebisyon, everything.
      for f in $FEATURES; do put "$f" 1; done
      if [ $now_home = 1 ]; then
        sel_web=$now_web sel_files=$now_files sel_remote=$now_remote sel_nekplik=$now_nekplik sel_pin=0
      fi
      while :; do
        echo
        i=0
        for f in $FEATURES; do
          i=$((i + 1))
          if [ "$(get "$f")" = 1 ]; then box="[x]"; else box="[ ]"; fi
          n=$(now_of "$f" || true); if [ -n "$n" ]; then n="  ($n)"; fi
          echo "  $box $i  $(label "$f")$n"
        done
        echo
        echo "Type numbers to tick or untick them (like: 3 5), a for all, n for none,"
        printf 'Enter when it looks right, q to quit: '
        read -r line || line=q
        case "$line" in
          "") break ;;
          q|Q) echo "Nothing changed."; exit 0 ;;
          a|A) for f in $FEATURES; do put "$f" 1; done ;;
          n|N) for f in $FEATURES; do put "$f" 0; done ;;
          *)
            for k in $line; do
              i=0
              for f in $FEATURES; do
                i=$((i + 1))
                [ "$k" = "$i" ] && { if [ "$(get "$f")" = 1 ]; then put "$f" 0; else put "$f" 1; fi; }
              done
            done ;;
        esac
      done
      # Unticked means removed if it's there; the clock and wallpapers are just left as they are.
      tick() { if [ "$(get "$1")" = 1 ]; then echo on; else echo off; fi; }
      want_home=$(tick home) want_web=$(tick web) want_files=$(tick files) want_remote=$(tick remote) want_nekplik=$(tick nekplik)
      if [ "$sel_clock" = 1 ]; then want_clock=on; else want_clock=keep; fi
      if [ "$sel_walls" = 1 ]; then want_walls=on; else want_walls=keep; fi
      if [ "$sel_pin" = 1 ]; then ask_pin; else want_pin=keep; fi
      # A PIN only goes away when the phone pages stay and the PIN was unticked on purpose.
      if [ "$sel_pin" = 0 ] && [ $now_home = 1 ] && [ "$want_home" = on ] && [ "$want_web" = on ]; then
        yes_no "Take the PIN off the phone pages, if there is one? [y/N]" n && want_pin=off
      fi ;;
    *) echo "Nothing changed."; exit 0 ;;
  esac

  # Without the home screen, its features can't be added; removing it removes them with it.
  if [ "$want_home" = off ]; then
    for f in web pin files remote walls; do eval "want_$f=keep"; done
  fi

  echo
  echo "This will:"
  for f in home web pin files remote clock walls nekplik; do
    w=$(eval "echo \$want_$f"); n=$(label "$f" | sed 's/  .*//')
    case "$f:$w" in
      home:off)    [ $now_home = 1 ] && echo "  - remove the home screen (and turn the TV's own back on)" ;;
      nekplik:off) [ $now_nekplik = 1 ] && echo "  - remove NEKPLIK" ;;
      web:off)     [ $now_web = 1 ] && echo "  - turn off the phone pages" ;;
      files:off)   [ $now_files = 1 ] && echo "  - turn off the Files page" ;;
      remote:off)  [ $now_remote = 1 ] && echo "  - turn off the remote" ;;
      pin:off)     echo "  - take the PIN off" ;;
      home:on)     if [ $now_home = 1 ]; then echo "  + Home screen (update)"; else echo "  + Home screen"; fi ;;
      nekplik:on)  if [ $now_nekplik = 1 ]; then echo "  + NEKPLIK (update)"; else echo "  + NEKPLIK"; fi ;;
      *:on)        echo "  + $n" ;;
    esac
  done
  echo
  yes_no "Go ahead? [Y/n]" y || { echo "Nothing changed."; exit 0; }
fi

step=0
section() { step=$((step + 1)); echo; echo "== $step. $1"; }

# Removing comes first: NEKPLIK, then the features, then the home screen itself.
if [ "$want_nekplik" = off ] && [ $now_nekplik = 1 ]; then
  section "Removing NEKPLIK"
  sh nekplik/nekplik.sh "$TV" off
fi
if [ "$want_home" = off ]; then
  if [ $now_home = 1 ]; then
    section "Removing the home screen"
    sh undo.sh "$TV"
  fi
else
  if [ "$want_remote" = off ] && [ $now_remote = 1 ]; then section "Turning off the remote"; sh remote.sh "$TV" off; fi
  if [ "$want_files" = off ] && [ $now_files = 1 ]; then section "Turning off the Files page"; sh files.sh "$TV" off; fi
  if [ "$want_web" = off ] && [ $now_web = 1 ]; then section "Turning off the phone pages"; sh messages.sh "$TV" off; fi
  if [ "$want_pin" = off ]; then section "Taking the PIN off"; sh pin.sh "$TV" off; fi

  section "Building the home screen"
  # Always fresh, so changes made here are what gets installed. Without Java and the Android SDK,
  # install.sh downloads the latest release instead.
  if ! sh build.sh; then
    echo "Can't build on this computer, so the latest release will be downloaded."
    rm -f out/tvhome.apk; export BUILD=0
  fi
  section "Installing it on the TV"
  sh install.sh "$TV" "$@"

  if [ "$want_web" = on ]; then
    section "Turning on the TV's web page"
    sh messages.sh "$TV" on
  fi
fi

if [ "$want_clock" = on ]; then
  section "Clock: network time and time zone"
  sh clock.sh "$TV" || echo "Couldn't set the clock; run sh clock.sh $IP later."
fi

if [ "$want_walls" = on ] && [ "$want_home" = on ]; then
  section "Wallpaper pictures"
  pictures=
  for f in wallpapers/*; do
    case "$(printf '%s' "$f" | tr 'A-Z' 'a-z')" in *.jpg|*.jpeg|*.png|*.webp|*.bmp|*.gif) pictures=1; break ;; esac
  done
  if [ -n "$pictures" ]; then
    sh wallpapers.sh "$TV"
  else
    echo "None in ./wallpapers. You can add some later from the web page, or put them there and run this again."
  fi
fi

if [ "$want_home" = on ]; then
  if [ "$want_pin" = on ]; then section "PIN for the phone pages"; sh pin.sh "$TV" "$PIN"; fi
  if [ "$want_files" = on ]; then section "Files page"; sh files.sh "$TV" on; fi
  if [ "$want_remote" = on ]; then section "Remote"; sh remote.sh "$TV" on; fi
fi

if [ "$want_nekplik" = on ]; then
  section "NEKPLIK"
  # Fresh too, like the home screen; else nekplik.sh downloads the latest release.
  sh nekplik/build.sh || { echo "Can't build NEKPLIK here, so the latest release will be downloaded."; rm -f nekplik/out/nekplik.apk; }
  sh nekplik/nekplik.sh "$TV"
fi

echo
echo "Done."
if [ "$want_home" = on ]; then
  if [ "$want_web" = on ] || [ $now_web = 1 ]; then echo "Phone pages: http://$IP:7979"; fi
  if [ -z "$ask" ]; then
    if [ -z "$PIN" ]; then echo "No PIN on the phone pages: set one with PIN=123456, or later: sh pin.sh $IP 123456"; fi
    if [ "$want_files" != on ] && [ $now_files = 0 ]; then echo "Files page off: turn it on with FILES=1, or later: sh files.sh $IP"; fi
    if [ "$want_remote" != on ] && [ $now_remote = 0 ]; then echo "Remote's arrows off: turn them on with REMOTE=1, or later: sh remote.sh $IP"; fi
  fi
fi
echo "Run sh setup.sh $IP again any time to add or remove things."
