#!/usr/bin/env bash
# Build the offline WA chart: pipeline/out/wa.pmtiles
# Needs: python3 with pipeline/requirements.txt, gdal (gdalwarp, gdal_contour, ogr2ogr), unzip, curl, tippecanoe + tile-join (2.17+)
set -euo pipefail
cd "$(dirname "$0")"
export WORK=${WORK:-build} OUT=${OUT:-out}
mkdir -p "$WORK" "$OUT"

python3 -I scripts/fetch_zones.py "$WORK/zones.geojson"
bash scripts/build_bathy.sh
# Perth metro in detail (5 m surveys, river soundings), replacing the 250 m chart there.
PY=${PY:-python3}
"$PY" scripts/build_metro.py "$WORK/wa_dem_smooth.tif" "$WORK"
"$PY" scripts/clip_metro.py "$WORK"
bash scripts/build_tiles.sh
