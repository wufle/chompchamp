# ChompChamp

<img src="branding/logo.svg" alt="ChompChamp logo: a goofy blowfish about to chomp a hook" width="120">

A free, offline fishing chartplotter for Android, starting with **Western Australia**.

> ⚠️ **NOT FOR NAVIGATION.** ChompChamp is a hobby project built from free public data. It is not
> an official nautical chart (those come from the Australian Hydrographic Office). Rocks, reefs, bars
> and wrecks are missing, depths can be badly wrong, and depths are measured from about mean sea
> level, so there is **less water than shown at low tide**. Fishing zones may be incomplete or out of
> date: always check the current rules with [DPIRD](https://www.dpird.wa.gov.au/) and
> [Parks Australia](https://parksaustralia.gov.au/marine/). Use it at your own risk.

## Install

You need an Android 8.0 or newer phone and three files from the
[**latest release**](https://github.com/wufle/chompchamp/releases/latest):

| File | What it is | Needed? |
|---|---|---|
| `chompchamp-<version>.apk` | The app | Yes |
| `wa.pmtiles` (about 125 MB) | Offline chart: depths, contours, coast, fishing zones | Yes, or the map is blank |
| `wa-relief.pmtiles` (about 55 MB) | Offline 5 m seabed (bathymetry) imagery for about 30 nearshore areas | Optional. Without it, the app streams the imagery when online |

**1. Install the app.** Open the `.apk` on your phone and allow "install unknown apps" for your
browser or file manager when Android asks. ChompChamp isn't on the Play Store, so Play Protect may
warn you.

**2. Add the chart.** In ChompChamp, tap **☰ → Charts & relief data… → Import chart or relief file…**
and pick `wa.pmtiles`. Don't unzip or rename it.

**3. Add the seabed imagery (optional).** Do the same again with `wa-relief.pmtiles`. The app works out
which file is which.

**4. Allow location** when asked, and open the map once while online so the chart labels are saved for
offline use.

After that the chart, zones, hazards and (if imported) seabed imagery work with no signal. Satellite
imagery always needs internet.

**Updating:** install the new `.apk` over the old one; routes, marks and settings are kept. Import a newer
`wa.pmtiles` the same way and it replaces the old chart. Uninstalling deletes your data, so export it first
(☰ → **Data: save, load, export, import…**). Mark photos aren't included in exports yet.

**Problems?**
- *Blank map:* no chart is imported, or you're outside Western Australia.
- *"App not installed":* the installed copy was signed with a different key (an early or self-built
  version). Export your data, uninstall, then install again.
- *"That isn't a .pmtiles chart file":* you picked a zip or a partial download.

Official builds are signed with this certificate (SHA-256):
`0D:D2:B4:04:B7:83:0C:A0:D3:0F:F4:CD:2A:69:5C:17:57:E0:1A:1B:6F:D2:42:18:CF:38:CB:96:32:F0:D6:46`

## What it does

Four modes (switch with the button at the top of the screen):

- **Chart**: offline chart with depth shading, contours, labels and land. No-fishing zones in red, restricted areas in orange, marine parks in green. Alerts when you enter a no-fishing zone. Tap a zone for details; long-press anywhere for depth, "Navigate here" or "Add mark".
- **Route**: tap to drop waypoints, tap a waypoint to move, rename or delete it. Shows distance and time at your planning speed. Save, open, reverse and delete routes. **Check route** (tick button) samples every leg for land, water shallower than your safety depth, and no-fishing zones.
- **Navigate**: steers you waypoint by waypoint with BRG, DTW, XTE, VMG, TTG, ETA to waypoint and destination, and distance remaining. Auto-advances at the arrival radius or when you pass abeam. Previous, stop and next buttons.
- **Fish**: zooms in close, measures drift over a rolling window and draws where you'll be in N minutes. One-tap **Mark** saves the spot with estimated depth. Add photos to marks (camera or gallery); the latest shows as a bubble over the mark on the map. Anchor alarm, nearest-mark distance/bearing, breadcrumb track.

Everything is customisable:
- **Data fields** per mode: SOG, COG, position, depth, waypoint, BRG, DTW, XTE, VMG, TTG, ETAs, remaining, drift speed/direction, nearest mark, anchor distance, trip log, time. Show/hide them and drag them anywhere (Menu → Edit layout), or combine them into one group (Menu → Group data fields); tap a field while editing to resize or hide just that one. Each mode keeps its own layout.
- **Appearance** (Menu): size and transparency of data fields, and of buttons and panels.
- **Layers** (bottom left): on/off and an opacity slider for every layer: marks and hazards, satellite, 5 m reef relief, depth shading, contours, labels, land, zones, parks, route, marks, drift line, track, anchor circle.
- **Charts & relief data** (Menu): see what is installed (size, date), import, remove the chart or the offline reef relief pack (relief falls back to online), clear the online tile cache.
- **Data** (Menu): save and load named snapshots on the phone; export and import a backup file (.json) with routes, marks, track and settings (merge or replace); export and import GPX to move marks, routes and tracks to and from other chartplotter apps.
- **Settings**: track history shown (1 min to the entire trip), track point spacing, arrival radius, planning speed, north-up / course-up, drift window and projection, fish-mode zoom, anchor radius, safety depth.

## Privacy

No accounts, ads or analytics. Your position, track, marks and photos stay on the phone unless you export
them (or Android's own device backup copies them). When online, the app requests map imagery and label
fonts for the area you're viewing from the WA Government (SLIP), EOX and OpenFreeMap, so those servers see
your IP address and the area on screen.

## Data sources and licences

Each chart layer keeps its source's licence. If you reuse the chart files, follow all of them.
**The marine park outlines are non-commercial**, so don't sell charts that include them.

| Layer | Source | Licence |
|---|---|---|
| Depth, contours, land (statewide, 250 m) | Geoscience Australia, [AusBathyTopo (Australia) 250m 2024](https://pid.geoscience.gov.au/dataset/ga/150050). Incorporates source bathymetry data reproduced under licence by permission of the Australian Hydrographic Office © Commonwealth of Australia 2009-2023 | CC BY 4.0 |
| Depth, contours: Perth metro coast (Two Rocks to Warnbro, Rottnest), Cockburn Sound, Swan and Canning rivers | WA Department of Transport 5 m composite grids (DOT-022) and point soundings (DOT-021). Heights relative to AHD (about mean sea level), not low-tide chart datum | CC BY 4.0 |
| Coastline and river outlines (Perth metro) | © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) | ODbL 1.0 |
| No-fishing / restricted zones | WA DPIRD Fisheries Guide, consolidated notices and orders (DPIRD-061), via [SLIP](https://public-services.slip.wa.gov.au/public/rest/services/SLIP_Public_Services/DPIRD_Fisheries_Guide/MapServer) | CC BY 4.0 |
| State marine park outlines | WA DBCA Legislated Lands and Waters (DBCA-011) | CC BY-NC 4.0 (non-commercial) |
| Commonwealth marine park zones | Australian Marine Parks © Commonwealth of Australia (DCCEEW), via [Parks Australia](https://gis.environment.gov.au/gispubmap/rest/services/ogc_services/Australian_Marine_Parks/FeatureServer) | CC BY 4.0 |
| Reef relief (5 m, about 30 nearshore areas) | WA Department of Transport bathymetry imagery, via [SLIP](https://public-services.slip.wa.gov.au/public/rest/services/Landgate_Public_Maps/WA_Bathymetry/MapServer) | CC BY 4.0 |
| Navigation marks (beacons, buoys, cardinals, lights, leads) | WA Department of Transport, Navigation Aids (DOT-004), via SLIP | CC BY 4.0 |
| Wrecks, rocks, obstructions | © OpenStreetMap contributors (OpenSeaMap seamark tags) | ODbL 1.0 |
| Satellite (online) | [Sentinel-2 cloudless 2016](https://s2maps.eu) by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2016) | CC BY 4.0 |
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

**Marks and hazards** are bundled with the app and aren't a substitute for official charts: isolated rocks
and obstructions known only from hydrographic surveys (AHO ENCs) are not in any free dataset.

## Roadmap

- [ ] More high-detail areas from WA DoT surveys (Perth metro is done)
- [ ] Newer national depth grid (AusBathyTopo 2026), and GA's 30 m Northern Australia grid
- [ ] Depths adjusted to low-tide chart datum
- [ ] Zone alerts and depth that work off screen (point-in-polygon against the data, not rendered tiles)
- [ ] Bundle fonts so labels work fully offline on first launch
- [ ] Offline satellite imagery for chosen areas
- [ ] Mark photos in backups
- [ ] Catch log on marks (species, size, photo, bait)
- [ ] Sea surface temperature and chlorophyll overlays (IMOS)
- [ ] Tides (WA Department of Transport tide stations) and marine weather
- [ ] Bag/size limits and seasonal closures by zone
- [ ] Other states

## For developers

```
android/     Kotlin app using MapLibre Native (reads a local .pmtiles chart file)
pipeline/    Scripts that build the offline chart (wa.pmtiles) and relief pack from public data
branding/    Logo source; make_icon.py writes the launcher icons
.github/     CI: builds the APK on every push to android/; chart and relief on demand
```

**App:** Android Studio, or `cd android && ./gradlew assembleDebug` (JDK 17 + Android SDK). Every push also
builds an APK as a workflow artifact (kept 7 days; downloading needs a GitHub login).

**Chart** (needs Python 3.12+, GDAL, `unzip`, `curl`, and tippecanoe 2.17+ with tile-join):

```sh
python3 -m venv .venv && .venv/bin/pip install -r pipeline/requirements.txt
PY="$PWD/.venv/bin/python" ./pipeline/build.sh      # -> pipeline/out/wa.pmtiles
```

The first run downloads about 3 GB. Outside Perth metro the 250 m grid is smoothed before contouring so
lines aren't stair-stepped, which looks better but adds no real detail.

**Relief pack** (needs `pip install pillow pmtiles`):
`python3 pipeline/scripts/build_relief.py pipeline/out/wa-relief.pmtiles`

**Refresh only the zones:** `python3 pipeline/scripts/fetch_zones.py pipeline/build/zones.geojson`.
**Refresh the bundled marks and hazards:**
`python3 pipeline/scripts/fetch_hazards.py android/app/src/main/assets/hazards.geojson`

The chart and relief can also be built with the *Build WA chart data* and *Build WA reef relief pack*
workflows. They make a workflow artifact, and only publish a release if **publish** is ticked.

### Signing

Every APK must be signed with the same key, or Android refuses to install it over the existing app. CI
reads the key from three repository secrets: `ANDROID_KEYSTORE_B64` (base64 of a PKCS12 keystore),
`ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS`. Create them once with
`scripts/create-signing-key.sh` (needs `openssl` and `gh`), and back up the keystore outside the repo.
Forks don't get the secrets, so their builds use a throwaway key (`-tempkey` in the filename) and can't
update an official install.
