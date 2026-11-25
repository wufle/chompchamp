#!/usr/bin/env python3
"""Download the areas that change bag limits or seasons, as one small JSON file.

Bundled with the app (android/app/src/main/assets/limit_areas.json) beside the hand-entered
bag_limits.json. The bioregions themselves are simple lines, so they are in code (BagLimits.kt).

Source: WA DPIRD Fisheries Guide (DPIRD-061) via SLIP, CC BY 4.0. Areas are grown slightly before
simplifying and islands are filled, so a point just outside counts as inside, never the reverse.

Usage: fetch_limit_areas.py OUTPUT.json      (needs shapely)
"""
import json
import sys
import urllib.request

from shapely.geometry import Point, Polygon, shape
from shapely.ops import unary_union

BASE = ("https://public-services.slip.wa.gov.au/public/rest/services/SLIP_Public_Services/"
        "DPIRD_Fisheries_Guide/MapServer")
UA = {"User-Agent": "chompchamp-pipeline/0.1"}
LAYERS = {
    "abrolhos": (243, "Abrolhos Islands Fish Habitat Protection Area"),
    "cockburn_warnbro": (131, "Prohibition on Fishing (Cockburn Sound and Warnbro Sound) Order 2005"),
    "crab_closure": (204, "Prohibition on Fishing for Blue Swimmer Crabs Order 2019"),
}
GROW, TOLERANCE = 0.0006, 0.0005  # degrees, about 60 and 50 m


def fetch(layer, name):
    url = f"{BASE}/{layer}/query?where=1%3D1&outFields=name&outSR=4326&f=geojson"
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=180) as r:
        features = json.load(r)["features"]
    if not features or any(f["properties"]["name"] != name for f in features):
        sys.exit(f"layer {layer} is no longer '{name}'; the service numbering has changed")
    return unary_union([shape(f["geometry"]).buffer(0) for f in features])


def main(out):
    areas = {}
    for key, (layer, name) in LAYERS.items():
        g = fetch(layer, name)
        grown = g.buffer(GROW).simplify(TOLERANCE)
        polys = [Polygon(p.exterior) for p in (grown.geoms if hasattr(grown, "geoms") else [grown])]
        merged = unary_union(polys)
        missing = g.difference(merged.buffer(1e-6)).area
        if missing > 1e-9:
            sys.exit(f"{key}: simplified area misses part of the original ({missing})")
        polys = list(merged.geoms) if hasattr(merged, "geoms") else [merged]
        areas[key] = [[[round(x, 5), round(y, 5)] for x, y in p.exterior.coords] for p in polys]
        print(key, len(polys), "polygons", sum(map(len, areas[key])), "points")
    for key, lon, lat, inside in [("cockburn_warnbro", 115.71, -32.2, True), ("cockburn_warnbro", 115.5, -32.2, False),
                                  ("crab_closure", 115.82, -32.0, True), ("crab_closure", 115.4, -33.6, False),
                                  ("abrolhos", 113.8, -28.7, True), ("abrolhos", 114.5, -28.7, False)]:
        if any(Polygon(r).contains(Point(lon, lat)) for r in areas[key]) != inside:
            sys.exit(f"{key}: check point {lat},{lon} should be {'inside' if inside else 'outside'}")
    with open(out, "w") as f:
        json.dump({"source": "WA DPIRD Fisheries Guide (DPIRD-061), CC BY 4.0, simplified", "areas": areas}, f,
                  separators=(",", ":"))


main(sys.argv[1])
