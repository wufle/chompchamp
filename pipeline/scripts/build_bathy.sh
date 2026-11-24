#!/usr/bin/env bash
# Build depth bands, depth contours and land polygons for WA from
# Geoscience Australia's AusBathyTopo (Australia) 250m grid.
#
# Outputs (in $WORK): depth.geojsonl  contour.geojsonl  land.geojsonl
set -euo pipefail

WORK=${WORK:-build}
# xmin ymin xmax ymax (WGS84). WA coast from the NT border to the SA border.
BBOX=${BBOX:-"112.0 -36.0 129.5 -13.0"}
URL=${BATHY_URL:-"https://files.ausseabed.gov.au/survey/AusBathyTopo%20(Australia)%202024%20250m.zip"}

mkdir -p "$WORK/raw"
ZIP="$WORK/ausbathytopo.zip"

if [[ ! -s "$ZIP" ]]; then
  echo ">> Downloading AusBathyTopo 250m (~2.6 GB)"
  curl -fL --retry 5 --retry-delay 10 -o "$ZIP.part" "$URL"
  mv "$ZIP.part" "$ZIP"
fi

echo ">> Zip contents"
unzip -l "$ZIP" | sed -n '1,60p'

# Pick the main depth grid (skip type-identifier / derived grids).
GRID=$(unzip -Z1 "$ZIP" | grep -iE '\.(tif|tiff)$' | grep -viE 'tid|type|hillshade|slope|aspect|uncert|std' | head -1 || true)
if [[ -z "$GRID" ]]; then
  echo "!! No GeoTIFF depth grid found in zip; listing above shows what is there" >&2
  exit 1
fi
echo ">> Using grid: $GRID"
unzip -o -j "$ZIP" "$GRID" -d "$WORK/raw" >/dev/null
SRC="$WORK/raw/$(basename "$GRID")"
gdalinfo "$SRC" | sed -n '1,40p'

echo ">> Clipping to WA: $BBOX"
# shellcheck disable=SC2086
gdalwarp -overwrite -t_srs EPSG:4326 -te $BBOX -r bilinear \
  -co COMPRESS=DEFLATE -co TILED=YES "$SRC" "$WORK/wa_dem.tif"

echo ">> Smoothing: 2x cubic-spline upsample (cosmetic: removes 250 m stair-steps, adds no real detail)"
gdal_translate -q -outsize 200% 200% -r cubicspline -co COMPRESS=DEFLATE -co TILED=YES -co BIGTIFF=IF_SAFER \
  "$WORK/wa_dem.tif" "$WORK/wa_dem_smooth.tif"
DEM="$WORK/wa_dem_smooth.tif"

echo ">> Depth contour lines"
# Elevations are positive-up, so depths are negative values.
LINES="-5000 -4000 -3000 -2000 -1500 -1000 -750 -500 -400 -300 -250 -200 -150 -100 -80 -60 -50 -40 -30 -25 -20 -15 -10 -5 -2"
rm -f "$WORK/contour_lines.gpkg"
# shellcheck disable=SC2086
gdal_contour -q -a elev -fl $LINES "$DEM" "$WORK/contour_lines.gpkg"
ogr2ogr -f GeoJSONSeq "$WORK/contour.geojsonl" "$WORK/contour_lines.gpkg" -dialect sqlite \
  -sql "SELECT geom, CAST(ROUND(-elev) AS INTEGER) AS depth FROM contour"

echo ">> Depth band polygons + land"
# Fine bands so the app can estimate depth under the boat (dmin–dmax).
BANDS="-12000 -1000 -500 -300 -200 -150 -100 -80 -60 -50 -40 -30 -25 -20 -15 -10 -8 -5 -3 -2 0 10000"
rm -f "$WORK/bands.gpkg"
# shellcheck disable=SC2086
gdal_contour -q -p -amin emin -amax emax -fl $BANDS "$DEM" "$WORK/bands.gpkg"
ogr2ogr -f GeoJSONSeq "$WORK/depth.geojsonl" "$WORK/bands.gpkg" -dialect sqlite \
  -sql "SELECT geom, CAST(ROUND(-emax) AS INTEGER) AS dmin, CAST(ROUND(-emin) AS INTEGER) AS dmax FROM contour WHERE emax <= 0"
ogr2ogr -f GeoJSONSeq "$WORK/land.geojsonl" "$WORK/bands.gpkg" -dialect sqlite \
  -sql "SELECT geom FROM contour WHERE emin >= 0"

ls -la "$WORK"/*.geojsonl
