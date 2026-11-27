#!/usr/bin/env python3
"""Tide constituents along the WA coast for the app's tide predictions -> android/app/src/main/assets/tides.json.

From EOT20, a global empirical ocean tide model from satellite altimetry (Hart-Davis et al. 2021, DGFI-TUM, CC BY 4.0,
https://doi.org/10.17882/79489). Keeps the model's 1/8 degree cells within about 25 km of land in WA, with amplitude
(cm) and Greenwich phase lag (degrees) of 16 constituents, named after the nearest place in assets/places.geojson.

The download is 2.3 GB (ocean and load tides). Each constituent is unpacked, cut to WA and deleted in turn, so it
needs about 3 GB free. Needs numpy, scipy and netCDF4.

    python3 fetch_tides.py WORKDIR
"""
import json, os, sys, urllib.request, zipfile
import numpy as np
from scipy import ndimage

URL = "https://www.seanoe.org/data/00683/79489/data/85762.zip"
UA = {"User-Agent": "chompchamp-pipeline/0.1"}
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
PLACES = os.path.join(ROOT, "android/app/src/main/assets/places.geojson")
OUT = os.path.join(ROOT, "android/app/src/main/assets/tides.json")
# The app's Tides.kt knows these (S1 is left out: radiational, and tiny here).
CONS = ["m2", "s2", "n2", "k2", "2n2", "t2", "k1", "o1", "p1", "q1", "j1", "m4", "mf", "mm", "ssa", "sa"]
BOX = (-36.0, -13.0, 112.0, 129.0)   # WA's coast: lat from, to, lon from, to
# Station names: the nearest town within 35 km, else suburb, village or harbour within 20 km, else the nearest
# cape, bay, island or hamlet.
TIERS = [({"city", "town"}, 35), ({"suburb", "village", "harbour", "marina"}, 20),
         ({"cape", "bay", "island", "hamlet", "lighthouse"}, 1e9)]


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def crop(work):
    """{constituent: (lat, lon, complex cm)} cut to the box, one netCDF file at a time."""
    import netCDF4
    outer = os.path.join(work, "eot20.zip")
    if not os.path.exists(outer):
        log("downloading EOT20 (2.3 GB)")
        req = urllib.request.Request(URL, headers=UA)
        with urllib.request.urlopen(req, timeout=120) as r, open(outer + ".part", "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        os.replace(outer + ".part", outer)
    out = {}
    with zipfile.ZipFile(outer) as z, zipfile.ZipFile(z.open("ocean_tides.zip")) as oz:
        for c in CONS:
            name = f"ocean_tides/{c.upper()}_ocean_eot20.nc"
            tmp = os.path.join(work, os.path.basename(name))
            with oz.open(name) as src, open(tmp, "wb") as dst:
                while chunk := src.read(1 << 20):
                    dst.write(chunk)
            d = netCDF4.Dataset(tmp)
            lat, lon = d["lat"][:], d["lon"][:]
            i = (lat >= BOX[0] - 0.5) & (lat <= BOX[1] + 0.5)
            j = (lon >= BOX[2] - 0.5) & (lon <= BOX[3] + 0.5)
            re = np.ma.filled(d["real"][i, :][:, j].astype("f8"), np.nan)
            im = np.ma.filled(d["imag"][i, :][:, j].astype("f8"), np.nan)
            d.close()
            os.remove(tmp)
            out[c] = (np.asarray(lat[i]), np.asarray(lon[j]), re + 1j * im)
            log(c, re.shape)
    return out


def main():
    work = sys.argv[1]
    os.makedirs(work, exist_ok=True)
    cons = crop(work)
    lat, lon, m2 = cons["m2"]
    valid = np.isfinite(m2)
    for c in CONS:
        valid &= np.isfinite(cons[c][2])
    # Cells within two cells (~25 km) of land or of the model's coastal gap.
    coast = valid & ndimage.binary_dilation(~valid, iterations=2)
    L, O = np.meshgrid(lat, lon, indexing="ij")
    coast &= (L >= BOX[0]) & (L <= BOX[1]) & (O >= BOX[2]) & (O <= BOX[3])

    places = json.load(open(PLACES))["features"]
    pl = np.array([f["geometry"]["coordinates"][::-1] for f in places])
    kinds = np.array([f["properties"].get("kind") for f in places])
    names = [f["properties"]["name"] for f in places]

    def near(la, lo):
        d = np.hypot(pl[:, 0] - la, (pl[:, 1] - lo) * np.cos(np.radians(la))) * 111
        for kset, km in TIERS:
            dk = np.where(np.isin(kinds, list(kset)), d, np.inf)
            if dk.min() <= km:
                return names[int(np.argmin(dk))]
        return names[int(np.argmin(d))]

    stations = []
    for i, j in zip(*np.nonzero(coast)):
        h = [cons[c][2][i, j] for c in CONS]
        stations.append([round(float(lat[i]), 3), round(float(lon[j]), 3), near(lat[i], lon[j]),
                         [round(abs(x), 1) for x in h],
                         # Greenwich phase lag: atan2(imag, real), checked against the tide gauges.
                         [int(round(np.degrees(np.angle(x)))) % 360 for x in h]])
    json.dump({"source": "EOT20 (Hart-Davis et al. 2021, DGFI-TUM), CC BY 4.0, doi:10.17882/79489",
               "units": "amplitude cm, phase degrees (Greenwich lag)", "constituents": CONS, "stations": stations},
              open(OUT, "w"), separators=(",", ":"))
    log(f"{len(stations)} stations, {os.path.getsize(OUT) / 1e6:.2f} MB -> {OUT}")


if __name__ == "__main__":
    main()
