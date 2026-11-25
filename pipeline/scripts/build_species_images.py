#!/usr/bin/env python3
"""Make the small species pictures shown with the bag limits.

pipeline/species_images.json lists each chosen picture with its author, licence and page. Every one was
looked at by eye: a wrong species is worse than none, so leave out anything doubtful.
Only public domain, CC0, CC BY and CC BY-SA. Sources: iNaturalist (research grade) and Wikimedia Commons.

Usage: build_species_images.py species_images.json OUTPUT_DIR      (needs pillow)
"""
import io
import json
import os
import re
import sys
import time
import urllib.request

from PIL import Image, ImageOps

UA = {"User-Agent": "chompchamp-pipeline/0.1"}
ALLOWED = re.compile(r"(Public domain|CC0|CC BY(-SA)? \d\.\d( [a-z]{2})?)")
SIZE = 360


def get(url):
    for attempt in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            err = e
            time.sleep(10 * (attempt + 1))
    raise err


def main(listing, out):
    os.makedirs(out, exist_ok=True)
    cache = os.environ.get("IMAGE_CACHE")
    credits = {}
    for e in json.load(open(listing)):
        if not ALLOWED.fullmatch(e["licence"]):
            sys.exit(f"{e['name']}: licence {e['licence']} isn't allowed")
        slug = re.sub(r"[^a-z0-9]+", "-", e["name"].lower()).strip("-")
        saved = os.path.join(cache, slug) if cache else None
        if saved and os.path.exists(saved):
            data = open(saved, "rb").read()
        else:
            data = get(e["url"])
            time.sleep(2)
            if saved:
                open(saved, "wb").write(data)
        im = ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGBA")
        page = Image.new("RGBA", im.size, "white")
        page.alpha_composite(im)
        im = page.convert("RGB")
        im.thumbnail((SIZE, SIZE))
        im.save(os.path.join(out, slug + ".webp"), quality=72, method=6)
        credits[e["name"]] = {k: e[k] for k in ("species", "by", "licence", "page", "example") if k in e} | {"file": slug}
    with open(os.path.join(out, "credits.json"), "w") as f:
        json.dump(credits, f, ensure_ascii=False, indent=0)
    print(len(credits), "pictures", sum(os.path.getsize(os.path.join(out, x)) for x in os.listdir(out)) // 1024, "KB")


main(sys.argv[1], sys.argv[2])
