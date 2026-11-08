#!/bin/sh
# Builds out/nekplik.apk. Needs Java 17 and the Android SDK (platform 34, build-tools 34.0.0), the same
# as Telebisyon. Plain Java, no libraries, no Gradle.
#   sh build.sh
#
# Signing: with Telebisyon's key.jks when this folder sits inside Telebisyon, else its own key.jks (made
# on the first build). Use the same key as Telebisyon for its web page to list the library; set
# KEYSTORE and KEYSTORE_PASS to choose one.
set -e
cd "$(dirname "$0")"

[ -n "$JAVA_HOME" ] || for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64; do [ -d "$j" ] && JAVA_HOME=$j && break; done
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
# The first SDK that has what's needed: ANDROID_HOME can point at one without it.
has_sdk() { [ -f "$1/platforms/android-34/android.jar" ] && [ -d "$1/build-tools/34.0.0" ]; }
SDK=
for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/homebrew/share/android-commandlinetools \
  /usr/local/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
  [ -n "$d" ] && has_sdk "$d" && SDK=$d && break
done
BT=$SDK/build-tools/34.0.0; JAR=$SDK/platforms/android-34/android.jar
[ -n "$SDK" ] || { echo "Android SDK not found. Set ANDROID_HOME, and install platforms;android-34 and build-tools;34.0.0." >&2; exit 1; }

rm -rf out; mkdir -p out/classes out/dex
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 34 out/res.zip
# shellcheck disable=SC2046 # javac and d8 want one argument per file, so the list is split on purpose
javac -nowarn -Xlint:-options -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" \
  -d out/classes $(find src -name "*.java")
# shellcheck disable=SC2046 # same here
"$BT/d8" --min-api 21 --release --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
cp out/base.apk out/unsigned.apk; (cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk

if [ -z "$KEYSTORE" ] && [ -f ../key.jks ] && [ -f ../build.sh ]; then KEYSTORE=../key.jks; fi
KS=${KEYSTORE:-key.jks}; PASS=${KEYSTORE_PASS:-tvhome123}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias nekplik \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=nekplik" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --out out/nekplik.apk out/aligned.apk
echo "Built out/nekplik.apk"
