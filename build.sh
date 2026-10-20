#!/usr/bin/env bash
#
# Homestead - build, sign and (optionally) install. No Gradle: aapt2/javac/d8/apksigner
# only. Run from this directory.
#
#   ./build.sh                 build + sign
#   ./build.sh install         also adb install -r to $ADB_TARGET (e.g. 192.168.1.20:5555)
#
# Signing key (not in this repository):
#   export HOMESTEAD_KEYSTORE=/path/to/release.keystore
#   export HOMESTEAD_KEYPASS=...
# If HOMESTEAD_KEYPASS is unset, the password is read from KEY-INFO.txt in
# $HOMESTEAD_KEYDIR (default ~/.homestead-keys).

set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}}"
BT="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/36.1.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}"
KEYDIR="${HOMESTEAD_KEYDIR:-$HOME/.homestead-keys}"
KEYSTORE="${HOMESTEAD_KEYSTORE:-$KEYDIR/homestead-release.keystore}"
KEYALIAS="${HOMESTEAD_KEYALIAS:-homestead}"
ADB="${ADB:-$SDK/platform-tools/adb.exe}"
ADB_TARGET="${ADB_TARGET:-}"      # ip:port of your TV, for ./build.sh install

MIN_SDK=22          # Fire OS 5 (Android 5.1). TYPE_ACCESSIBILITY_OVERLAY exists from 22.
TARGET_SDK=28       # Fire OS 7 (Android 9). Not raised: 29+ adds stricter background-
                    # activity-start rules, and the Home redirect starts from the background.

if [ -z "${HOMESTEAD_KEYPASS:-}" ]; then
  if [ -f "$KEYDIR/KEY-INFO.txt" ]; then
    HOMESTEAD_KEYPASS=$(grep -m1 '^Password:' "$KEYDIR/KEY-INFO.txt" | sed 's/^Password: *//' | awk '{print $1}')
  else
    echo "No signing key. Set HOMESTEAD_KEYPASS, or see the README (Building)." >&2
    exit 1
  fi
fi

win() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

echo "==> clean"
rm -rf classes dex gen app-unsigned.apk app-with-dex.apk app-aligned.apk compiled.flata
mkdir -p classes dex res/drawable-nodpi

echo "==> artwork"
java tools/MakeArt.java res/drawable-nodpi >/dev/null

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir res -o compiled.flata

echo "==> aapt2 link"
"$BT/aapt2" link -o app-unsigned.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  -R compiled.flata \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --auto-add-overlay

echo "==> javac"
find src -name '*.java' > sources.txt
javac --release 11 -nowarn -encoding UTF-8 \
  -classpath "$(win "$ANDROID_JAR")" -d classes @sources.txt
rm -f sources.txt

echo "==> d8"
find classes -name '*.class' > classes.txt
"$BT/d8.bat" --lib "$(win "$ANDROID_JAR")" --min-api "$MIN_SDK" --release \
  --output "$(win "$PWD/dex")" @classes.txt
rm -f classes.txt

echo "==> package dex"
cp app-unsigned.apk app-with-dex.apk
( cd dex && "$BT/aapt" add "../app-with-dex.apk" classes.dex >/dev/null )

echo "==> zipalign"
"$BT/zipalign" -f -p 4 app-with-dex.apk app-aligned.apk

echo "==> sign"
"$BT/apksigner.bat" sign \
  --ks "$(win "$KEYSTORE")" --ks-key-alias "$KEYALIAS" \
  --ks-pass "pass:$HOMESTEAD_KEYPASS" --key-pass "pass:$HOMESTEAD_KEYPASS" \
  --min-sdk-version "$MIN_SDK" \
  --out homestead.apk app-aligned.apk
"$BT/apksigner.bat" verify --min-sdk-version "$MIN_SDK" homestead.apk

VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
echo "==> homestead.apk  v$VN ($VC)  $(stat -c%s homestead.apk) bytes, $(unzip -l homestead.apk 2>/dev/null | grep -c '\.dex\|\.png\|\.arsc' || echo '?') packaged entries"

if [ "${1:-}" = "install" ]; then
  if [ -z "$ADB_TARGET" ]; then
    echo "Set ADB_TARGET=<tv-ip>:5555 for install." >&2
    exit 1
  fi
  echo "==> install to $ADB_TARGET"
  "$ADB" -s "$ADB_TARGET" install -r -d homestead.apk
fi
