#!/usr/bin/env bash
# Combine depth, contours, land and fishing zones into one offline wa.pmtiles.
set -euo pipefail

WORK=${WORK:-build}
OUT=${OUT:-out}
mkdir -p "$OUT"

ATTR='Depth © Geoscience Australia (AusBathyTopo, incorporating AHO data), WA Department of Transport (CC BY 4.0). Coast © OpenStreetMap contributors. Zones: WA DPIRD, DBCA (CC BY-NC 4.0), Parks Australia.'

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

echo ">> Tiling the detailed areas (Two Rocks to Preston Beach, Kalbarri to Horrocks) (z4-14)"
for layer in depth land water contour source; do
  cat "$WORK"/metro_$layer.geojsonl "$WORK"/kalbarri_$layer.geojsonl > "$WORK/detail_$layer.geojsonl"
done
tippecanoe -o "$WORK/metro.pmtiles" --force \
  -Z4 -z14 \
  -L depth:"$WORK/detail_depth.geojsonl" \
  -L land:"$WORK/detail_land.geojsonl" \
  -L water:"$WORK/detail_water.geojsonl" \
  -L contour:"$WORK/detail_contour.geojsonl" \
  -L source:"$WORK/detail_source.geojsonl" \
  --detect-shared-borders \
  --no-feature-limit --no-tile-size-limit \
  --no-tiny-polygon-reduction-at-maximum-zoom \
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
  "$WORK/base.pmtiles" "$WORK/metro.pmtiles" "$WORK/zones.pmtiles"

ls -la "$OUT"
