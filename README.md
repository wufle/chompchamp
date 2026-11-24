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
- **Data fields** per mode: SOG, COG, position, depth, waypoint, BRG, DTW, XTE, VMG, TTG, ETAs, remaining, drift speed/direction, nearest mark, anchor distance, trip log, time. Show/hide them and drag them anywhere (Menu → Edit layout); tap a field while editing to resize or hide just that one. Each mode keeps its own layout.
- **Appearance** (Menu): size and transparency of data fields, and of buttons and panels.
- **Layers** (bottom left): on/off and an opacity slider for every layer: satellite, 5 m reef relief, depth shading, contours, labels, land, zones, parks, route, marks, drift line, track, anchor circle.
- **Data** (Menu): save and load named snapshots on the phone; export and import a backup file (.json) with routes, marks, track and settings (merge or replace); export and import GPX to move marks, routes and tracks to and from other chartplotter apps.
- **Settings**: arrival radius, planning speed, north-up / course-up, drift window and projection, fish-mode zoom, anchor radius, safety depth.

## Repo layout

```
android/     Kotlin app using MapLibre Native (reads a local .pmtiles chart file)
pipeline/    Scripts that build the offline chart file (wa.pmtiles) from public data
.github/     CI: builds the APK on every push; builds the chart data monthly or on demand
```

## Getting it on your phone

1. **Chart data:** download `wa.pmtiles` from the latest *WA chart* Release. To rebuild it, run
   Actions → *Build WA chart data*; it produces a workflow artifact, and only publishes a
   Release if you tick **publish**.
2. **App:** Actions → *Android build* → latest run → download the `chompchamp-apk`
   artifact, unzip it and install `chompchamp-<version>.apk` (allow "install unknown apps").
   New versions install over the old one and keep your routes, marks and settings.
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

## Signing

Every APK must be signed with the same key, or Android refuses to install it over the
existing app. CI reads the key from three repository secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_B64` | base64 of a PKCS12 keystore |
| `ANDROID_KEYSTORE_PASSWORD` | its password (store and key) |
| `ANDROID_KEY_ALIAS` | key alias (`chompchamp`) |

Create them once with `scripts/create-signing-key.sh` (needs `openssl` and `gh`).

Keep a backup of the keystore outside the repo. If it's lost, every phone has to uninstall
and reinstall (losing routes and marks).

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

**How zones are classified** (`pipeline/scripts/fetch_zones.py`): each area is classified by
its own schedule heading, not by the title of the order. Marine park orders typically ban
recreational fishing across the whole park, then allow it back in General Use, Recreation and
Special Purpose areas through a "permitted activities" schedule.
- `notake` (red): sanctuary areas, wreck sites, and single-area "Prohibition on Fishing" orders
  (Burswood Lake, Esperance Jetty, Greens Pool, Hamelin Pool, Bernier Island), plus Commonwealth
  National Park (IUCN II) and Sanctuary (Ia) zones
- `restricted` (orange): recreation, special purpose and protected areas, fish habitat protection
  areas, Abrolhos reef observation areas, gear and netting rules. Fishing is allowed with limits; tap
  the zone and open the order for details
- `park` (green outline): general use areas and other marine park waters
- Commercial-only closures and schedules that only define the whole park are skipped.

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
