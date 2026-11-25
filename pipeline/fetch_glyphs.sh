#!/usr/bin/env bash
# Bundles the label font (Noto Sans Regular, SIL OFL 1.1) as MapLibre glyph ranges, so map labels
# work offline from the first launch. Ranges 0-12287 cover Latin, Greek, Cyrillic, punctuation,
# arrows and symbols; CJK is drawn by Android's own fonts.
set -euo pipefail
FONT="Noto Sans Regular"
OUT="$(dirname "$0")/../android/app/src/main/assets/glyphs/$FONT"
mkdir -p "$OUT"
for start in $(seq 0 256 12032); do
  range="$start-$((start + 255))"
  curl -sSf -A "chompchamp-pipeline/0.1" -o "$OUT/$range.pbf" \
    "https://tiles.openfreemap.org/fonts/Noto%20Sans%20Regular/$range.pbf"
done
du -sh "$OUT"
