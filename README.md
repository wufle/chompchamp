# ChompChamp

A free, offline fishing chartplotter for Android, starting with **Western Australia**.

> ⚠️ **NOT FOR NAVIGATION.** ChompChamp is built from free public data. It is not an
> official nautical chart (those come from the Australian Hydrographic Office). Depths come
> from a 250 m national model and can be badly wrong near reefs, bars and shorelines.
> Fishing zone boundaries may be incomplete or out of date: always check current rules with
> [DPIRD](https://www.dpird.wa.gov.au/) and [Parks Australia](https://parksaustralia.gov.au/marine/).

## What it does

Four modes (switch with the button at the top of the screen):

- **Chart**: offline chart with depth shading, contours, labels and land. No-fishing zones in red, restricted areas in orange, marine parks in green. Alerts when you enter a no-fishing zone. Tap a zone for details; long-press anywhere for depth, "Navigate here" or "Add mark".
- **Route**: tap to drop waypoints, tap a waypoint to move, rename or delete it. Shows distance and time at your planning speed. Save, open, reverse and delete routes. **Check** samples every leg for land, water shallower than your safety depth, and no-fishing zones.
- **Navigate**: steers you waypoint by waypoint with BRG, DTW, XTE, VMG, TTG, ETA to waypoint and destination, and distance remaining. Auto-advances at the arrival radius or when you pass abeam. Prev/Skip/Stop controls.
- **Fish**: zooms in close, measures drift over a rolling window and draws where you'll be in N minutes. One-tap **Mark** saves the spot with estimated depth. Anchor alarm, nearest-mark distance/bearing, breadcrumb track.

Everything is customisable:
- **Data fields** per mode: SOG, COG, position, depth, waypoint, BRG, DTW, XTE, VMG, TTG, ETAs, remaining, drift speed/direction, nearest mark, anchor distance, trip log, time. Show/hide them, pick a text size, and drag them anywhere (Menu → Edit layout). Each mode keeps its own layout.
- **Layers** (bottom left): satellite, 5 m reef relief, depth shading, contours, labels, land, zones, parks, track.
- **Settings**: arrival radius, planning speed, north-up / course-up, drift window and projection, fish-mode zoom, anchor radius, safety depth.

## Repo layout

```
android/     Kotlin app using MapLibre Native (reads a local .pmtiles chart file)
pipeline/    Scripts that build the offline chart file (wa.pmtiles) from public data
.github/     CI: builds the APK on every push; builds the chart data monthly or on demand
```

## Getting it on your phone

1. **Chart data:** Actions → *Build WA chart data* → *Run workflow*. When it finishes, a
   new Release appears with `wa.pmtiles` attached. Download it to your phone.
2. **App:** Actions → *Android build* → latest run → download the `chompchamp-debug-apk`
   artifact, unzip it and install `app-debug.apk` (allow "install unknown apps").
3. Open the app, accept the disclaimer, tap **+** and pick `wa.pmtiles`.

## Building locally

App: Android Studio, or `cd android && ./gradlew assembleDebug` (JDK 17 + Android SDK).

Chart data (needs `python3`, GDAL, `unzip`, `curl`, and tippecanoe 2.17+):

```sh
./pipeline/build.sh          # -> pipeline/out/wa.pmtiles
```

The first run downloads the 2.6 GB AusBathyTopo grid. The grid is 250 m; the pipeline smooths it
(2x cubic-spline upsample) before contouring so lines aren't stair-stepped, which looks better
but adds no real detail.

Offline 5 m reef relief pack (needs `pip install pillow pmtiles`):
`python3 pipeline/scripts/build_relief.py pipeline/out/wa-relief.pmtiles`, or run the
*Build WA reef relief pack* workflow. Import it in the app the same way as the chart. To just refresh fishing zones:
`python3 pipeline/scripts/fetch_zones.py pipeline/build/zones.geojson`.

## Data sources

| Layer | Source | Licence |
|---|---|---|
| Depth, contours, land | Geoscience Australia, [AusBathyTopo (Australia) 250m 2024](https://pid.geoscience.gov.au/dataset/ga/150050) | © Commonwealth of Australia; check terms before commercial use |
| No-fishing / restricted zones | WA DPIRD Fisheries Guide, consolidated notices and orders (DPIRD-061), via [SLIP](https://public-services.slip.wa.gov.au/public/rest/services/SLIP_Public_Services/DPIRD_Fisheries_Guide/MapServer) | WA Government, check SLIP terms |
| State marine park outlines | WA DBCA Legislated Lands and Waters (DBCA-011) | CC BY-SA 4.0 |
| Commonwealth marine park zones | Parks Australia, [Australian Marine Parks](https://gis.environment.gov.au/gispubmap/rest/services/ogc_services/Australian_Marine_Parks/FeatureServer) | Commonwealth (likely CC BY 4.0, verify) |
| Reef relief (5 m, ~30 nearshore areas) | WA Department of Transport bathymetry imagery (DOT-022), via [SLIP](https://public-services.slip.wa.gov.au/public/rest/services/Landgate_Public_Maps/WA_Bathymetry/MapServer) | CC BY 3.0 AU |
| Satellite (online) | [Sentinel-2 cloudless 2016](https://s2maps.eu) by EOX IT Services GmbH | CC BY 4.0 |
| Label fonts (online, cached) | [OpenFreeMap](https://openfreemap.org) | OFL |

**How zones are classified** (`pipeline/scripts/fetch_zones.py`):
- `notake`: DPIRD orders prohibiting recreational fishing or all fishing (marine park sanctuary zones, wreck sites, Rottnest, etc.), plus Commonwealth National Park (IUCN II) and Sanctuary (Ia) zones
- `restricted`: DPIRD fish habitat protection areas and other closures that limit but don't ban rec fishing
- `park`: all other marine park areas (rec fishing generally allowed)
- Commercial-only closures are skipped.

Known limitation: some DPIRD orders, such as Rottnest, mix full no-fishing areas with partial restrictions, so a few zones may show as stricter than they really are.

Known limitation: the depth estimate and no-fishing alerts read the map tiles on screen, so they only work while the boat is visible on the map (follow mode keeps it there).

## Roadmap

- [ ] Satellite imagery layer (online, with offline cache for chosen areas)
- [ ] Depth numbers from WA DoT surveys (BAG files) and GA's 30 m Northern Australia grid
- [ ] Zone alerts and depth that work off screen (point-in-polygon against the data, not rendered tiles)
- [ ] Bundle fonts so labels work fully offline on first launch
- [ ] Catch log on marks (species, size, photo, bait)
- [ ] Sea surface temperature and chlorophyll overlays (IMOS)
- [ ] Tides (WA Department of Transport tide stations) and marine weather
- [ ] Bag/size limits and seasonal closures by zone
- [ ] Other states
