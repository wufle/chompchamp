#!/usr/bin/env python3
"""Download navigation marks and charted hazards for WA as one small GeoJSON file.

The result is bundled with the app (android/app/src/main/assets/hazards.geojson), so it
works offline without re-importing the chart.

Sources:
  * WA Department of Transport - Navigation Aids (DOT-004), CC BY 4.0
    beacons, buoys, cardinal / isolated danger / special marks, lights, leads
  * OpenStreetMap / OpenSeaMap contributors, ODbL 1.0
    wrecks, rocks and obstructions (seamark:type) plus historic=wreck

Every feature gets:
  kind    port | stbd | card_n | card_e | card_s | card_w | danger | special | safe |
          light | lead | other | wreck | rock | obstruction
  name    human readable name (may be empty)
  detail  type description, e.g. "Buoy Spar Cardinal North Lit"
  lit     true when the mark is lit
  src     dot | osm

Usage: fetch_hazards.py OUTPUT.geojson
"""
import json
import sys
import time
import urllib.parse
import urllib.request

BBOX = (112.0, -36.0, 129.5, -13.0)  # same as the chart
NAVAIDS = ("https://public-services.slip.wa.gov.au/public/rest/services/"
           "SLIP_Public_Services/Transport/MapServer/5")
OVERPASS = "https://overpass-api.de/api/interpreter"
UA = {"User-Agent": "chompchamp-pipeline/0.1"}


def fetch(url, data=None, retries=4):
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, data=data, headers=UA)
            with urllib.request.urlopen(req, timeout=240) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            if attempt == retries - 1:
                raise
            print(f"  retry {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(5 * (attempt + 1))


def navaid_kind(desc):
    d = desc.lower()
    if "cardinal north" in d: return "card_n"
    if "cardinal east" in d: return "card_e"
    if "cardinal south" in d: return "card_s"
    if "cardinal west" in d: return "card_w"
    if "isolated danger" in d: return "danger"
    if "mid channel" in d or "mid-channel" in d: return "safe"
    if "port" in d: return "port"
    if "starboard" in d: return "stbd"
    if "special" in d or "yacht" in d: return "special"
    if "lead" in d: return "lead"
    if "light" in d: return "light"
    return "other"


def fetch_navaids():
    out = []
    offset = 0
    while True:
        params = {
            "where": "status IS NULL OR status <> 'REMOVED'",
            "outFields": "navaiddesc,functiondesc,status,owner",
            "outSR": "4326", "f": "geojson",
            "resultOffset": offset, "resultRecordCount": 2000,
        }
        fc = fetch(f"{NAVAIDS}/query?{urllib.parse.urlencode(params)}")
        feats = fc.get("features", [])
        for f in feats:
            p = f.get("properties") or {}
            desc = p.get("functiondesc") or ""
            if not f.get("geometry") or desc in ("", "None") or "aeronautical" in desc.lower():
                continue
            lon, lat = f["geometry"]["coordinates"][:2]
            if not (BBOX[0] <= lon <= BBOX[2] and BBOX[1] <= lat <= BBOX[3]):
                continue
            props = {"kind": navaid_kind(desc), "name": p.get("navaiddesc") or "", "detail": desc,
                     "lit": " lit" in f" {desc.lower()}" or "light" in desc.lower(), "src": "dot"}
            if p.get("owner"):
                props["owner"] = p["owner"]
            if desc.lower().startswith("ais virtual"):
                props["virtual"] = True
            out.append(point(lon, lat, props))
        if len(feats) < 2000:
            break
        offset += len(feats)
    print(f"  DoT navigation aids: {len(out)}", file=sys.stderr)
    return out


def fetch_osm():
    s, w, n, e = BBOX[1], BBOX[0], BBOX[3], BBOX[2]
    q = f"""[out:json][timeout:200];
(nwr["seamark:type"~"^(wreck|rock|obstruction)$"]({s},{w},{n},{e});
 nwr["historic"="wreck"]({s},{w},{n},{e}););
out center tags;"""
    d = fetch(OVERPASS, data=urllib.parse.urlencode({"data": q}).encode())
    out = []
    for el in d.get("elements", []):
        t = el.get("tags", {})
        lat = el.get("lat", el.get("center", {}).get("lat"))
        lon = el.get("lon", el.get("center", {}).get("lon"))
        if lat is None:
            continue
        kind = t.get("seamark:type") or "wreck"
        if kind not in ("wreck", "rock", "obstruction"):
            kind = "wreck"
        detail = []
        cat = t.get(f"seamark:{kind}:category")
        if cat:
            detail.append(cat.replace("_", " "))
        for key, label in (("seamark:" + kind + ":water_level", "water level"),
                           ("wreck:date_sunk", "sunk"), ("scuba_diving:depth", "depth")):
            if t.get(key):
                detail.append(f"{label} {t[key].replace('_', ' ')}")
        out.append(point(lon, lat, {
            "kind": kind, "name": t.get("name") or t.get("seamark:name") or "",
            "detail": ", ".join(detail), "lit": False, "src": "osm"}))
    print(f"  OSM wrecks/rocks/obstructions: {len(out)}", file=sys.stderr)
    return out


def point(lon, lat, props):
    return {"type": "Feature", "geometry": {"type": "Point", "coordinates": [round(lon, 6), round(lat, 6)]},
            "properties": props}


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    feats = fetch_navaids() + fetch_osm()
    with open(sys.argv[1], "w") as fh:
        json.dump({"type": "FeatureCollection", "features": feats}, fh, separators=(",", ":"))
    counts = {}
    for f in feats:
        counts[f["properties"]["kind"]] = counts.get(f["properties"]["kind"], 0) + 1
    print(f"Wrote {len(feats)} features: {counts}", file=sys.stderr)


if __name__ == "__main__":
    main()
