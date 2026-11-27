#!/usr/bin/env python3
"""High-detail charts for the areas in REGIONS, each replacing the 250 m chart inside its box:

  metro     Two Rocks to Preston Beach: the coast, Rottnest, Cockburn Sound, the Swan/Canning rivers,
            Mandurah, the Dawesville Cut and the Peel-Harvey estuary
  kalbarri  Kalbarri to Horrocks: the Murchison River entrance, Port Gregory and Horrocks

Sources:
  * WA Department of Transport 5 m composite grids (DOT-022) and point soundings
    (DOT-021), CC BY 4.0. Heights are AHD (close to mean sea level).
  * OpenStreetMap coastline and river/harbour outlines, ODbL 1.0
  * kalbarri only: AusBathyTopo 30m (Perth) 2026, Geoscience Australia, CC BY 4.0. Its LiDAR and multibeam
    cells count as survey; the rest of it (south of 28 S) is the base. Read a window at a time from the zip online.
  * AusBathyTopo 250 m (already clipped to WA) where there is nothing better, sea only

Depth is never made up for rivers and harbours: water with no soundings nearby is drawn as
water but gets no depth band, so the app reports no depth there.

Usage: build_metro.py BASE_DEM.tif WORKDIR [REGION]   (REGION: metro (default) or kalbarri)
Outputs: <region>_depth / _contour / _land / _water .geojsonl
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
import rasterio.features
import rasterio.windows
from rasterio.features import rasterize
from rasterio.transform import from_origin
from rasterio.warp import Resampling, reproject, transform as warp_xy
from scipy import ndimage
from scipy.interpolate import LinearNDInterpolator, NearestNDInterpolator
import shapely
from shapely.geometry import LineString, MultiPolygon, Polygon, box, mapping
from shapely.ops import linemerge, polygonize, unary_union

ABT30 = ("/vsizip//vsicurl/https://files.ausseabed.gov.au/survey/AusBathyTopo_Perth_2026_30m.zip/"
         "AusBathyTopo_Perth_2026_30m/AusBathyTopo_Perth_2026_30m_MSL_{}.tif")
REGIONS = {
    "metro": dict(
        bbox=(115.40, -32.90, 116.02, -31.42),
        rasters=["TwoRocks", "OceanReef", "Rottnest", "Warnbro", "CockburnSound", "Perth", "Dawesville", "Yalgorup"],
        points=["SC", "CS", "FR", "WB", "MH", "DV"],   # MH: Mandurah; DV: Dawesville and Peel Inlet
        sea_gap_m=80, abt30=False),
    # The 30 m grid stops at 28 S, so north of that (Kalbarri itself) only the river entrance and a few
    # offshore soundings beat the 250 m grid. Sounding lines along the coast to the south are ~500 m apart.
    "kalbarri": dict(
        bbox=(113.95, -28.45, 114.55, -27.60),
        rasters=["Kalbarri"],
        points=["KL", "PG"],                            # KL: Kalbarri; PG: Port Gregory, Horrocks, Bowes
        sea_gap_m=1200, abt30=True),
    # Cliff Head to Seven Mile Beach. Airborne LiDAR covers almost all of the nearshore here and agrees with the
    # DoT soundings (1991-2011) to about 0.5 m, so it wins over depths interpolated between sounding lines.
    "dongara": dict(
        bbox=(114.72, -29.60, 115.05, -29.05),
        rasters=["PortDenison"],
        points=["PD"],                                  # PD: Port Denison and Dongara
        sea_gap_m=1200, abt30=True, lidar_first=True),
}
METRO_BBOX = REGIONS["metro"]["bbox"]
BOXES = [r["bbox"] for r in REGIONS.values()]
PAD = 0.004           # build slightly larger, then clip to the box
RES = 0.0001          # about 10 m
S3 = "https://s3-ap-southeast-2.amazonaws.com/transport.wa/Bathymetry"
MAX_GAP_M = 80        # no depth further than this from a real sounding in rivers and harbours
OVERPASS = ["https://overpass-api.de/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"]
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


def fetch_osm(path, bbox):
    if os.path.exists(path):
        return json.load(open(path))["elements"]
    w, s, e, n = bbox
    m = 0.1
    b = f"{s - m},{w - m},{n + m},{e + m}"
    q = f"""[out:json][timeout:300];
