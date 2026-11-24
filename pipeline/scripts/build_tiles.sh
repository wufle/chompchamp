#!/usr/bin/env bash
# Combine depth, contours, land and fishing zones into one offline wa.pmtiles.
set -euo pipefail

WORK=${WORK:-build}
OUT=${OUT:-out}
mkdir -p "$OUT"

ATTR='Depth © Geoscience Australia (AusBathyTopo). Zones: WA DPIRD, DBCA (CC BY-SA 4.0), Parks Australia.'

echo ">> Tiling depth / contours / land (z4-14)"
tippecanoe -o "$WORK/base.pmtiles" --force \
  -Z4 -z14 \
  -L depth:"$WORK/depth.geojsonl" \
  -L land:"$WORK/land.geojsonl" \
  -L contour:"$WORK/contour.geojsonl" \
  --detect-shared-borders \
  --coalesce-densest-as-needed \
  --extend-zooms-if-still-dropping \
  --simplification=2 \
  --attribution="$ATTR"

echo ">> Tiling fishing zones (z4-14, never dropped)"
tippecanoe -o "$WORK/zones.pmtiles" --force \
  -Z4 -z14 \
  -L zones:"$WORK/zones.geojson" \
  --no-feature-limit --no-tile-size-limit \
  --no-tiny-polygon-reduction \
  --simplification=2 \
  --attribution="$ATTR"

echo ">> Joining"
tile-join -o "$OUT/wa.pmtiles" --force --no-tile-size-limit \
  --name="ChompChamp WA chart" --attribution="$ATTR" \
  "$WORK/base.pmtiles" "$WORK/zones.pmtiles"

ls -la "$OUT"
