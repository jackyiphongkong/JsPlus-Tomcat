#!/bin/sh
# ============================================================================
# diff-check.sh - POSIX counterpart of diff-check.ps1: compare this Working
# tree's src/ java files against the Githubrepos mirror after every build and
# append a "found diffed" note to both changelogs (English + Traditional
# Chinese). Read-only: nothing is copied or committed.
# ----------------------------------------------------------------------------
# Usage (called from build.xml):
#   sh '<jr.root>/test/diff-check.sh' '<jr.root>/src' '<jr.git.repo>/src' \
#      '<jr.changelog>' '<jr.changelog.zh>'
# ============================================================================
WORKING_SRC="$1"
MIRROR_SRC="$2"
CHANGELOG="$3"
CHANGELOG_ZH="$4"

if [ ! -d "$WORKING_SRC" ]; then
    echo "diff-check: Working src missing: $WORKING_SRC"
    exit 0
fi
if [ ! -d "$MIRROR_SRC" ]; then
    echo "diff-check: mirror src missing: $MIRROR_SRC (skipped)"
    exit 0
fi

TS=$(date '+%Y-%m-%d %H:%M:%S')
TMP=$(mktemp)
TMPZH=$(mktemp)
trap 'rm -f "$TMP" "$TMPZH"' EXIT

diffed=0
# Compare every .java under Working src (relative path) against the mirror.
while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    mirror="$MIRROR_SRC/$rel"
    if [ ! -f "$mirror" ]; then
        diffed=$((diffed + 1))
        echo "#   - $rel" >> "$TMP"
        echo "#   - $rel" >> "$TMPZH"
        continue
    fi
    h1=$(sha256sum "$WORKING_SRC/$rel" | cut -d' ' -f1)
    h2=$(sha256sum "$mirror" | cut -d' ' -f1)
    if [ "$h1" != "$h2" ]; then
        diffed=$((diffed + 1))
        echo "#   - $rel" >> "$TMP"
        echo "#   - $rel" >> "$TMPZH"
    fi
done <<EOF
$(cd "$WORKING_SRC" && find . -name '*.java' -type f | sed 's|^\./||' | sort)
EOF

if [ "$diffed" -eq 0 ]; then
    echo "diff-check: 0 java files differ."
    exit 0
fi

{
    echo "# Human-readable diff note (auto-detected)"
    echo "# $TS - $diffed java file(s) differ from the Githubrepos mirror (not yet synced):"
    cat "$TMP"
} >> "$CHANGELOG"

{
    echo "# 人話差異說明（自動偵測）"
    echo "# $TS - $diffed 個 Java 檔案與 Githubrepos 鏡像不同（未同步）："
    cat "$TMPZH"
} >> "$CHANGELOG_ZH"

echo "diff-check: found diffed: $diffed java file(s) -> changelog note appended."
