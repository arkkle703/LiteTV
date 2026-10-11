#!/usr/bin/env bash
#
# Builds update.json — the manifest that every release publishes under a fixed name.
#
# The app fetches it through
#   https://github.com/<owner>/<repo>/releases/latest/download/update.json
# which is why the name never changes: the APK assets carry the version in their
# names, only this file keeps a stable URL. That form also avoids the GitHub API,
# whose anonymous limit is 60 requests per hour per IP.
#
# Usage:
#   scripts/make-update-json.sh <versionName> <versionCode> <apkDir> <releaseTag> [notesFile]
#
#   versionName   e.g. 1.5.0        (must match APP_VERSION_NAME used for the build)
#   versionCode   e.g. 7            (must match APP_VERSION_CODE; the app compares this)
#   apkDir        directory holding LiteTV-v<versionName>-<abi>.apk
#   releaseTag    e.g. v1.5.0       (the tag the assets are published under)
#   notesFile     optional; bullet lines become the in-app "what's new" list
#
# Example:
#   ./gradlew :app:assembleRelease
#   cd app/build/outputs/apk/release
#   for abi in arm64-v8a armeabi-v7a x86_64 x86; do
#       mv "app-$abi-release.apk" "LiteTV-v1.5.0-$abi.apk"
#   done
#   ../../../scripts/make-update-json.sh 1.5.0 7 . v1.5.0 notes.md
#
# Note: a manifest must describe the version its own APKs carry. Publishing a
# manifest that announces a versionCode newer than the APKs it points at makes
# every client download a file that fails verification.

set -euo pipefail

ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
DEFAULT_SLUG="vibe4free/LiteTV"
MAX_CHANGELOG_LINES=8
MAX_CHANGELOG_LINE_CHARS=90

usage() {
    sed -n '3,30p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

[ $# -ge 4 ] || usage

VERSION_NAME="$1"
VERSION_CODE="$2"
APK_DIR="$3"
RELEASE_TAG="$4"
NOTES_FILE="${5:-}"

if ! [[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || [ "$VERSION_CODE" -le 0 ]; then
    echo "error: versionCode must be a positive integer, got '$VERSION_CODE'" >&2
    exit 1
fi
[ -d "$APK_DIR" ] || { echo "error: no such directory: $APK_DIR" >&2; exit 1; }
command -v python3 >/dev/null || { echo "error: python3 is required" >&2; exit 1; }

SLUG="$(git config --get remote.origin.url 2>/dev/null \
    | sed -E 's#(git@|https://)github\.com[:/]##; s#\.git$##')"
[ -n "$SLUG" ] || SLUG="$DEFAULT_SLUG"

file_size() {
    stat -c %s "$1" 2>/dev/null || stat -f %z "$1"
}

file_sha256() {
    if command -v sha256sum >/dev/null; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# Collect the APKs. All four ABIs are expected: a manifest that silently ships
# three would strand whoever has the fourth.
APK_JSON=""
for abi in "${ABIS[@]}"; do
    apk="$APK_DIR/LiteTV-v$VERSION_NAME-$abi.apk"
    if [ ! -f "$apk" ]; then
        echo "error: missing $apk" >&2
        exit 1
    fi
    size="$(file_size "$apk")"
    sha="$(file_sha256 "$apk")"
    url="https://github.com/$SLUG/releases/download/$RELEASE_TAG/LiteTV-v$VERSION_NAME-$abi.apk"
    APK_JSON+="$abi $url $sha $size"$'\n'
    printf '  %-12s %9s bytes  %s\n' "$abi" "$size" "${sha:0:16}…" >&2
done

OUT="$APK_DIR/update.json"

VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" SLUG="$SLUG" \
RELEASE_TAG="$RELEASE_TAG" NOTES_FILE="$NOTES_FILE" OUT="$OUT" \
APK_JSON="$APK_JSON" MAX_LINES="$MAX_CHANGELOG_LINES" MAX_CHARS="$MAX_CHANGELOG_LINE_CHARS" \
python3 - <<'PY'
import json, os, re

def changelog(path, max_lines, max_chars):
    """Bullet lines from the release notes, cleaned up for a television dialog."""
    if not path or not os.path.isfile(path):
        return []
    lines = []
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            # Only top-level bullets: the sub-bullets under them are detail that does not fit a
            # television dialog, and the headline items are what a viewer needs to decide.
            if not raw.startswith(("- ", "* ")):
                continue
            text = raw[2:].strip()
            # Markdown emphasis and links read badly on a TV; keep the words.
            text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
            text = re.sub(r"`(.+?)`", r"\1", text)
            text = re.sub(r"\[(.+?)\]\(.+?\)", r"\1", text)
            if not text:
                continue
            if len(text) > max_chars:
                text = text[:max_chars - 1].rstrip() + "…"
            lines.append(text)
            if len(lines) >= max_lines:
                break
    return lines

variants = {}
for row in os.environ["APK_JSON"].strip().splitlines():
    abi, url, sha, size = row.split(" ")
    variants[abi] = {"url": url, "sha256": sha, "size": int(size)}

manifest = {
    "versionCode": int(os.environ["VERSION_CODE"]),
    "versionName": os.environ["VERSION_NAME"],
    "minVersionCode": 0,
    "changelog": changelog(os.environ["NOTES_FILE"],
                           int(os.environ["MAX_LINES"]), int(os.environ["MAX_CHARS"])),
    "releaseUrl": "https://github.com/%s/releases/tag/%s" % (
        os.environ["SLUG"], os.environ["RELEASE_TAG"]),
    "variants": variants,
}

with open(os.environ["OUT"], "w", encoding="utf-8") as handle:
    json.dump(manifest, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
PY

echo "wrote $OUT" >&2
