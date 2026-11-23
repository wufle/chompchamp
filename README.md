# ChompChamp

A free, offline fishing chartplotter for Android, starting with **Western Australia**.

> ⚠️ **NOT FOR NAVIGATION.** ChompChamp is built from free public data. It is not an
> official nautical chart (those come from the Australian Hydrographic Office). Depths come
> from a 250 m national model and can be badly wrong near reefs, bars and shorelines.
> Fishing zone boundaries may be incomplete or out of date: always check current rules with
> [DPIRD](https://www.dpird.wa.gov.au/) and [Parks Australia](https://parksaustralia.gov.au/marine/).

## What it does (v0.1)

- Offline chart of WA waters: depth shading, depth contours with labels, land
- **No-fishing zones** in red (state sanctuary zones, wreck sites, Commonwealth National Park and Sanctuary zones), restricted areas in orange, marine park boundaries in green
- **Alert when you enter a no-fishing zone** (banner + vibration)
- Tap a zone to see what it is and a link to the legal order
- GPS boat position, follow mode, SOG/COG in knots, lat/lon in degrees and decimal minutes
- Keeps the screen on while open

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

The first run downloads the 2.6 GB AusBathyTopo grid. To just refresh fishing zones:
`python3 pipeline/scripts/fetch_zones.py pipeline/build/zones.geojson`.

## Data sources

| Layer | Source | Licence |
|---|---|---|
| Depth, contours, land | Geoscience Australia, [AusBathyTopo (Australia) 250m 2024](https://pid.geoscience.gov.au/dataset/ga/150050) | © Commonwealth of Australia; check terms before commercial use |
| No-fishing / restricted zones | WA DPIRD Fisheries Guide, consolidated notices and orders (DPIRD-061), via [SLIP](https://public-services.slip.wa.gov.au/public/rest/services/SLIP_Public_Services/DPIRD_Fisheries_Guide/MapServer) | WA Government, check SLIP terms |
| State marine park outlines | WA DBCA Legislated Lands and Waters (DBCA-011) | CC BY-SA 4.0 |
| Commonwealth marine park zones | Parks Australia, [Australian Marine Parks](https://gis.environment.gov.au/gispubmap/rest/services/ogc_services/Australian_Marine_Parks/FeatureServer) | Commonwealth (likely CC BY 4.0, verify) |
| Label fonts (online, cached) | [OpenFreeMap](https://openfreemap.org) | OFL |

**How zones are classified** (`pipeline/scripts/fetch_zones.py`):
- `notake`: DPIRD orders prohibiting recreational fishing or all fishing (marine park sanctuary zones, wreck sites, Rottnest, etc.), plus Commonwealth National Park (IUCN II) and Sanctuary (Ia) zones
- `restricted`: DPIRD fish habitat protection areas and other closures that limit but don't ban rec fishing
- `park`: all other marine park areas (rec fishing generally allowed)
- Commercial-only closures are skipped.

Known limitation: some DPIRD orders, such as Rottnest, mix full no-fishing areas with partial restrictions, so a few zones may show as stricter than they really are.

## Roadmap

- [ ] Satellite imagery layer (online, with offline cache for chosen areas)
- [ ] Higher-resolution nearshore depth (AusSeabed multibeam, WA DoT surveys)
- [ ] Bundle fonts so labels work fully offline on first launch
- [ ] Waypoints, tracks and a catch log
- [ ] Sea surface temperature and chlorophyll overlays (IMOS)
- [ ] Tides (WA Department of Transport tide stations) and marine weather
- [ ] Bag/size limits and seasonal closures by zone
- [ ] Other states