(way["natural"="coastline"]({b});
 way["natural"="water"]["water"~"^(river|harbour|canal|bay)$"]({b});
 relation["natural"="water"]["water"~"^(river|harbour|canal|bay)$"]({b});
 way["natural"="water"][!"water"]({b});
 relation["natural"="water"][!"water"]({b});
 way["waterway"="dock"]({b});
);out geom;"""
    tries = 3 * len(OVERPASS)
    for attempt in range(tries):
        url = OVERPASS[attempt % len(OVERPASS)]
        try:
            req = urllib.request.Request(url, data=urllib.parse.urlencode({"data": q}).encode(), headers=UA)
            with urllib.request.urlopen(req, timeout=360) as r:
                d = json.load(r)
            if not any(x["tags"].get("natural") == "coastline" for x in d["elements"]):
                raise RuntimeError("no coastline in Overpass reply")
            json.dump(d, open(path, "w"))
            return d["elements"]
        except Exception as ex:  # noqa: BLE001
            if attempt == tries - 1:
                raise
            log("Overpass retry", url, ex)
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
    coast, water, untagged = [], [], []
    for el in elements:
        t = el.get("tags", {})
        # natural=water with no water=* is a marina, canal estate or harbour basin (Mandurah), or a lake:
        # kept only where it joins the sea or a river, below.
        bare = t.get("natural") == "water" and "water" not in t
        if bare and t.get("intermittent") == "yes":
            continue
        into = untagged if bare else water
        if el["type"] == "way" and t.get("natural") == "coastline":
            c = ring(el["geometry"])
            if len(c) > 1:
                coast.append(LineString(c))
        elif el["type"] == "way":
            c = ring(el.get("geometry", []))
            if len(c) > 3 and c[0] == c[-1]:
                into.append(Polygon(c).buffer(0))
        elif el["type"] == "relation":
            rings = {"outer": [], "inner": []}
            for mem in el.get("members", []):
                if mem["type"] == "way" and mem.get("geometry"):
                    c = ring(mem["geometry"])
                    if len(c) > 1:
                        rings["inner" if mem.get("role") == "inner" else "outer"].append(LineString(c))
            outer = unary_union(list(polygonize(unary_union(rings["outer"])))) if rings["outer"] else Polygon()
            inner = unary_union(list(polygonize(unary_union(rings["inner"])))) if rings["inner"] else Polygon()
            into.append(outer.difference(inner).buffer(0))
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
    sea = frame.difference(land)
    # Untagged water that touches the sea or a river (a few passes: canal -> marina -> river).
    reach = unary_union([sea, inland]).buffer(0.00002)
    left = [u for u in untagged if not u.is_empty and u.intersects(frame)]
    for _ in range(4):
        joined = [u for u in left if u.intersects(reach)]
        if not joined:
            break
        left = [u for u in left if not u.intersects(reach)]
        add = unary_union(joined).intersection(frame).intersection(land)
        inland = unary_union([inland, add])
        reach = unary_union([reach, add.buffer(0.00002)])
        log("untagged water joined", len(joined))
    # Only water that a boat can reach from the sea: drop isolated ponds and canals.
    parts = polys_of(inland.buffer(0.00025))
    connected = unary_union([p for p in parts if p.intersects(sea)])
    inland = inland.intersection(connected)
    return land.difference(inland), inland, sea


AREAS = {"SC": "Swan and Canning rivers", "CS": "Cockburn Sound", "FR": "Fremantle", "WB": "Warnbro Sound",
         "MH": "Mandurah", "DV": "Dawesville and Peel Inlet", "KL": "Kalbarri", "PG": "Port Gregory area",
         "RT": "Rottnest", "RK": "Rockingham", "JB": "Jervoise Bay", "PD": "Port Denison and Dongara"}
METHODS = {"Single": "single beam", "Multi": "multibeam", "DigiSingle": "single beam, digitised from paper charts"}


def sounding_source(name, zip_code):
    """("WA DoT soundings, Rottnest, Geordie 1982 (single beam)", accuracy 1-3) from a DoT point file name.
    Codes we can't be sure of take the area of the zip they came in; technical suffixes (33khz, CR) are dropped."""
    m = re.match(r"([A-Z]{2})(\d{4})\d{4}_?(\w*?)_(Single|Multi|DigiSingle)\.txt$", os.path.basename(name))
    code, year, sub, method = m.groups()
    area = AREAS.get(code) or AREAS.get(zip_code, zip_code)
    sub = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", sub)
    if len(sub) > 3 and sub.replace(" ", "").isalpha() and sub not in ("Chart", "White"):
        area = f"{area}, {sub}"
    acc = 3 if method == "DigiSingle" else 1 if int(year) >= 2000 else 2
    return f"WA DoT soundings, {area} {year} ({METHODS[method]})", acc


