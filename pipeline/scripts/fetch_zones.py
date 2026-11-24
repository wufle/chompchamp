#!/usr/bin/env python3
"""Download WA fishing closures and marine park zones as one GeoJSON file.

Sources (all public ArcGIS REST services, no key needed):
  * WA DPIRD Fisheries Guide - closed waters / marine reserves, wreck sites,
    fish habitat protection areas (DPIRD-061)
  * WA DBCA Legislated Lands and Waters (DBCA-011) - state marine park outlines
  * Parks Australia - Australian Marine Parks zoning (Commonwealth waters)

Every feature gets normalised properties:
  zone_class  notake | restricted | park
  name        human readable name
  detail      extra text (schedule, zone name, ...)
  source      dataset name
  link        URL to the legal instrument when known

Usage: fetch_zones.py OUTPUT.geojson
"""
import json
import sys
import time
import urllib.parse
import urllib.request

WA_BBOX = (108.0, -40.0, 130.0, -10.0)  # generous: includes offshore AMPs

SLIP = "https://public-services.slip.wa.gov.au/public/rest/services/SLIP_Public_Services"
DPIRD = f"{SLIP}/DPIRD_Fisheries_Guide/MapServer"
DBCA_LANDS = f"{SLIP}/Property_and_Planning/MapServer/15"
AMP = "https://gis.environment.gov.au/gispubmap/rest/services/ogc_services/Australian_Marine_Parks/FeatureServer/0"

# DPIRD group layers whose children we pull in.
DPIRD_GROUPS = {
    111: "S43 closed waters / marine reserves",
    242: "S115 fish habitat protection areas",
}

UA = {"User-Agent": "chompchamp-pipeline/0.1"}


def get_json(url, params=None, retries=4):
    if params:
        url = f"{url}?{urllib.parse.urlencode(params)}"
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
                data = json.load(r)
            if isinstance(data, dict) and "error" in data:
                raise RuntimeError(f"{url}: {data['error']}")
            return data
        except Exception as e:  # noqa: BLE001
            if attempt == retries - 1:
                raise
            print(f"  retry {attempt + 1} after error: {e}", file=sys.stderr)
            time.sleep(3 * (attempt + 1))


def query_layer(layer_url, where="1=1", bbox=None, chunk=100):
    """Return all GeoJSON features of a layer, chunked by object id (works on
    MapServers that don't support pagination)."""
    base = {"where": where, "f": "json"}
    if bbox:
        base.update(
            geometry=",".join(map(str, bbox)),
            geometryType="esriGeometryEnvelope",
            inSR="4326",
            spatialRel="esriSpatialRelIntersects",
        )
    ids_resp = get_json(f"{layer_url}/query", {**base, "returnIdsOnly": "true"})
    oid_field = ids_resp.get("objectIdFieldName") or "OBJECTID"
    ids = sorted(ids_resp.get("objectIds") or [])
    features = []
    for i in range(0, len(ids), chunk):
        part = ids[i : i + chunk]
        fc = get_json(
            f"{layer_url}/query",
            {
                "objectIds": ",".join(map(str, part)),
                "outFields": "*",
                "outSR": "4326",
                "geometryPrecision": "6",
                "f": "geojson",
            },
        )
        features.extend(fc.get("features", []))
    print(f"  {layer_url.rsplit('/', 2)[-2:]} -> {len(features)} features ({oid_field})", file=sys.stderr)
    return features


def is_commercial_only(layer_name):
    n = layer_name.lower()
    return "commercial" in n and "recreational" not in n


