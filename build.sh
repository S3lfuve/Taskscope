#!/data/data/com.termux/files/usr/bin/sh
set -eu
umask 077
cd "$(dirname "$0")"
build=.build
mkdir -p "$build/classes" "$build/generated" "$build/payload/lib/arm64-v8a"
find "$build/classes" -type f -name '*.class' -delete
if [ ! -s "$build/android.jar" ]; then
    curl -fLsS --retry 2 https://dl.google.com/android/repository/platform-35_r02.zip -o "$build/platform.zip"
    unzip -jo "$build/platform.zip" '*/android.jar' -d "$build"
fi
platform="$build/android.jar"
clang -Wall -Wextra -Werror -O2 -fPIE -pie -Wl,-z,max-page-size=16384 \
    src/probe.c -o "$build/payload/lib/arm64-v8a/libtaskscope_probe.so"
aapt package -f -m -J "$build/generated" -M AndroidManifest.xml -S res -I "$platform" -F "$build/unsigned.apk"
javac --release 8 -encoding UTF-8 -classpath "$platform" -d "$build/classes" \
    src/*.java "$build/generated/local/taskscope/manager/R.java"
jar cf "$build/classes.jar" -C "$build/classes" .
d8 --min-api 26 --lib "$platform" --output "$build/payload" "$build/classes.jar"
jar uf "$build/unsigned.apk" -C "$build/payload" classes.dex -C "$build/payload" lib
keystore=${TASKSCOPE_KEYSTORE:-$build/local-signing.p12}
set --
if [ -n "${TASKSCOPE_KEYSTORE_PASSWORD:-}" ]; then
    export TASKSCOPE_KEYSTORE_PASSWORD
    set -- -storepass:env TASKSCOPE_KEYSTORE_PASSWORD
fi
if [ ! -f "$keystore" ]; then
    keytool -genkeypair -keystore "$keystore" -alias taskscope "$@" \
        -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Taskscope Local'
fi
signing_input=stdin
if [ -n "${TASKSCOPE_KEYSTORE_PASSWORD:-}" ]; then signing_input=env:TASKSCOPE_KEYSTORE_PASSWORD; fi
apksigner sign --ks "$keystore" --ks-key-alias taskscope \
    --ks-pass "$signing_input" --v4-signing-enabled false --out Taskscope.apk "$build/unsigned.apk"
apksigner verify --verbose Taskscope.apk

badging=$(aapt dump badging Taskscope.apk)
version_code=$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionCode='\([0-9][0-9]*\)'.*/\1/p")
version_name=$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionName='\([0-9A-Za-z.-][0-9A-Za-z.-]*\)'.*/\1/p")
test -n "$version_code" && test -n "$version_name" || exit 1
printf '{\n  "packageName": "local.taskscope.manager",\n  "versionCode": %s,\n  "versionName": "%s"\n}\n' \
    "$version_code" "$version_name" > "$build/release.json"
mv "$build/release.json" release.json