def read_points(path, T, shape, add_source):
    """Bin soundings (shallowest per cell), newest survey first; older data only fills gaps.
    -> (depth grid, source id grid) where ids come from add_source(label, accuracy)."""
    grid = np.full(shape, np.nan, "float32")
    ids = np.zeros(shape, "uint8")
    files = []
    for z in path:
        zf = zipfile.ZipFile(z)
        zcode = os.path.basename(z)[:2]
        for n in zf.namelist():
            m = re.match(r"[A-Z]{2}(\d{8})\w*_(Single|Multi|DigiSingle)\.txt$", os.path.basename(n))
            if m:
                files.append((m.group(1), zf, n, zcode))
    for date, zf, n, zcode in sorted(files, key=lambda f: f[0], reverse=True):
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
        if use.any():
            ids[r0:r1, c0:c1][use] = add_source(*sounding_source(n, zcode))
        log(f"  {n}: {len(p)} soundings, {int(use.sum())} cells used")
    return grid, ids


def read_abt30(raw, bbox, T, shape):
    """AusBathyTopo 30m (Perth) 2026 on our grid: (depth, real, tid) where real = LiDAR or multibeam (TID 11, 15)."""
    os.environ.setdefault("GDAL_HTTP_USERAGENT", UA["User-Agent"])
    os.environ.setdefault("GDAL_DISABLE_READDIR_ON_OPEN", "EMPTY_DIR")
    w, s, e, n = bbox
    out = []
    for kind, rs in (("cog", Resampling.bilinear), ("tid", Resampling.nearest)):
        cache = os.path.join(raw, f"abt30_{kind}.tif")
        if not os.path.exists(cache):
            for attempt in range(4):
                try:
                    with rasterio.open(ABT30.format(kind)) as src:
                        win = rasterio.windows.from_bounds(w - 0.02, s - 0.02, e + 0.02, n + 0.02, transform=src.transform)
                        win = win.intersection(rasterio.windows.Window(0, 0, src.width, src.height))
                        a = src.read(1, window=win)
                        prof = dict(driver="GTiff", width=a.shape[1], height=a.shape[0], count=1, dtype=a.dtype,
                                    crs="EPSG:4326", transform=src.window_transform(win), nodata=src.nodata,
                                    compress="deflate")
                    with rasterio.open(cache + ".part", "w", **prof) as dst:
                        dst.write(a, 1)
                    os.replace(cache + ".part", cache)
                    break
                except Exception as ex:  # noqa: BLE001
                    if attempt == 3:
                        raise
                    log("AusBathyTopo 30 m retry", ex)
                    time.sleep(20)
        dst = np.full(shape, np.nan if kind == "cog" else 255, "float32" if kind == "cog" else "uint8")
        with rasterio.open(cache) as src:
            reproject(rasterio.band(src, 1), dst, dst_transform=T, dst_crs="EPSG:4326",
                      src_nodata=src.nodata, dst_nodata=np.nan if kind == "cog" else 255, resampling=rs)
        out.append(dst)
    z, tid = out
    z[(z > 1e30) | (z < -12000)] = np.nan
    real = np.isfinite(z) & np.isin(tid, (11, 15))
    log(f"AusBathyTopo 30 m: {np.isfinite(z).mean():.1%} of the box, LiDAR/multibeam {real.mean():.2%}")
    return z, real, tid


NEAR = [100, 250, 500, 1000, 2000, 5000]   # metres to the nearest measured depth, rounded up to these
F = 5                                          # source cells are F x F depth cells (about 50 m)


