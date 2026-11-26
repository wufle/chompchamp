#!/usr/bin/env python3
"""Pick random test points in every depth band of the clipped source grid.

Usage: check_points.py wa_dem.tif points.json   -> [[lon, lat, elevation, band], ...]
"""
import json
import sys

import numpy as np
import rasterio

import os
from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location("build_metro", os.path.join(os.path.dirname(os.path.abspath(__file__)), "build_metro.py"))
bm = module_from_spec(spec)
spec.loader.exec_module(bm)

BANDS = [-12000, -1000, -500, -200, -100, -50, -30, -20, -10, -5, -2, 0, 2, 20, 9999]


def main():
    rng = np.random.default_rng(1)
    with rasterio.open(sys.argv[1]) as r:
        a, t, nodata = r.read(1), r.transform, r.nodata
    ok = np.isfinite(a) if nodata is None else np.isfinite(a) & (a != nodata)
    # The detailed box (Two Rocks to Preston Beach) is built from other surveys.
    w, s, e, n = bm.METRO_BBOX
    lon = t.c + (np.arange(a.shape[1]) + 0.5) * t.a
    lat = t.f + (np.arange(a.shape[0]) + 0.5) * t.e
    ok &= ~(((lon > w) & (lon < e))[None, :] & ((lat > s) & (lat < n))[:, None])
    out = []
    for lo, hi in zip(BANDS, BANDS[1:]):
        rr, cc = np.nonzero(ok & (a >= lo) & (a < hi))
        for i in rng.choice(len(rr), min(400, len(rr)), replace=False):
            out.append([float(lon[cc[i]]), float(lat[rr[i]]), float(a[rr[i], cc[i]]), f"{lo}..{hi}"])
    json.dump(out, open(sys.argv[2], "w"))
    print(len(out), "points", file=sys.stderr)


if __name__ == "__main__":
    main()
