#!/usr/bin/env python3
"""Build an offline raster pack of WA Department of Transport bathymetry imagery.

DoT publishes colour-ramped, hill-shaded 5 m bathymetry (LiDAR / multibeam / laser
composite surveys) for ~30 nearshore areas as an ArcGIS MapServer, licensed
CC BY 3.0 AU. This script renders those images into Web-Mercator tiles and writes
a WebP PMTiles archive the app can use offline.

Usage: build_relief.py OUTPUT.pmtiles [--maxzoom 15] [--minzoom 10] [--workers 6]
Needs: pip install pillow pmtiles
"""
import argparse
import io
import math
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from PIL import Image
from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import Writer

SERVICE = "https://public-services.slip.wa.gov.au/public/rest/services/Landgate_Public_Maps/WA_Bathymetry/MapServer"
COVERAGE_LAYER = 1      # "Composite Surfaces - Multibeam LIDAR Laser (DOT-022)" extents
IMAGE_GROUP = 2         # "Bathymetry Images" group layer
HALF = 20037508.342789244
UA = {"User-Agent": "chompchamp-pipeline/0.2"}
ATTRIBUTION = "Bathymetry imagery © WA Department of Transport (CC BY 3.0 AU). NOT FOR NAVIGATION."


def fetch(url, params, retries=5):
    full = f"{url}?{urllib.parse.urlencode(params)}"
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(urllib.request.Request(full, headers=UA), timeout=120) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            if attempt == retries - 1:
                raise
            print(f"  retry {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(2 * (attempt + 1))


def coverage_rings():
    import json
    data = json.loads(fetch(f"{SERVICE}/{COVERAGE_LAYER}/query", {
        "where": "1=1", "outFields": "location", "returnGeometry": "true",
        "outSR": "3857", "f": "json",
    }))
    rings = []
    for f in data["features"]:
        for ring in f["geometry"].get("rings", []):
            rings.append(ring)
    print(f"Coverage: {len(data['features'])} survey areas, {len(rings)} rings", file=sys.stderr)
    return rings


def tile_bounds(z, x, y):
    size = 2 * HALF / (1 << z)
    minx = -HALF + x * size
    maxy = HALF - y * size
    return minx, maxy - size, minx + size, maxy


def point_in_ring(px, py, ring):
    inside = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        if (y1 > py) != (y2 > py) and px < (x2 - x1) * (py - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def tiles_for_rings(rings, z):
    size = 2 * HALF / (1 << z)
    tiles = set()
    for ring in rings:
        xs = [p[0] for p in ring]
        ys = [p[1] for p in ring]
        x0, x1 = int((min(xs) + HALF) // size), int((max(xs) + HALF) // size)
        y0, y1 = int((HALF - max(ys)) // size), int((HALF - min(ys)) // size)
        for tx in range(x0, x1 + 1):
            for ty in range(y0, y1 + 1):
                a, b, c, d = tile_bounds(z, tx, ty)
                hit = any(a <= px <= c and b <= py <= d for px, py in ring[::max(1, len(ring) // 400)])
                if not hit:
                    pts = [(a, b), (a, d), (c, b), (c, d), ((a + c) / 2, (b + d) / 2)]
                    hit = any(point_in_ring(px, py, ring) for px, py in pts)
                if hit:
                    tiles.add((tx, ty))
    return tiles


def render(z, x, y, px):
    a, b, c, d = tile_bounds(z, x, y)
    data = fetch(f"{SERVICE}/export", {
        "bbox": f"{a},{b},{c},{d}", "bboxSR": 3857, "imageSR": 3857,
        "size": f"{px},{px}", "format": "png32", "transparent": "true",
        "layers": f"show:{IMAGE_GROUP}", "f": "image",
    })
    return Image.open(io.BytesIO(data)).convert("RGBA")


def is_empty(img):
    return img.getchannel("A").getbbox() is None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("output")
    ap.add_argument("--minzoom", type=int, default=10)
    ap.add_argument("--maxzoom", type=int, default=15)
    ap.add_argument("--workers", type=int, default=6)
    args = ap.parse_args()

    zmax = args.maxzoom
    # Render one 512px image per (zmax-1) tile and split it into four zmax tiles:
    # 4x fewer requests to the government server.
    zfetch = zmax - 1
    rings = coverage_rings()
    fetch_tiles = sorted(tiles_for_rings(rings, zfetch))
    print(f"Rendering {len(fetch_tiles)} z{zfetch} images (512 px) ...", file=sys.stderr)

    levels = {zmax: {}}
    done = 0

    def job(t):
        return t, render(zfetch, t[0], t[1], 512)

    with ThreadPoolExecutor(args.workers) as pool:
        for (tx, ty), img in pool.map(job, fetch_tiles):
            done += 1
            if done % 200 == 0:
                print(f"  {done}/{len(fetch_tiles)}", file=sys.stderr)
            if is_empty(img):
                continue
            for dx in (0, 1):
                for dy in (0, 1):
                    child = img.crop((dx * 256, dy * 256, dx * 256 + 256, dy * 256 + 256))
                    if not is_empty(child):
                        levels[zmax][(tx * 2 + dx, ty * 2 + dy)] = child

    # Build lower zooms by merging children.
    for z in range(zmax - 1, args.minzoom - 1, -1):
        parents = {}
        for (x, y), img in levels[z + 1].items():
            canvas = parents.setdefault((x // 2, y // 2), Image.new("RGBA", (512, 512)))
            canvas.paste(img, ((x % 2) * 256, (y % 2) * 256))
        levels[z] = {k: v.resize((256, 256), Image.LANCZOS) for k, v in parents.items()}
        print(f"  z{z}: {len(levels[z])} tiles", file=sys.stderr)

    entries = []
    for z, tiles in levels.items():
        for (x, y), img in tiles.items():
            buf = io.BytesIO()
            img.save(buf, "WEBP", quality=82, method=4)
            entries.append((zxy_to_tileid(z, x, y), buf.getvalue()))
    entries.sort()

    # Bounds from the zmax tiles.
    def lon(xm): return xm / HALF * 180
    def lat(ym): return math.degrees(2 * math.atan(math.exp(ym / 6378137)) - math.pi / 2)
    xs = [k[0] for k in levels[zmax]]
    ys = [k[1] for k in levels[zmax]]
    minx, _, _, maxy = tile_bounds(zmax, min(xs), min(ys))
    _, miny, maxx, _ = tile_bounds(zmax, max(xs), max(ys))

    with open(args.output, "wb") as fh:
        w = Writer(fh)
        for tid, data in entries:
            w.write_tile(tid, data)
        w.finalize(
            {
                "tile_type": TileType.WEBP,
                "tile_compression": Compression.NONE,
                "min_zoom": args.minzoom,
                "max_zoom": zmax,
                "min_lon_e7": int(lon(minx) * 1e7),
                "min_lat_e7": int(lat(miny) * 1e7),
                "max_lon_e7": int(lon(maxx) * 1e7),
                "max_lat_e7": int(lat(maxy) * 1e7),
                "center_zoom": 12,
                "center_lon_e7": int(115.6 * 1e7),
                "center_lat_e7": int(-32.0 * 1e7),
            },
            {"name": "ChompChamp WA reef relief", "attribution": ATTRIBUTION, "format": "webp"},
        )
    total = sum(len(d) for _, d in entries)
    print(f"Wrote {len(entries)} tiles, {total / 1e6:.1f} MB -> {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
