#!/bin/bash
# Offers an APK to the installed RetroFire apps (src/com/retroarch/retrofire/Updater.java): uploads it
# as release retrofire-N of the public repository mefistofelix/firetv-apps and points retrofire.json
# in that repository at it (the same scheme as FireBro).
# usage: bash publish-update.sh N VERSION_CODE VERSION_NAME path/to/RetroFire.apk
# Needs gh with write access to mefistofelix/firetv-apps (GH_TOKEN in CI).
set -e
N="$1"; CODE="$2"; NAME="$3"; APK="$4"
[ -n "$N" ] && [ -n "$CODE" ] && [ -n "$NAME" ] && [ -f "$APK" ] || { echo "usage: publish-update.sh N VERSION_CODE VERSION_NAME apk"; exit 2; }
REPO=mefistofelix/firetv-apps
FILE="RetroFire-$N.apk"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")
if ! gh release view "retrofire-$N" -R "$REPO" > /dev/null 2>&1; then
  gh release create "retrofire-$N" "$APK#$FILE" -R "$REPO" --title "RetroFire $NAME" \
    --notes "RetroFire build $N ($NAME, versionCode $CODE)" --latest=false > /dev/null
else
  cp "$APK" "/tmp/$FILE" && gh release upload "retrofire-$N" "/tmp/$FILE" -R "$REPO" --clobber
fi
URL="https://github.com/$REPO/releases/download/retrofire-$N/$FILE"
JSON=$(printf '{"versionCode": %s, "versionName": "%s", "url": "%s", "sha256": "%s", "size": %s}\n' "$CODE" "$NAME" "$URL" "$SHA" "$SIZE")
OLD=$(gh api "repos/$REPO/contents/retrofire.json" -q .sha 2>/dev/null || true)
gh api -X PUT "repos/$REPO/contents/retrofire.json" -f message="RetroFire build $N" \
  -f content="$(printf '%s' "$JSON" | base64 -w0)" ${OLD:+-f sha="$OLD"} > /dev/null
echo "update offered: $URL"
