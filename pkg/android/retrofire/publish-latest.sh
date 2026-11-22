#!/bin/bash
# Offers the newest CI build to the installed RetroFire apps, like FireBro's remote-build.sh does after
# its build: downloads RetroFire.apk and version.txt from the release build-retrofire of this
# repository and runs publish-update.sh with the local gh (no token in CI).
# usage (Git Bash, after the "RetroFire APK" workflow of the retrofire branch has finished):
#   bash pkg/android/retrofire/publish-latest.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT
gh release download build-retrofire -R mefistofelix/RetroArch -p RetroFire.apk -p version.txt -D "$S"
CODE=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" "$S/version.txt")
NAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" "$S/version.txt")
N=$(printf '%s' "$NAME" | sed -n 's/.*-rf\([0-9][0-9]*\)$/\1/p')
[ -n "$CODE" ] && [ -n "$N" ] || { echo "cannot read the version:"; cat "$S/version.txt"; exit 1; }
if gh release view "retrofire-$N" -R mefistofelix/firetv-apps > /dev/null 2>&1; then
  echo "retrofire-$N is already offered"; exit 0
fi
bash "$HERE/publish-update.sh" "$N" "$CODE" "$NAME" "$S/RetroFire.apk"
