#!/bin/sh
# Builds "FLIXNET Share.app" (a Mac app: double-click, choose the movies folder, and it shares until
# "Stop sharing"), with share.py and FLIXNET's icon inside. Needs a Mac (osacompile, sips, iconutil).
#   sh make-mac-app.sh        makes ./FLIXNET Share.app (drag it to Applications if you like)
set -e
cd "$(dirname "$0")"
APP="FLIXNET Share.app"
rm -rf "$APP"
osacompile -o "$APP" "FLIXNET Share.applescript"
cp ../share.py "$APP/Contents/Resources/share.py"

# FLIXNET's red F as the app's icon (icon.png, 1024x1024, from art.py), in every size macOS wants.
ICONSET=$(mktemp -d)/icon.iconset
mkdir -p "$ICONSET"
for s in 16 32 128 256 512; do
  sips -z $s $s icon.png --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
  sips -z $((s * 2)) $((s * 2)) icon.png --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/applet.icns"
rm -rf "$(dirname "$ICONSET")"
# osacompile also puts its own icon in Assets.car, which newer macOS shows first: take it out.
rm -f "$APP/Contents/Resources/Assets.car"
plutil -remove CFBundleIconName "$APP/Contents/Info.plist" 2>/dev/null || true
# Changing the app undid osacompile's signature: sign it again, for this computer (ad hoc).
codesign --force --deep --sign - "$APP" 2>/dev/null
touch "$APP" # so Finder shows the new icon
echo "Built $APP"
