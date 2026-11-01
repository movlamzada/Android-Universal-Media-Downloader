#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
cd "$ROOT"
"$ROOT/gradlew" --no-daemon :app:assembleDebug
cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$ROOT/UniversalMediaDownloader.apk"
echo "APK: $ROOT/UniversalMediaDownloader.apk"