def source_layer(sources, sid, from_survey, water_m, river_m, valid, abt_have, abt30, T, shape, lat):
    """Polygons saying where the depth came from and how good it is: src (text), acc (1 good, 2 fair, 3 poor,
    4 rough: background grid; 0: no depth), near (metres to the nearest measured depth, rounded up to NEAR).
    Outside the detailed boxes there is no source layer: readers take a chart that has one anywhere to mean the
    250 m grid there (ChartReader.NATIONAL_GRID in the app, NATIONAL_GRID in the planner)."""
    R, C = shape[0] // F * F, shape[1] // F * F
    blk = lambda a, how: getattr(a[:R, :C].reshape(R // F, F, C // F, F), how)((1, 3))
    meas = blk(sid > 0, "any")
    ids = blk(sid, "max")
    water = blk(water_m, "mean") > 0.5
    unknown = water & (blk(river_m & ~valid, "mean") > 0.5)
    survey = blk(from_survey, "mean") > 0.5
    cm = (RES * F * 111320 * np.cos(np.radians(lat)), RES * F * 110900)
    if meas.any():
        dist, (ir, ic) = ndimage.distance_transform_edt(~meas, sampling=(cm[1], cm[0]), return_indices=True)
        near_id = ids[ir, ic]
    else:
        dist, near_id = np.full(meas.shape, np.inf), np.zeros(meas.shape, "uint8")
    bg250 = len(sources)
    sources = sources + [("AusBathyTopo 250 m national grid (Geoscience Australia), compiled from charts and surveys", 4),
                         ("AusBathyTopo 30m (Perth) 2026 compiled grid, between surveys", 4)]
    bucket = np.minimum(np.searchsorted(NEAR, np.where(meas, 0, dist)), len(NEAR) - 1)
    abt_c = blk(abt_have, "mean") > 0.5 if abt_have is not None else np.zeros(meas.shape, bool)
    bg = ~survey | (near_id == 0)
    acc_of = np.array([0] + [a for _, a in sources[1:]], "int32")
    src = np.where(bg, np.where(abt30 & abt_c, bg250 + 1, bg250), near_id.astype("int32"))
    acc = np.where(bg, 4, np.minimum(acc_of[np.minimum(near_id, len(acc_of) - 1)] + np.digitize(bucket, [1, 3]), 3))
    near = np.where(bg, -1, bucket)
    src[unknown], acc[unknown], near[unknown] = 0, 0, -1
    key = (src * 10 + acc) * 10 + (near + 1)
    keys, inv = np.unique(np.where(water, key, -1), return_inverse=True)
    out = inv.reshape(key.shape).astype("int32") + 1
    out[~water] = 0
    props = {}
    for j, k in enumerate(keys):
        if k >= 0:
            props[j + 1] = (int(k // 100), int(k // 10 % 10), int(k % 10) - 1)
    log("source classes", len(props))
    Tc = T * rasterio.Affine.scale(F, F)
    for geom, v in rasterio.features.shapes(out, mask=out > 0, transform=Tc, connectivity=4):
        i, a, b = props[int(v)]
        p = {"src": "No depth survey here" if i == 0 else sources[i][0], "acc": a if i else 0}
        if b >= 0:
            p["near"] = NEAR[b]
        g = shapely.geometry.shape(geom).simplify(RES * 0.5)
        if not g.is_empty:
            # Only the most detailed tiles: that is all the app and planner read depths from.
            yield g, p, {"minzoom": 14}


def main():
    base_path, work = sys.argv[1:3]
    region = sys.argv[3] if len(sys.argv) > 3 else "metro"
    cfg = REGIONS[region]
    raw = os.path.join(work, f"{region}_raw")
    os.makedirs(raw, exist_ok=True)
    w, s, e, n = cfg["bbox"]
    clip = box(*cfg["bbox"])
    frame = box(w - PAD, s - PAD, e + PAD, n + PAD)
    cols, rows = round((e - w + 2 * PAD) / RES), round((n - s + 2 * PAD) / RES)
    T = from_origin(w - PAD, n + PAD, RES, RES)
    shape = (rows, cols)
    log("grid", shape)

    land, inland, sea = osm_shapes(fetch_osm(os.path.join(raw, "osm.json"), cfg["bbox"]), frame)
    land_m = rasterize([land], shape, transform=T, dtype="uint8").astype(bool)
    river_m = rasterize([inland], shape, transform=T, dtype="uint8").astype(bool) if not inland.is_empty \
        else np.zeros(shape, bool)
    water_m = ~land_m
    log(f"land {land_m.mean():.2%} river/harbour {river_m.mean():.2%}")

    # Where each depth came from, for the "source" layer: id -> (label, accuracy 1 good .. 4 rough).
    sources = [None]

    def add_source(label, acc):
        if (label, acc) not in sources:
            sources.append((label, acc))
        return sources.index((label, acc))
    sid = np.zeros(shape, "uint8")   # measured cells only

    # 1. Survey grids, shallowest 5 m value per cell.
    surv = np.full(shape, np.nan, "float32")
    for name in cfg["rasters"]:
        path = download(f"{S3}/Rasters/{name}_5m.tif", os.path.join(raw, f"{name}_5m.tif"))
        tmp = np.full(shape, np.nan, "float32")
        with rasterio.open(path) as src:
            reproject(rasterio.band(src, 1), tmp, dst_transform=T, dst_crs="EPSG:4326",
                      src_nodata=-9999, dst_nodata=np.nan, resampling=Resampling.max)
        ok = np.isfinite(tmp) & (tmp > -200)
        surv[ok] = tmp[ok]
        sid[ok] = add_source(f"WA DoT survey grid, {re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', name)} (multibeam, LiDAR or laser, 5 m)", 1)
        log(f"  {name}: {ok.mean():.2%}")
    surv[land_m] = np.nan
    grid_m = np.isfinite(surv)

    # 2. Soundings, interpolated between survey lines, only close to real data: within MAX_GAP_M in
    # rivers and harbours, sea_gap_m at sea (where lines can be a few hundred metres apart).
    zips = [download(f"{S3}/PointData/{p}.zip", os.path.join(raw, f"{p}.zip")) for p in cfg["points"]]
    pts, pid = read_points(zips, T, shape, add_source)
    pts[land_m | np.isfinite(surv)] = np.nan
    measured = np.isfinite(pts)   # sounding cells, before filling between the lines
    cellx = RES * 111320 * np.cos(np.radians((s + n) / 2))
    celly = RES * 110900
    gap = cfg["sea_gap_m"]
    reach = int(np.ceil(gap / 2 / min(cellx, celly)))
    if reach <= 12:
        lab, k = ndimage.label(ndimage.binary_dilation(np.isfinite(pts), iterations=12))
    else:
        lab, k = ndimage.label(ndimage.distance_transform_edt(~np.isfinite(pts), sampling=(celly, cellx)) <= gap / 2 + 10)
    log("sounding clusters", k)
    filled = 0
    # How much an interpolated depth counts against the background grid: fades out between half the sea
    # gap and the full gap from the nearest sounding, so sparse soundings don't end in a hard edge.
    weight = np.ones(shape, "float32")
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
        want = ~lnd & ~np.isfinite(surv[r0:r1, c0:c1]) & ~have & (
            (dist <= MAX_GAP_M) | (~river_m[r0:r1, c0:c1] & (dist <= gap)))
        qr, qc = np.nonzero(want)
        if len(qr):
            far = (dist[qr, qc] > MAX_GAP_M) & ~river_m[r0:r1, c0:c1][qr, qc]
            v = np.full(len(qr), np.nan)
            v[~far] = LinearNDInterpolator(np.c_[rr * celly, cc * cellx], zz)(np.c_[qr[~far] * celly, qc[~far] * cellx])
            if far.any():
                # Further out at sea: from the soundings alone (the shore points would make long thin
                # triangles), and past the outermost ones from the nearest, faded out by the weight below.
                hr, hc = np.nonzero(have)
                pts_xy = np.c_[hr * celly, hc * cellx]
                q = np.c_[qr[far] * celly, qc[far] * cellx]
                vf = LinearNDInterpolator(pts_xy, sub[hr, hc])(q)
                miss = ~np.isfinite(vf)
                if miss.any():
                    vf[miss] = NearestNDInterpolator(pts_xy, sub[hr, hc])(q[miss])
                v[far] = vf
            sub[qr, qc] = v
            filled += int(np.isfinite(v).sum())
            dq = dist[qr, qc]
            far = (dq > MAX_GAP_M) & ~river_m[r0:r1, c0:c1][qr, qc]
            if far.any():
                wsub = weight[r0:r1, c0:c1]
                wsub[qr[far], qc[far]] = np.clip((gap - dq[far]) / (gap / 2), 0, 1)
    log("interpolated cells", filled)
    ok = np.isfinite(pts) & ~np.isfinite(surv)
    surv[ok] = pts[ok]
    sid[ok & (pid > 0)] = pid[ok & (pid > 0)]
    del pts, lab, pid
    abt = None
    if cfg["abt30"]:
        # 2b. LiDAR and multibeam from the 30 m compilation, where the DoT surveys don't reach.
        abt, real, tid = read_abt30(raw, cfg["bbox"], T, shape)
        ok = real & ~land_m & ~river_m & ~np.isfinite(surv) & (abt < 0)
        if cfg.get("lidar_first"):
            # Also over soundings and what was filled between them, but not the DoT 5 m grids, and not a
            # sounding over 1 m shallower than the LiDAR (a shoal it may have smoothed away).
            shoal = measured & (surv > abt + 1)
            ok |= real & ~land_m & ~river_m & ~grid_m & ~shoal & (abt < 0)
            weight[ok] = 1
            log(f"LiDAR over soundings: {(ok & measured).sum()} sounding cells, kept {shoal.sum()} shoaler ones")
        surv[ok] = abt[ok]
        for code, what in ((15, "airborne LiDAR"), (11, "multibeam")):
            sid[ok & (tid == code)] = add_source(f"{what[0].upper() + what[1:]}, from AusBathyTopo 30m (Perth) 2026", 1)
        del real, tid
    have = np.isfinite(surv)
    log(f"surveyed share of river/harbour water: {(have & river_m).sum() / max(1, river_m.sum()):.1%}; "
        f"of all water: {(have & water_m).sum() / water_m.sum():.1%}")

    # 3. Sea without survey: 250 m grid, shifted to meet the surveys around the edges.
    base = np.full(shape, np.nan, "float32")
    with rasterio.open(base_path) as src:
        reproject(rasterio.band(src, 1), base, dst_transform=T, dst_crs="EPSG:4326",
                  src_nodata=src.nodata, dst_nodata=np.nan, resampling=Resampling.bilinear)
    base250 = base
    abt_have = None
    if abt is not None:
        # The rest of the 30 m grid (south of 28 S) is a better base than the 250 m one.
        base = np.where(np.isfinite(abt), abt, base250)
        abt_have = np.isfinite(abt)
        del abt
    f = 10
    R, C = rows // f * f, cols // f * f
    hv = have[:R, :C] & ~river_m[:R, :C] & np.isfinite(base[:R, :C]) & (weight[:R, :C] > 0.999)
    dl = np.where(hv, np.nan_to_num(surv[:R, :C] - base[:R, :C]), 0)
    blk = lambda a: a.reshape(R // f, f, C // f, f).sum((1, 3))
    num, den = blk(dl), blk(hv.astype("float32"))
    G = lambda a: ndimage.gaussian_filter(a.astype("float64"), 5)
    gn, gd = G(num), G(den) / (f * f)
    shift = np.where(gd > 1e-4, gn / (f * f) / np.maximum(gd, 1e-4), 0) * np.clip(gd * 2, 0, 1)
    big = np.zeros(shape, "float32")
    big[:R, :C] = ndimage.zoom(shift, f, order=1, grid_mode=True, mode="nearest")[:R, :C]
    z = base + big
    z = np.where(have, np.where(np.isfinite(z), weight * surv + (1 - weight) * z, surv), z)
    from_survey = have & (weight >= 0.5)
    del weight
    # Match the 250 m chart exactly at the edge of the box.
    edge = np.minimum.reduce([np.broadcast_to(np.minimum(np.arange(rows), np.arange(rows)[::-1])[:, None], shape),
                              np.broadcast_to(np.minimum(np.arange(cols), np.arange(cols)[::-1])[None], shape)])
    k = np.clip((edge - 40) / 150, 0, 1).astype("float32")
    sea_m = water_m & ~river_m
    z = np.where(sea_m, z * k + base250 * (1 - k), z)
    z[river_m & ~have] = np.nan
    from_survey &= ~sea_m | (k >= 0.5)
    del base, base250, big, edge, k

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

    if os.environ.get("CHART_DEBUG_TIF"):   # for looking at the result before it is cut into bands
        with rasterio.open(os.environ["CHART_DEBUG_TIF"], "w", driver="GTiff", width=cols, height=rows, count=1,
                           dtype="float32", crs="EPSG:4326", transform=T, nodata=np.nan, compress="deflate") as dbg:
            dbg.write(z, 1)

    src_feats = source_layer(sources, sid, from_survey, water_m, river_m, np.isfinite(z), abt_have,
                             cfg["abt30"], T, shape, (s + n) / 2)
    del sid, from_survey, abt_have

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

    write(f"{region}_depth.geojsonl", bands())
    write(f"{region}_contour.geojsonl", lines())
    write(f"{region}_source.geojsonl", src_feats)
    write(f"{region}_land.geojsonl", ((p, {}, None) for p in polys_of(land)))
    write(f"{region}_water.geojsonl", ((p, {}, None) for p in polys_of(inland)))


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4) or (len(sys.argv) == 4 and sys.argv[3] not in REGIONS):
        sys.exit(__doc__)
    main()
