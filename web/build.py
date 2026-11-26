#!/usr/bin/env python3
"""Build the desktop planner as one self-contained HTML file: web/dist/chompchamp-planner.html

The map style, hazards and logo come from the Android app's own files, so they can't drift apart.
No network and no dependencies: the libraries and label font are in web/vendor.
"""
import base64
import json
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
        "glyphs": glyphs,
    }) + ";",
    "CORE_JS": script(text(WEB / "src/core.js")),
    "APP_JS": script(text(WEB / "src/app.js")),
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
