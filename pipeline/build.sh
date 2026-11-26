#!/usr/bin/env bash
# Build the offline WA chart: pipeline/out/wa.pmtiles
# Needs: python3 with pipeline/requirements.txt, gdal (gdalwarp, gdal_contour, ogr2ogr), unzip, curl, tippecanoe + tile-join (2.17+)
set -euo pipefail
cd "$(dirname "$0")"
export WORK=${WORK:-build} OUT=${OUT:-out}
mkdir -p "$WORK" "$OUT"

python3 -I scripts/fetch_zones.py "$WORK/zones.geojson"
bash scripts/build_bathy.sh
# Two Rocks to Preston Beach in detail (5 m surveys, river and estuary soundings), replacing the 250 m chart there.
PY=${PY:-python3}
"$PY" scripts/build_metro.py "$WORK/wa_dem_smooth.tif" "$WORK"
"$PY" scripts/clip_metro.py "$WORK"
bash scripts/build_tiles.sh

# Compare the finished chart with the source grid; fails the build if they disagree.
"$PY" scripts/check_points.py "$WORK/wa_dem.tif" "$WORK/check_points.json"
node scripts/check_chart.mjs "$OUT/wa.pmtiles" "$WORK/check_points.json"
