#!/usr/bin/env python3
"""Download place names for WA (towns, harbours, boat ramps, islands, capes, bays, reefs...) as one GeoJSON file.

The result is bundled with the app (android/app/src/main/assets/places.geojson) and the planner, where it is
drawn as labels and searched by name, offline.

Sources:
  * OpenStreetMap contributors, ODbL 1.0: everything, unless below
  * WA Department of Transport, CC BY 4.0: Public Boat Ramps (DOT-033) and Maritime Facility Locations (DOT-030).
    These win over OpenStreetMap ramps and harbours at the same spot.

Every feature is a point with:
  kind   city | town | village | suburb | hamlet | marina | harbour | ramp | jetty | lighthouse |
         island | cape | bay | beach | reef | bank | water
  name   the name; unnamed boat ramps are called "Boat ramp, <nearest town or suburb>"
  rank   1 (most important) .. 6: which zoom it is labelled from, and which wins when labels collide
  near   nearest town or suburb (for telling apart places with the same name), may be absent
  detail more about it, e.g. "Sealed, 4 lanes, 60 trailer bays", may be absent

Usage: fetch_places.py OUTPUT.geojson
"""
import json
import math
import sys
import time
import urllib.parse
import urllib.request

BBOX = (112.0, -36.0, 129.0, -13.0)  # WA, a little short of the border
OVERPASS = ["https://overpass-api.de/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]
UA = {"User-Agent": "chompchamp-pipeline/0.1"}
SLIP = "https://public-services.slip.wa.gov.au/public/rest/services/SLIP_Public_Services/"
DOT_RAMPS = [SLIP + f"Infrastructure_and_Utilities/MapServer/{n}" for n in (49, 50, 51)]
DOT_FACILITIES = SLIP + "Marine_and_Estuaries/MapServer/20"

QUERY = """[out:json][timeout:300];
(
 node["place"~"^(city|town|village|suburb|hamlet)$"]["name"]({b});
 nwr["place"~"^(island|islet)$"]["name"]({b});
 nwr["natural"~"^(cape|peninsula|bay|beach|reef|shoal|strait|spit)$"]["name"]({b});
 nwr["seamark:type"~"^(sea_area|harbour)$"]["name"]({b});
 nwr["leisure"="marina"]["name"]({b});
 nwr["harbour"="yes"]["name"]({b});
 nwr["landuse"="harbour"]["name"]({b});
 nwr["leisure"="slipway"]({b});
 nwr["man_made"="lighthouse"]["name"]({b});
 nwr["man_made"="pier"]["name"]({b});
 nwr["natural"="water"]["water"~"^(lagoon|lake|river|estuary)$"]["name"]({b});
);
out tags center bb;"""


def fetch():
    b = f"{BBOX[1]},{BBOX[0]},{BBOX[3]},{BBOX[2]}"
    data = urllib.parse.urlencode({"data": QUERY.format(b=b)}).encode()
    for attempt in range(6):
        url = OVERPASS[attempt % len(OVERPASS)]
        try:
            req = urllib.request.Request(url, data=data, headers=UA)
            with urllib.request.urlopen(req, timeout=360) as r:
                return json.load(r)["elements"]
        except Exception as e:  # noqa: BLE001
            print(f"  {url}: {e}", file=sys.stderr)
            time.sleep(10 * (attempt + 1))
    raise SystemExit("Overpass failed")


def arcgis(layer):
    """All points in an ArcGIS layer, as (lon, lat, attributes)."""
    out, offset = [], 0
    while True:
        q = urllib.parse.urlencode({"where": "1=1", "outFields": "*", "outSR": "4326", "f": "json",
                                    "resultOffset": offset, "resultRecordCount": 1000})
        for attempt in range(4):
            try:
                with urllib.request.urlopen(urllib.request.Request(f"{layer}/query?{q}", headers=UA), timeout=120) as r:
                    d = json.load(r)
                break
            except Exception as e:  # noqa: BLE001
                if attempt == 3:
                    raise
                print(f"  {layer}: {e}", file=sys.stderr)
                time.sleep(5 * (attempt + 1))
        feats = d.get("features", [])
        out += [(f["geometry"]["x"], f["geometry"]["y"], f["attributes"]) for f in feats if f.get("geometry")]
        if len(feats) < 1000:
            return out
        offset += len(feats)


def dot_places():
    out = []
    for layer in DOT_RAMPS:
        for lon, lat, a in arcgis(layer):
            name = (a.get("name_of_boat_ramp") or "").strip().rstrip(". ")
            name = ramp_name(name)
            kind = (a.get("boat_ramp_type1") or a.get("assetdesc") or "").strip()
            bits = [kind.replace(" Ramp", "").replace("Paddle Craft Launching Area", "Paddle craft only")] if kind else []
            if a.get("boat_ramps") and a["boat_ramps"] <= 12:   # some records have the car parks here
                bits.append(f"{a['boat_ramps']} lane" + ("s" if a["boat_ramps"] > 1 else ""))
            if a.get("finger_jetties"):
                bits.append(f"{a['finger_jetties']} finger jett" + ("ies" if a["finger_jetties"] > 1 else "y"))
            if a.get("trailer_car_parks"):
                bits.append(f"{a['trailer_car_parks']} trailer bays")
            out.append({"kind": "ramp", "name": name, "rank": 5, "lat": round(lat, 5), "lon": round(lon, 5),
                        "detail": ", ".join(bits).capitalize(), "dot": True})
    for lon, lat, a in arcgis(DOT_FACILITIES):
        name = (a.get("off_name") or "").strip()
        if name:
            out.append({"kind": "harbour", "name": name, "rank": 4, "lat": round(lat, 5), "lon": round(lon, 5), "dot": True})
    print(f"  DoT ramps and maritime facilities: {len(out)}", file=sys.stderr)
    return out


def ramp_name(name):
    """"Avalon Beach, Falcon" -> "Avalon Beach boat ramp, Falcon"; "" for no name at all."""
    if name.lower() in ("boat ramp", "ramp", "slipway", "boat launch"):
        return ""
    if not name or "ramp" in name.lower() or "launch" in name.lower():
        return name
    head, _, tail = name.partition(",")
    return f"{head.strip()} boat ramp" + (f", {tail.strip()}" if tail.strip() else "")


def km_between(a, b):
    return math.hypot((a["lat"] - b["lat"]) * 110.6, (a["lon"] - b["lon"]) * 111.3 * math.cos(math.radians(a["lat"])))


def size_km(el):
    """Rough size of a way or relation from its bounding box (0 for a node)."""
    bb = el.get("bounds")
    if not bb:
        return 0.0
    lat = (bb["minlat"] + bb["maxlat"]) / 2
    w = (bb["maxlon"] - bb["minlon"]) * 111.32 * math.cos(math.radians(lat))
    h = (bb["maxlat"] - bb["minlat"]) * 110.57
    return math.hypot(w, h)


def classify(t, km, name):
    """(kind, rank) for an element's tags, or None to leave it out."""
    place = t.get("place")
    if place == "city": return "city", 1
    if place == "town": return "town", 2
    if place == "village": return "village", 3
    if place == "suburb": return "suburb", 4
    if place == "hamlet": return "hamlet", 5
    if t.get("leisure") == "slipway": return "ramp", 5
    if t.get("leisure") == "marina" or t.get("harbour") == "yes" or t.get("landuse") == "harbour" \
            or t.get("seamark:type") == "harbour":
        return "harbour" if t.get("landuse") == "harbour" or t.get("harbour") == "yes" else "marina", 4
    if t.get("man_made") == "lighthouse": return "lighthouse", 5
    if t.get("man_made") == "pier":
        # Most named piers are berths and pontoons ("A jetty", "Pier 2"): keep real jetties.
        if "jetty" not in name.lower() or any(ch.isdigit() for ch in name) or len(name) < 8: return None
        return "jetty", 6
    nat = t.get("natural")
    if place in ("island", "islet"):
        return "island", 2 if km > 30 else 3 if km > 8 else 4 if km > 1.5 else 5
    if nat in ("cape", "peninsula", "spit"): return "cape", 3 if km > 20 else 4
    if nat in ("bay", "strait"):
        # Many bays are mapped as a single point, so the name says more about their size than the shape does.
        if any(w in name for w in (" Sound", " Gulf", " Estuary", " Inlet", " Harbour")): return "bay", 2 if km == 0 or km > 8 else 3
        return "bay", 2 if km > 40 else 3 if km > 8 else 4 if km > 1.5 or km == 0 else 5
    if nat == "beach": return "beach", 5 if km > 1 else 6
    if nat in ("reef", "shoal") or t.get("seamark:type") == "sea_area":
        return ("reef" if nat == "reef" else "bank"), 3 if km > 20 else 4 if km > 3 else 5
    if nat == "water":
        if km < 1.5: return None
        return "water", 2 if km > 40 else 3 if km > 8 else 4
    return None


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    els = fetch()
    print(f"  {len(els)} elements", file=sys.stderr)
    feats = []
    seen = set()
    for el in els:
        t = el.get("tags", {})
        bb = el.get("bounds") or {}
        lat = el.get("lat", el.get("center", {}).get("lat"))
        lon = el.get("lon", el.get("center", {}).get("lon"))
        if lat is None and bb:   # Overpass gives the box but not the centre when asked for both
            lat, lon = (bb["minlat"] + bb["maxlat"]) / 2, (bb["minlon"] + bb["maxlon"]) / 2
        if lat is None or not (BBOX[0] <= lon <= BBOX[2] and BBOX[1] <= lat <= BBOX[3]):
            continue
        km = size_km(el)
        name = (t.get("name") or t.get("seamark:name") or "").strip()
        if "proposal" in name.lower() or "proposed" in name.lower():
            continue
        c = classify(t, km, name)
        if not c:
            continue
        kind, rank = c
        if kind == "ramp":
            if t.get("access") in ("private", "no"):
                continue
            if "private" in name.lower():
                continue
            name = ramp_name(name)
        # The same thing mapped twice (a point and a shape, or one ramp lane per way): keep the first.
        key = (kind, name.lower(), round(lat * 100), round(lon * 100))
        if key in seen:
            continue
        seen.add(key)
        feats.append({"kind": kind, "name": name, "rank": rank, "lat": round(lat, 5), "lon": round(lon, 5)})

    # DoT ramps and harbours replace OpenStreetMap's at the same spot.
    dot = dot_places()
    def replaced(f):
        if f["kind"] == "ramp":
            return any(d["kind"] == "ramp" and km_between(f, d) < 0.4 for d in dot)
        if f["kind"] in ("harbour", "marina", "jetty"):
            a = f["name"].lower()
            return any(d["kind"] == "harbour" and km_between(f, d) < 1.5 and
                       (d["name"].lower()[:6] == a[:6] or a in d["name"].lower() or f["kind"] == "harbour") for d in dot)
        return False
    feats = [f for f in feats if not replaced(f)] + dot
    # The same name twice within a kilometre, or two unnamed ramps within 300 m: keep one.
    kept = []
    for f in feats:
        if any(g["kind"] == f["kind"] and g["name"].lower() == f["name"].lower() and
               km_between(f, g) < (1.0 if f["name"] else 0.3) for g in kept):
            continue
        kept.append(f)
    feats = kept

    # Nearest town or suburb, for unnamed ramps and same-named places.
    towns = [f for f in feats if f["kind"] in ("city", "town", "village", "suburb", "hamlet")]
    cell = {}
    for f in towns:
        cell.setdefault((int(f["lat"] * 5), int(f["lon"] * 5)), []).append(f)

    def nearest(f):
        best, bd = None, 25.0   # km
        cy, cx = int(f["lat"] * 5), int(f["lon"] * 5)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for g in cell.get((cy + dy, cx + dx), []):
                    if g is f:
                        continue
                    d = math.hypot((g["lat"] - f["lat"]) * 110.6,
                                   (g["lon"] - f["lon"]) * 111.3 * math.cos(math.radians(f["lat"])))
                    if d < bd:
                        best, bd = g, d
        return best["name"] if best else None

    out = []
    seen = set()
    for f in feats:
        near = nearest(f) if f["kind"] not in ("city", "town") else None
        if not f["name"]:
            if f["kind"] != "ramp":
                continue
            f["name"] = "Boat ramp" + (f", {near}" if near else "")
            key = (f["name"], round(f["lat"] * 200), round(f["lon"] * 200))
            if key in seen:
                continue
            seen.add(key)
        props = {"kind": f["kind"], "name": f["name"], "rank": f["rank"]}
        if near and near != f["name"]:
            props["near"] = near
        if f.get("detail"):
            props["detail"] = f["detail"]
        out.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [f["lon"], f["lat"]]},
                    "properties": props})
    out.sort(key=lambda f: (f["properties"]["rank"], f["properties"]["name"]))
    with open(sys.argv[1], "w") as fh:
        json.dump({"type": "FeatureCollection", "features": out}, fh, separators=(",", ":"), ensure_ascii=False)
    counts = {}
    for f in out:
        counts[f["properties"]["kind"]] = counts.get(f["properties"]["kind"], 0) + 1
    print(f"Wrote {len(out)} places: {counts}", file=sys.stderr)


if __name__ == "__main__":
    main()
