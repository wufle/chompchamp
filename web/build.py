#!/usr/bin/env python3
"""Build the planner as one self-contained HTML file: web/dist/chompchamp-planner.html, and the website in web/dist/site

The map style, hazards and logo come from the Android app's own files, so they can't drift apart.
No network and no dependencies: the libraries and label font are in web/vendor.
"""
import base64
import json
import xml.etree.ElementTree as ET
from pathlib import Path

WEB = Path(__file__).resolve().parent
ROOT = WEB.parent
ASSETS = ROOT / "android/app/src/main/assets"
VERSION = (WEB / "VERSION").read_text().strip()


def text(p):
    return Path(p).read_text(encoding="utf-8")


def script(s):
    """Make JavaScript safe to sit inside an inline <script> tag."""
    return s.replace("</script", "<\\/script").replace("<!--", "<\\!--")


def data(v):
    return json.dumps(v, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")


def icons():
    """The app's own icons (Android vector drawables, 24 x 24): name -> [[path data, even-odd fill]]."""
    ns = "{http://schemas.android.com/apk/res/android}"
    out = {}
    for f in sorted((ROOT / "android/app/src/main/res/drawable").glob("ic_*.xml")):
        if "launcher" in f.stem:
            continue
        paths = [[p.get(ns + "pathData"), p.get(ns + "fillType") == "evenOdd"] for p in ET.parse(f).getroot().iter("path")]
        out[f.stem[3:]] = paths
    return out


def species():
    """Species pictures for the bag limits (about 10 KB each), with their credits."""
    credits = json.loads(text(ASSETS / "species/credits.json"))
    for c in credits.values():
        c["img"] = base64.b64encode((ASSETS / f"species/{c['file']}.webp").read_bytes()).decode()
    return credits


glyphs = {p.stem: base64.b64encode(p.read_bytes()).decode() for p in sorted((WEB / "vendor/glyphs").glob("*.pbf"))}
parts = {
    "VERSION": VERSION,
    "LOGO_B64": base64.b64encode((ROOT / "branding/logo.svg").read_bytes()).decode(),
    # The libraries' licences travel with the file. "--" isn't allowed inside an HTML comment.
    "LICENCES": "\n\n".join(text(p) for p in sorted((WEB / "vendor").glob("LICENSE-*.txt"))).replace("--", "- -"),
    "VENDOR_CSS": text(WEB / "vendor/maplibre-gl.css"),
    "APP_CSS": text(WEB / "src/app.css"),
    "VENDOR_MAPLIBRE": script(text(WEB / "vendor/maplibre-gl.js")),
    "VENDOR_PMTILES": script(text(WEB / "vendor/pmtiles.js")),
    "DATA": "const DATA = " + data({
        "version": VERSION,
        "style": json.loads(text(ASSETS / "chart-style.json")),
        "hazards": json.loads(text(ASSETS / "hazards.geojson")),
        "places": json.loads(text(ASSETS / "places.geojson")),
        "tides": json.loads(text(ASSETS / "tides.json")),
        "bagLimits": json.loads(text(ASSETS / "bag_limits.json")),
        "limitAreas": json.loads(text(ASSETS / "limit_areas.json")),
        "icons": icons(),
        "species": species(),
        "glyphs": glyphs,
    }) + ";",
    "CORE_JS": script(text(WEB / "src/core.js")),
    # The feature modules share app.js's scope: they go where it says %%MODULES%%.
    "APP_JS": script(text(WEB / "src/app.js").replace("// %%MODULES%%", "\n".join(text(WEB / f"src/{m}.js") for m in ("ui", "fields", "gps", "weather", "catches", "marks", "display")))),
}

# Split on the placeholders once, so nothing inside a part is ever treated as a placeholder.
out = []
rest = text(WEB / "src/index.html")
while True:
    head, sep, tail = rest.partition("%%")
    out.append(head)
    if not sep:
        break
    name, _, rest = tail.partition("%%")
    out.append(parts[name])

dist = WEB / "dist"
dist.mkdir(exist_ok=True)
target = dist / "chompchamp-planner.html"
target.write_text("".join(out), encoding="utf-8")
print(f"{target} ({target.stat().st_size / 1e6:.1f} MB)")

# The website (GitHub Pages): the same page as index.html, with what it needs to install and work offline.
import hashlib
import shutil
site = dist / "site"
site.mkdir(exist_ok=True)
page = target.read_bytes()
(site / "index.html").write_bytes(page)
build = VERSION + "-" + hashlib.sha256(page).hexdigest()[:10]
(site / "sw.js").write_text(text(WEB / "src/sw.js").replace("%%BUILD%%", build), encoding="utf-8")
shutil.copy(WEB / "src/manifest.webmanifest", site)
for icon in sorted((WEB / "icons").glob("*.png")):
    shutil.copy(icon, site)
print(f"{site}/ (offline build {build})")
