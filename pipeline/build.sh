#!/usr/bin/env bash
# Build the offline WA chart: pipeline/out/wa.pmtiles
# Needs: python3, gdal (gdalwarp, gdal_contour, ogr2ogr), unzip, curl, tippecanoe + tile-join (2.17+)
set -euo pipefail
cd "$(dirname "$0")"
export WORK=${WORK:-build} OUT=${OUT:-out}
mkdir -p "$WORK" "$OUT"

python3 -I scripts/fetch_zones.py "$WORK/zones.geojson"
bash scripts/build_bathy.sh
bash scripts/build_tiles.sh
