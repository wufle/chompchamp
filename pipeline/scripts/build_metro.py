#!/usr/bin/env python3
"""High-detail Perth metro chart: coast from Two Rocks to Warnbro, Rottnest, Cockburn
Sound and the Swan/Canning rivers. Replaces the 250 m chart inside METRO_BBOX.

Sources:
  * WA Department of Transport 5 m composite grids (DOT-022) and point soundings
    (DOT-021), CC BY 4.0. Heights are AHD (close to mean sea level).
  * OpenStreetMap coastline and river/harbour outlines, ODbL 1.0
  * AusBathyTopo 250 m (already clipped to WA) where there is no survey, sea only

Depth is never made up for rivers and harbours: water with no soundings nearby is drawn as
water but gets no depth band, so the app reports no depth there.

Usage: build_metro.py BASE_DEM.tif WORKDIR
Outputs: metro_depth / metro_contour / metro_land / metro_water .geojsonl
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import zipfile

import contourpy
import numpy as np
import rasterio
from rasterio.features import rasterize
from rasterio.transform import from_origin
from rasterio.warp import Resampling, reproject, transform as warp_xy
from scipy import ndimage
from scipy.interpolate import LinearNDInterpolator
import shapely
from shapely.geometry import LineString, MultiPolygon, Polygon, box, mapping
from shapely.ops import linemerge, polygonize, unary_union

METRO_BBOX = (115.40, -32.45, 116.02, -31.42)
PAD = 0.004           # build slightly larger, then clip to the box
RES = 0.0001          # about 10 m
S3 = "https://s3-ap-southeast-2.amazonaws.com/transport.wa/Bathymetry"
RASTERS = ["TwoRocks", "OceanReef", "Rottnest", "Warnbro", "CockburnSound", "Perth"]
POINTS = ["SC", "CS", "FR", "WB"]
MAX_GAP_M = 80        # no depth further than this from a real sounding (points only)
OVERPASS = ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]
UA = {"User-Agent": "chompchamp-pipeline/0.1"}

# dmin/dmax bands and contour levels (metres). FINE contours only appear when zoomed in.
BANDS = [0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 80, 100, 150, 200, 300, 500, 1000]
LINES = [2, 5, 10, 15, 20, 25, 30, 40, 50, 60, 80, 100, 150, 200, 250, 300, 400, 500, 750]
FINE = [1, 3, 4, 6, 8, 12, 18]


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def download(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=300) as r, \
                    open(path + ".part", "wb") as fh:
                while chunk := r.read(1 << 20):
                    fh.write(chunk)
            os.replace(path + ".part", path)
            return path
        except Exception as e:  # noqa: BLE001
            if attempt == 3:
                raise
            log("retry", url, e)
            time.sleep(10 * (attempt + 1))


def fetch_osm(path):
    if os.path.exists(path):
        return json.load(open(path))["elements"]
    w, s, e, n = METRO_BBOX
    m = 0.1
    b = f"{s - m},{w - m},{n + m},{e + m}"
    q = f"""[out:json][timeout:180];
