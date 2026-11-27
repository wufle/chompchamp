#!/usr/bin/env bash
# Build the offline WA chart: pipeline/out/wa.pmtiles
# Needs: python3 with pipeline/requirements.txt, gdal (gdalwarp, gdal_contour, ogr2ogr), unzip, curl, tippecanoe + tile-join (2.17+)
set -euo pipefail
cd "$(dirname "$0")"
export WORK=${WORK:-build} OUT=${OUT:-out}
mkdir -p "$WORK" "$OUT"

python3 -I scripts/fetch_zones.py "$WORK/zones.geojson"
bash scripts/build_bathy.sh
# Detailed areas, replacing the 250 m chart there: Two Rocks to Preston Beach (5 m surveys, river and estuary
# soundings), Kalbarri to Horrocks (soundings, LiDAR and multibeam) and Cliff Head to Seven Mile Beach around
# Dongara (LiDAR and soundings). See REGIONS in build_metro.py.
PY=${PY:-python3}
"$PY" scripts/build_metro.py "$WORK/wa_dem_smooth.tif" "$WORK" metro
"$PY" scripts/build_metro.py "$WORK/wa_dem_smooth.tif" "$WORK" kalbarri
"$PY" scripts/build_metro.py "$WORK/wa_dem_smooth.tif" "$WORK" dongara
"$PY" scripts/clip_metro.py "$WORK"
bash scripts/build_tiles.sh

# Compare the finished chart with the source grid; fails the build if they disagree.
"$PY" scripts/check_points.py "$WORK/wa_dem.tif" "$WORK/check_points.json"
node scripts/check_chart.mjs "$OUT/wa.pmtiles" "$WORK/check_points.json"
