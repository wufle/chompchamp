#!/usr/bin/env python3
"""Cut the detailed boxes (REGIONS in build_metro.py) out of the statewide 250 m layers (their files replace them).

Usage: clip_metro.py WORKDIR
Rewrites depth / contour / land .geojsonl in place, and writes source.geojsonl: where the depths outside the
detailed boxes come from (they have their own <region>_source.geojsonl).
"""
import json
import os
import sys

import shapely
from shapely.geometry import box, mapping, shape

from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location("build_metro", os.path.join(os.path.dirname(os.path.abspath(__file__)), "build_metro.py"))
bm = module_from_spec(spec)
spec.loader.exec_module(bm)


WA = (112.0, -36.0, 129.5, -13.0)   # as BBOX in build_bathy.sh


def main():
    work = sys.argv[1]
    hole = shapely.union_all([box(*b) for b in bm.BOXES])
    shapely.prepare(hole)
    for name, dim in (("depth", 2), ("contour", 1), ("land", 2)):
        path = os.path.join(work, f"{name}.geojsonl")
        kept = cut = 0
        with open(path) as src, open(path + ".tmp", "w") as out:
            for line in src:
                line = line.strip("\x1e \n")
                if not line:
                    continue
                f = json.loads(line)
                if not f.get("geometry"):
                    continue
                parts = []
                for g in shapely.get_parts(shape(f["geometry"])):
                    if not hole.intersects(box(*g.bounds)):
                        parts.append(g)
                        continue
                    g = shapely.make_valid(g).difference(hole)
                    parts += [p for p in shapely.get_parts(g, return_index=False)
                              if p.geom_type in ("Polygon", "LineString", "MultiPolygon", "MultiLineString")
                              and not p.is_empty and shapely.get_dimensions(p) == dim]
                    cut += 1
                for p in parts:
                    out.write(json.dumps({"type": "Feature", "properties": f.get("properties") or {},
                                          "geometry": mapping(p)}, separators=(",", ":")) + "\n")
                    kept += 1
        os.replace(path + ".tmp", path)
        print(f"{name}: {kept} parts kept, {cut} cut at the detailed boxes", file=sys.stderr)
    # Same wording and accuracy (4: rough, a background grid) as source_layer() in build_metro.py.
    src = {"src": "AusBathyTopo 250 m national grid (Geoscience Australia), compiled from charts and surveys", "acc": 4}
    with open(os.path.join(work, "source.geojsonl"), "w") as out:
        for g in shapely.get_parts(box(*WA).difference(hole)):
            out.write(json.dumps({"type": "Feature", "properties": src, "geometry": mapping(g)}) + "\n")


if __name__ == "__main__":
    main()