(way["natural"="coastline"]({b});
 way["natural"="water"]["water"~"^(river|harbour|canal|bay)$"]({b});
 relation["natural"="water"]["water"~"^(river|harbour|canal|bay)$"]({b});
 way["waterway"="dock"]({b});
);out geom;"""
    for attempt in range(6):
        url = OVERPASS[attempt % len(OVERPASS)]
        try:
            req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": q}).encode(), headers=UA)
            with urllib.request.urlopen(req, timeout=240) as r:
                d = json.load(r)
            if not any(x["tags"].get("natural") == "coastline" for x in d["elements"]):
                raise RuntimeError("no coastline in Overpass reply")
            json.dump(d, open(path, "w"))
            return d["elements"]
        except Exception as ex:  # noqa: BLE001
            if attempt == 5:
                raise
            log("Overpass retry", ex)
            time.sleep(20)


def ring(geom):
    return [(p["lon"], p["lat"]) for p in geom if p]


def polys_of(g):
    if g.is_empty:
        return []
    if g.geom_type == "Polygon":
        return [g]
    return [p for p in getattr(g, "geoms", []) for p in polys_of(p)]


def osm_shapes(elements, frame):
    """-> (land incl. rivers as holes, river/harbour water) clipped to frame."""
    coast, water = [], []
    for el in elements:
        t = el.get("tags", {})
        if el["type"] == "way" and t.get("natural") == "coastline":
            c = ring(el["geometry"])
            if len(c) > 1:
                coast.append(LineString(c))
        elif el["type"] == "way":
            c = ring(el.get("geometry", []))
            if len(c) > 3 and c[0] == c[-1]:
                water.append(Polygon(c).buffer(0))
        elif el["type"] == "relation":
            rings = {"outer": [], "inner": []}
            for mem in el.get("members", []):
                if mem["type"] == "way" and mem.get("geometry"):
                    c = ring(mem["geometry"])
                    if len(c) > 1:
                        rings["inner" if mem.get("role") == "inner" else "outer"].append(LineString(c))
            outer = unary_union(list(polygonize(unary_union(rings["outer"])))) if rings["outer"] else Polygon()
            inner = unary_union(list(polygonize(unary_union(rings["inner"])))) if rings["inner"] else Polygon()
            water.append(outer.difference(inner).buffer(0))
    # Coastline ways have land on their LEFT. Cut the frame into faces, then let each face
    # be voted land or sea by points just either side of the coastline segments.
    clipped = unary_union([c.intersection(frame) for c in coast])
    faces = list(polygonize(unary_union([clipped, frame.exterior])))
    tree = shapely.STRtree(faces)
    votes = np.zeros(len(faces))
    eps = 2e-6
    for c in coast:
        xy = np.asarray(c.coords)
        d = np.diff(xy, axis=0)
        ln = np.hypot(d[:, 0], d[:, 1])
        keep = ln > 0
        mid = ((xy[:-1] + xy[1:]) / 2)[keep]
        nrm = np.stack([-d[keep, 1], d[keep, 0]], 1) / ln[keep, None]
        for side, vote in ((eps, 1), (-eps, -1)):
            pts = shapely.points(mid + nrm * side)
            _, fi = tree.query(pts, predicate="within")
            np.add.at(votes, fi, vote)
    land = unary_union([f for f, v in zip(faces, votes) if v > 0])
    log("coast faces", len(faces), "land", int((votes > 0).sum()), "undecided", int((votes == 0).sum()))
    if land.is_empty:
        raise RuntimeError("no land found from OSM coastline")
    inland = unary_union([w for w in water if not w.is_empty]).intersection(frame).intersection(land)
    # Only water that a boat can reach from the sea: drop isolated ponds and canals.
    parts = polys_of(inland.buffer(0.00025))
    sea = frame.difference(land)
    connected = unary_union([p for p in parts if p.intersects(sea)])
    inland = inland.intersection(connected)
    return land.difference(inland), inland, sea


def read_points(path, T, shape):
    """Bin soundings (shallowest per cell), newest survey first; older data only fills gaps."""
    grid = np.full(shape, np.nan, "float32")
    files = []
    for z in path:
        zf = zipfile.ZipFile(z)
        for n in zf.namelist():
            m = re.match(r"[A-Z]{2}(\d{8})\w*_(Single|Multi|DigiSingle)\.txt$", os.path.basename(n))
            if m:
                files.append((m.group(1), zf, n))
    for date, zf, n in sorted(files, key=lambda f: f[0], reverse=True):
        with zf.open(n) as fh:
            raw = fh.read().replace(b",", b" ")
        vals = np.array(raw.split(), dtype="float64")
        if len(vals) < 3 or len(vals) % 3:
            log("  skip", n, len(vals))
            continue
        p = vals.reshape(-1, 3)
        p = p[(p[:, 2] < 3) & (p[:, 2] > -45) & (p[:, 2] != 0)]
        if "SC" in os.path.basename(n)[:2]:
            p = p[p[:, 2] > -25]
        if not len(p):
            continue
        lon, lat = warp_xy("EPSG:28350", "EPSG:4326", p[:, 0], p[:, 1])
        c = ((np.asarray(lon) - T.c) / T.a).astype(int)
        r = ((np.asarray(lat) - T.f) / T.e).astype(int)
        ok = (c >= 0) & (r >= 0) & (c < shape[1]) & (r < shape[0])
        if not ok.any():
            continue
        r, c, z = r[ok], c[ok], p[ok, 2].astype("float32")
        r0, r1, c0, c1 = max(r.min() - 5, 0), r.max() + 6, max(c.min() - 5, 0), c.max() + 6
        sub = grid[r0:r1, c0:c1]
        new = np.full(sub.shape, -np.inf, "float32")
        np.maximum.at(new, (r - r0, c - c0), z)
        near_newer = ndimage.binary_dilation(np.isfinite(sub), iterations=4)
        use = np.isfinite(new) & ~near_newer
        sub[use] = new[use]
        log(f"  {n}: {len(p)} soundings, {int(use.sum())} cells used")
    return grid


def main():
    base_path, work = sys.argv[1:3]
    raw = os.path.join(work, "metro_raw")
    os.makedirs(raw, exist_ok=True)
    w, s, e, n = METRO_BBOX
    clip = box(*METRO_BBOX)
    frame = box(w - PAD, s - PAD, e + PAD, n + PAD)
    cols, rows = round((e - w + 2 * PAD) / RES), round((n - s + 2 * PAD) / RES)
    T = from_origin(w - PAD, n + PAD, RES, RES)
    shape = (rows, cols)
    log("grid", shape)

    land, inland, sea = osm_shapes(fetch_osm(os.path.join(raw, "osm.json")), frame)
    land_m = rasterize([land], shape, transform=T, dtype="uint8").astype(bool)
    river_m = rasterize([inland], shape, transform=T, dtype="uint8").astype(bool) if not inland.is_empty \
        else np.zeros(shape, bool)
    water_m = ~land_m
    log(f"land {land_m.mean():.2%} river/harbour {river_m.mean():.2%}")

    # 1. Survey grids, shallowest 5 m value per cell.
    surv = np.full(shape, np.nan, "float32")
    for name in RASTERS:
        path = download(f"{S3}/Rasters/{name}_5m.tif", os.path.join(raw, f"{name}_5m.tif"))
        tmp = np.full(shape, np.nan, "float32")
        with rasterio.open(path) as src:
            reproject(rasterio.band(src, 1), tmp, dst_transform=T, dst_crs="EPSG:4326",
                      src_nodata=-9999, dst_nodata=np.nan, resampling=Resampling.max)
        ok = np.isfinite(tmp) & (tmp > -200)
        surv[ok] = tmp[ok]
        log(f"  {name}: {ok.mean():.2%}")
    surv[land_m] = np.nan

    # 2. Soundings, interpolated between survey lines, only close to real data.
    zips = [download(f"{S3}/PointData/{p}.zip", os.path.join(raw, f"{p}.zip")) for p in POINTS]
    pts = read_points(zips, T, shape)
    pts[land_m | np.isfinite(surv)] = np.nan
    lab, k = ndimage.label(ndimage.binary_dilation(np.isfinite(pts), iterations=12))
    log("sounding clusters", k)
    cellx = RES * 111320 * np.cos(np.radians(32))
    celly = RES * 110900
    filled = 0
    for i, sl in enumerate(ndimage.find_objects(lab)):
        r0, r1, c0, c1 = sl[0].start, sl[0].stop, sl[1].start, sl[1].stop
        r0, c0 = max(r0 - 10, 0), max(c0 - 10, 0)
        r1, c1 = min(r1 + 10, rows), min(c1 + 10, cols)
        sub, lnd = pts[r0:r1, c0:c1], land_m[r0:r1, c0:c1]
        have = np.isfinite(sub)
        if have.sum() < 20:
            continue
        # Banks count as 0.3 m deep so the bed shelves up to the shore.
        bank = lnd & ndimage.binary_dilation(~lnd)
        rr, cc = np.nonzero(have | bank)
        zz = np.where(have, sub, -0.3)[rr, cc]
        dist = ndimage.distance_transform_edt(~have, sampling=(celly, cellx))
        want = ~lnd & ~np.isfinite(surv[r0:r1, c0:c1]) & ~have & (dist <= MAX_GAP_M)
        qr, qc = np.nonzero(want)
        if len(qr):
            v = LinearNDInterpolator(np.c_[rr * celly, cc * cellx], zz)(np.c_[qr * celly, qc * cellx])
            sub[qr, qc] = v
            filled += int(np.isfinite(v).sum())
    log("interpolated cells", filled)
    ok = np.isfinite(pts) & ~np.isfinite(surv)
    surv[ok] = pts[ok]
    del pts, lab
    have = np.isfinite(surv)
    log(f"surveyed share of river/harbour water: {(have & river_m).sum() / max(1, river_m.sum()):.1%}; "
        f"of all water: {(have & water_m).sum() / water_m.sum():.1%}")

    # 3. Sea without survey: 250 m grid, shifted to meet the surveys around the edges.
    base = np.full(shape, np.nan, "float32")
    with rasterio.open(base_path) as src:
        reproject(rasterio.band(src, 1), base, dst_transform=T, dst_crs="EPSG:4326",
                  dst_nodata=np.nan, resampling=Resampling.bilinear)
    f = 10
    R, C = rows // f * f, cols // f * f
    hv = have[:R, :C] & ~river_m[:R, :C] & np.isfinite(base[:R, :C])
    dl = np.where(hv, np.nan_to_num(surv[:R, :C] - base[:R, :C]), 0)
    blk = lambda a: a.reshape(R // f, f, C // f, f).sum((1, 3))
    num, den = blk(dl), blk(hv.astype("float32"))
    G = lambda a: ndimage.gaussian_filter(a.astype("float64"), 5)
    gn, gd = G(num), G(den) / (f * f)
    shift = np.where(gd > 1e-4, gn / (f * f) / np.maximum(gd, 1e-4), 0) * np.clip(gd * 2, 0, 1)
    big = np.zeros(shape, "float32")
    big[:R, :C] = ndimage.zoom(shift, f, order=1, grid_mode=True, mode="nearest")[:R, :C]
    z = base + big
    z[have] = surv[have]
    # Match the 250 m chart exactly at the edge of the box.
    edge = np.minimum.reduce([np.broadcast_to(np.minimum(np.arange(rows), np.arange(rows)[::-1])[:, None], shape),
                              np.broadcast_to(np.minimum(np.arange(cols), np.arange(cols)[::-1])[None], shape)])
    k = np.clip((edge - 40) / 150, 0, 1).astype("float32")
    sea_m = water_m & ~river_m
    z = np.where(sea_m, z * k + base * (1 - k), z)
    z[river_m & ~have] = np.nan
    del base, big, edge, k

    # 4. Light smoothing that never lowers a shoal by more than 0.25 m.
    z[water_m] = np.minimum(z[water_m], -0.05)
    z[land_m] = np.nan
    valid = np.isfinite(z)
    g = ndimage.gaussian_filter(np.where(valid, z, 0), 1.2) / np.maximum(
        ndimage.gaussian_filter(valid.astype("float32"), 1.2), 1e-3)
    z = np.where(valid, np.maximum(g, z - 0.25), np.nan).astype("float32")
    # Run the depths a few cells under the land polygons, so there is no gap at the shore.
    grow = land_m & ndimage.binary_dilation(valid, iterations=4)
    idx = ndimage.distance_transform_edt(~valid, return_distances=False, return_indices=True)
    z[grow] = z[tuple(idx)][grow]
    del idx, g

    unsurveyed = river_m & ~valid
    log(f"river/harbour water without depths: {unsurveyed.sum() / max(1, river_m.sum()):.1%}")

    # 5. Vectorise.
    x = T.c + (np.arange(cols) + 0.5) * RES
    y = T.f - (np.arange(rows) + 0.5) * RES
    depth = np.ma.masked_invalid(-z)
    del z
    gen = contourpy.contour_generator(x, y, depth, name="serial", corner_mask=True,
                                      fill_type=contourpy.FillType.OuterOffset,
                                      line_type=contourpy.LineType.Separate)
    min_area = 3 * RES * RES
    step = 0.02
    land_tiles = [q for i in np.arange(w - PAD, e + PAD, step) for j in np.arange(s - PAD, n + PAD, step)
                  for q in polys_of(land.intersection(box(i, j, i + step, j + step)))]
    land_tree = shapely.STRtree(land_tiles)

    def write(name, feats):
        path = os.path.join(work, name)
        k = 0
        with open(path, "w") as fh:
            for geom, props, tc in feats:
                geom = geom.intersection(clip)
                if geom.is_empty:
                    continue
                geom = shapely.set_precision(geom, 1e-6)
                ft = {"type": "Feature", "properties": props, "geometry": mapping(geom)}
                if tc:
                    ft["tippecanoe"] = tc
                fh.write(json.dumps(ft, separators=(",", ":")) + "\n")
                k += 1
        log(name, k, "features", os.path.getsize(path) // 1_000_000, "MB")

    def bands():
        hi_all = float(depth.max())
        for lo, hi in zip(BANDS, BANDS[1:]):
            if lo > hi_all:
                break
            pts_l, off_l = gen.filled(lo, hi)
            for p, o in zip(pts_l, off_l):
                rings = [p[o[i]:o[i + 1]] for i in range(len(o) - 1)]
                poly = Polygon(rings[0], [r for r in rings[1:] if Polygon(r).area >= min_area / 3])
                if poly.area < min_area / 3:
                    continue
                poly = poly.simplify(RES * 0.2)
                if not poly.is_valid:
                    poly = poly.buffer(0)
                yield poly, {"dmin": lo, "dmax": hi}, None

    def lines():
        for lv in sorted(LINES + FINE):
            for p in gen.lines(lv):
                closed = len(p) > 3 and np.allclose(p[0], p[-1])
                if len(p) < 4 or (closed and Polygon(p).area < min_area):
                    continue
                ln = LineString(p).simplify(RES * 0.2)
                # Depths run slightly under the land; trim that part off the contours.
                hit = land_tree.query(ln, predicate="intersects")
                if len(hit):
                    ln = ln.difference(unary_union([land_tiles[i] for i in hit]))
                for part in shapely.get_parts(ln):
                    if part.length > RES * 1.5:
                        yield part, {"depth": lv}, ({"minzoom": 12} if lv in FINE else None)

    write("metro_depth.geojsonl", bands())
    write("metro_contour.geojsonl", lines())
    write("metro_land.geojsonl", ((p, {}, None) for p in polys_of(land)))
    write("metro_water.geojsonl", ((p, {}, None) for p in polys_of(inland)))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main()