def classify_dpird(order, heading):
    """Classify one area of a DPIRD order by its schedule heading, not the order title.

    Marine park orders typically ban recreational fishing across the whole park
    (Schedule 1) and then allow it back in General Use / Recreation / Special Purpose
    areas via a "permitted activities" schedule. Only sanctuary areas and wreck sites
    are closed to all recreational fishing. Returns notake | restricted | park | None.
    """
    o = order.lower()
    h = heading.lower()
    if "sanctuary" in h or "wreck" in o or "wreck" in h:
        return "notake"
    if "general use" in h:
        # e.g. Ningaloo: "General Use Areas - must not ... by means of a pointed instrument"
        return "restricted" if "must not" in h else "park"
    # Areas that only define the whole park or list permitted activities.
    if ("marine park" in h or "marine management area" in h or "permitted recreational fishing" in h
            or ("interpretation" in h and "marine park" in o)):
        return None
    # Single-area orders that prohibit all fishing (Burswood Lake, Esperance Jetty,
    # Greens Pool, Hamelin Pool, Bernier Island...).
    blanket = o.startswith("prohibition on fishing (") or o.startswith("prohibiton on fishing (")
    partial = any(w in h for w in ("special purpose", "recreation", "protected area", "schedule 6", "schedule 8"))
    if blanket and not partial:
        return "notake"
    # Recreation / special purpose / protected areas, habitat protection, gear rules...
    return "restricted"


def fetch_dpird():
    out = []
    for group_id, group_label in DPIRD_GROUPS.items():
        group = get_json(f"{DPIRD}/{group_id}", {"f": "json"})
        for sub in group.get("subLayers", []):
            if is_commercial_only(sub["name"]):
                continue  # commercial-only closures don't affect rec anglers
            for f in query_layer(f"{DPIRD}/{sub['id']}"):
                p = f.get("properties") or {}
                order = p.get("name") or sub["name"]
                heading = " - ".join(x for x in (p.get("legal"), p.get("descript")) if x)
                zone_class = classify_dpird(order, heading)
                if zone_class is None:
                    continue
                out.append(feature(
                    f["geometry"], zone_class,
                    name=order,
                    detail=heading,
                    source=f"WA DPIRD Fisheries Guide ({group_label})",
                    link=p.get("link"),
                ))
    return out


def fetch_dbca():
    where = "leg_category IN ('Marine Park','Marine Nature Reserve','Marine Management Area')"
    out = []
    for f in query_layer(DBCA_LANDS, where=where):
        p = f.get("properties") or {}
        out.append(feature(
            f["geometry"], "park",
            name=p.get("leg_name") or "WA marine park",
            detail=p.get("leg_category"),
            source="WA DBCA Legislated Lands and Waters (DBCA-011), CC BY-SA 4.0",
            link=None,
        ))
    return out


def fetch_amp():
    out = []
    for f in query_layer(AMP, bbox=WA_BBOX):
        p = f.get("properties") or {}
        zone = p.get("ZONENAME") or ""
        z = zone.lower()
        # National Park and Sanctuary zones are no-take; Recreational Use,
        # Habitat Protection, Multiple Use and Special Purpose allow rec fishing.
        zone_class = "notake" if ("national park" in z or "sanctuary" in z) else "park"
        out.append(feature(
            f["geometry"], zone_class,
            name=f"{p.get('RESNAME', '')} Marine Park".strip(),
            detail=f"{zone} (IUCN {p.get('ZONEIUCN', '?')})",
            source="Parks Australia - Australian Marine Parks zoning",
            link="https://parksaustralia.gov.au/marine/",
        ))
    return out


def feature(geometry, zone_class, name, detail, source, link):
    props = {"zone_class": zone_class, "name": name, "source": source}
    if detail:
        props["detail"] = detail
    if link:
        props["link"] = link
    return {"type": "Feature", "geometry": geometry, "properties": props}


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    features = []
    for label, fn in (("DPIRD closures", fetch_dpird), ("DBCA marine parks", fetch_dbca), ("Australian Marine Parks", fetch_amp)):
        print(f"Fetching {label}...", file=sys.stderr)
        features.extend(fn())
    features = [f for f in features if f.get("geometry")]
    with open(sys.argv[1], "w") as fh:
        json.dump({"type": "FeatureCollection", "features": features}, fh)
    counts = {}
    for f in features:
        counts[f["properties"]["zone_class"]] = counts.get(f["properties"]["zone_class"], 0) + 1
    print(f"Wrote {len(features)} features to {sys.argv[1]}: {counts}", file=sys.stderr)


if __name__ == "__main__":
    main()
