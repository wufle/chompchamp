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

You need an Android 8.0 or newer phone and these files from the
[**latest release**](https://github.com/wufle/chompchamp/releases/latest):

| File | What it is | Needed? |
|---|---|---|
| `chompchamp-<version>.apk` | The app | Yes |
| `wa.pmtiles` (about 125 MB) | Offline chart: depths, contours, coast, fishing zones | Yes, or the map is blank |
| `wa-relief.pmtiles` (about 55 MB) | Offline 5 m seabed (bathymetry) imagery for about 30 nearshore areas | Optional. Without it, the app streams the imagery when online |
| `chompchamp-planner.html` | [Desktop planner](#desktop-planner) for your PC | Optional |

**1. Install the app.** Open the `.apk` on your phone and allow "install unknown apps" for your
browser or file manager when Android asks. ChompChamp isn't on the Play Store, so Play Protect may
warn you.

**2. Add the chart.** In ChompChamp, tap **☰ → Charts, offline areas & updates… → Check for updates… → Download…**
and tick the chart. Or download `wa.pmtiles` yourself and pick it with **Import chart or relief file…**.
Don't unzip or rename it.

**3. Add the seabed imagery (optional).** Do the same again with the reef relief pack (`wa-relief.pmtiles`).
The app works out which file is which.

**4. Allow location** when asked.

After that the chart, zones, hazards, labels and (if imported) seabed imagery work with no signal.

**5. Save satellite imagery for your areas (optional).** Tap **☰ → Charts, offline areas & updates… → Save
an area for offline use…**, move the map so your fishing area fills the screen, and tap **Download this area**.
The panel shows the size first. Saved satellite and reef relief imagery shows with no signal whenever those
layers are on.

**Updating:** tap **☰ → Charts, offline areas & updates… → Check for updates…** (or **About → Updates**). It
looks at the latest GitHub release, shows what's new, and downloads the app, chart or relief pack you tick.
Android asks you to allow ChompChamp to install apps the first time, then asks again before each update.
Routes, marks and settings are kept. To be told about updates, set **Settings → Check GitHub for app and chart
updates** to weekly or daily; it's off by default. You can still install a new `.apk` or import a new
`wa.pmtiles` by hand. Uninstalling deletes your data, so export it first (☰ → **Data: save, load, export,
import…**). Photos aren't included in exports yet.

**Problems?**
- *Blank map:* no chart is imported, or you're outside Western Australia.
- *"App not installed":* the installed copy was signed with a different key (an early or self-built
  version). Export your data, uninstall, then install again.
- *"That isn't a .pmtiles chart file":* you picked a zip or a partial download.

Official builds are signed with this certificate (SHA-256):
`0D:D2:B4:04:B7:83:0C:A0:D3:0F:F4:CD:2A:69:5C:17:57:E0:1A:1B:6F:D2:42:18:CF:38:CB:96:32:F0:D6:46`

## What it does

Four modes (switch with the button at the top of the screen):

- **Chart**: offline chart with depth shading, contours, labels and land. No-fishing zones in red, restricted areas in orange, marine parks in green. Alerts when you enter a no-fishing zone. Tap a zone for details; long-press anywhere for depth, "Navigate here" or "Add mark". The long-press also says where that depth came from (which survey, its year and method, or the 250 m national grid), how accurate it is likely to be (good, fair, poor or rough), and how far it is to the nearest measured depth.
- **Route**: tap to drop waypoints, tap a waypoint to move, rename or delete it. Shows distance and time at your planning speed. Save, open, reverse and delete routes. **Check route** (tick button) samples every leg for land, water shallower than your safety depth, and no-fishing zones.
- **Navigate**: steers you waypoint by waypoint with BRG, DTW, XTE, VMG, TTG, ETA to waypoint and destination, and distance remaining. Auto-advances at the arrival radius or when you pass abeam. Previous, stop and next buttons.
- **Fish**: zooms in close, measures drift over a rolling window and draws where you'll be in N minutes. **Mark** saves your catch and the spot together: species, number, length, weight, kept or released, bait, notes and photos, with the time, position and estimated depth filled in. The newest photo shows as a bubble on the map. Sort marks into coloured groups you can show or hide (Menu → Marks), and move or delete many at once. Add more catches or photos (camera or gallery) to a mark by tapping it. Anchor alarm, nearest-mark distance/bearing, breadcrumb track.

**Search places** (Menu): find a town, suburb, harbour, marina, boat ramp, jetty, lighthouse, island, point, bay, beach
or reef by name and go there on the map. Results show the kind of place, the nearest town and how far it is; boat ramps
also show lanes, finger jetties and trailer bays where WA's Department of Transport lists them. Type "ramp" and a town
("ramp mandurah") for that town's ramps. The same names are labelled on the map (Layers → Place names), bigger places
from further out. Works offline.

**Bag & size limits** (fish-in-a-bag button at the top right in Fish mode, or Menu): daily bag, boat and size limits for
the area you're in, with closed seasons, pictures, and a search by species. Tap a picture for a bigger one and a
**Google Images** button that searches the web for more photos of that fish (needs internet). The area follows your position, or choose one. Works
offline. The limits are copied from DPIRD's guide by hand and shortened, and they don't update by themselves, so check the
[current rules](https://www.dpird.wa.gov.au/individuals/recreational-fishing/recreational-fishing-rules/recreational-bag-and-size-limits/).

**Trips & catches** (Menu): a trip records your track with the time and speed at each point. Recording starts when you
tap **Record** at the top left of the map (move it with Menu → Display → Move or resize), start a route, or mark
a catch; set it to start only when you tap Record in Settings. While it records, a red light there slowly flashes next
to the time, and Android shows a notification. Tapping it shows the trip so far. To stop, **press and hold** it, then
choose to save, keep recording or discard the trip, so a knock on the screen can't end a trip. If you turn the
button off (Menu → Display), recording moves to the mode menu and Trips & catches. Saved
trips keep their full track, distance, time under way, average and top speed, catches, and a speed chart you drag along
to see when and where on the map. Export a trip as GPX, or its speed log or catches as CSV. Recording carries on with the
screen off or another app in front (turn that off in Settings to save battery). A trip left with no GPS for longer than
the break in Settings (6 hours) is saved and stopped by itself.

**Vessels (AIS)** (Layers, off by default, needs internet): boats that transmit AIS, from volunteer shore stations, with
name, type, speed, course and how long ago each was heard. No account or receiver needed. Positions run a minute or
more behind, and boats without AIS or out of range of a station are missing. It is for interest, not for avoiding collisions.

Everything is customisable:
- **Data fields** per mode: SOG (knots or km/h), COG, position, depth, waypoint, BRG, DTW, XTE, VMG, TTG, ETAs, remaining, drift speed/direction, nearest mark, anchor distance, trip distance, time, average and top speed, clock. Show/hide them and drag them anywhere (Menu → Display → Move or resize), or combine them into one group (Menu → Display → Arrangement); tap a field while editing to resize or hide just that one. Each mode keeps its own layout.
- **Display** (Menu): the data field options above, the scale bar, Record button and bag limits button on/off (move the buttons with Move or resize), the AIS vessel colour (light green, teal or light blue), plus size and transparency of data fields, and of buttons and panels, and the size of AIS vessels, catches and place names.
- **Layers** (bottom left): on/off and an opacity slider for every layer: marks and hazards, place names, satellite, 5 m reef relief, depth shading, contours, labels, land, zones, parks, route, marks, drift line, track, saved trip, catches, anchor circle, AIS vessels.
- **Charts, offline areas & updates** (Menu): save satellite and reef relief imagery for an area, check GitHub for a new app, chart or relief pack, see what is installed (size, date), import, remove the chart or the offline reef relief pack (relief falls back to online), clear the online tile cache.
- **Data** (Menu): save and load named snapshots on the phone; export and import a backup file (.json) with routes, marks, current track, saved trips, catch log and settings (merge or replace); export and import GPX to move marks, routes and tracks to and from other chartplotter apps.
- **Settings**: when trip recording starts, recording with the screen off, when a forgotten trip is finished, track history shown (1 min to the entire trip), track point spacing, AIS age limit, arrival radius, planning speed, north-up / course-up, drift window and projection, fish-mode zoom, anchor radius, safety depth.

## Desktop planner

Plan on a big screen: a single HTML file for Linux, Windows or Mac that you open in a browser. It reads the same
`wa.pmtiles` chart and works offline once opened. View the chart, depths, zones and hazards (right-click → Depth here and its source), search for places by name, draw and check routes,
keep marks, and swap routes and marks with the phone app using backup or GPX files. No GPS or live navigation.

1. Download `chompchamp-planner.html` and `wa.pmtiles` from the
   [latest release](https://github.com/wufle/chompchamp/releases/latest).
2. Open the HTML file in a browser and choose `wa.pmtiles`. The browser keeps the chart, so you only do this once.
3. To swap routes and marks with your phone, use **Data** in the planner and **☰ → Data** on the phone.

Your routes and marks are saved in that browser only, so export a backup now and then. Keep the file's name and
location when you update it, because some browsers tie your saved data to them. Tested in Chromium and Firefox on Linux.

## Privacy

No accounts, ads or analytics. Your position, trips, catches, marks and photos stay on the phone unless you export
them (or Android's own device backup copies them). When online, the app requests map imagery for the area
you're viewing from the WA Government (SLIP) and Digital Earth Australia (Geoscience Australia), so those servers
see your IP address and the area on screen. Saving an area for offline use downloads from the same servers.
Tapping **Google Images** on a fish opens a Google search in your browser.
Checking for updates asks GitHub for the latest release; it only happens when you ask, unless you turn on
automatic checks. With the AIS layer on, Open Waters also sees your IP address and the area on screen
about once a minute; it keeps request logs for 90 days. Your own position is never sent.

## Data sources and licences

The app and pipeline code is under the [MIT licence](LICENSE). The **data isn't**: each chart layer keeps its
source's licence (including the bundled `hazards.geojson` and `places.geojson`). If you reuse the chart files, follow all of them.
**The marine park outlines are non-commercial**, so don't sell charts that include them.

| Layer | Source | Licence |
|---|---|---|
| Depth, contours, land (statewide, 250 m) | Geoscience Australia, [AusBathyTopo (Australia) 250m 2024](https://pid.geoscience.gov.au/dataset/ga/150050). Incorporates source bathymetry data reproduced under licence by permission of the Australian Hydrographic Office © Commonwealth of Australia 2009-2023 | CC BY 4.0 |
| Depth, contours: the coast from Two Rocks to Preston Beach, Rottnest, Cockburn Sound, the Swan and Canning rivers, Mandurah, the Dawesville Cut and the Peel–Harvey estuary | WA Department of Transport 5 m composite grids (DOT-022) and point soundings (DOT-021). Heights relative to AHD (about mean sea level), not low-tide chart datum | CC BY 4.0 |
| Depth, contours: Kalbarri to Horrocks, including the Murchison River entrance, Port Gregory and Horrocks | WA Department of Transport 5 m composite grid (DOT-022) and point soundings (DOT-021); south of 28° S also the LiDAR and multibeam in Geoscience Australia's [AusBathyTopo 30m (Perth) 2026](https://ecat.ga.gov.au/geonetwork/srv/eng/catalog.search#/metadata/61a348f7-077f-476a-b8a2-b8e3feb2a304), incorporating data © Commonwealth of Australia (AHO) | CC BY 4.0 |
| Coastline, river, estuary and marina outlines (Two Rocks to Preston Beach, Kalbarri to Horrocks) | © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) | ODbL 1.0 |
| No-fishing / restricted zones | WA DPIRD Fisheries Guide, consolidated notices and orders (DPIRD-061), via [SLIP](https://public-services.slip.wa.gov.au/public/rest/services/SLIP_Public_Services/DPIRD_Fisheries_Guide/MapServer) | CC BY 4.0 |
| State marine park outlines | WA DBCA Legislated Lands and Waters (DBCA-011) | CC BY-NC 4.0 (non-commercial) |
| Commonwealth marine park zones | Australian Marine Parks © Commonwealth of Australia (DCCEEW), via [Parks Australia](https://gis.environment.gov.au/gispubmap/rest/services/ogc_services/Australian_Marine_Parks/FeatureServer) | CC BY 4.0 |
| Reef relief (5 m, about 30 nearshore areas) | WA Department of Transport bathymetry imagery, via [SLIP](https://public-services.slip.wa.gov.au/public/rest/services/Landgate_Public_Maps/WA_Bathymetry/MapServer) | CC BY 4.0 |
| Navigation marks (beacons, buoys, cardinals, lights, leads) | WA Department of Transport, Navigation Aids (DOT-004), via SLIP | CC BY 4.0 |
| Wrecks, rocks, obstructions | © OpenStreetMap contributors (OpenSeaMap seamark tags) | ODbL 1.0 |
| Place names: towns, suburbs, harbours, marinas, jetties, lighthouses, islands, points, bays, beaches, reefs | © OpenStreetMap contributors | ODbL 1.0 |
| Boat ramps and DoT maritime facilities (harbours, public jetties) | WA Department of Transport, Public Boat Ramps (DOT-033) and Maritime Facility Locations (DOT-030), via SLIP | CC BY 4.0 |
| Satellite (online, or saved for chosen areas) | © Geoscience Australia, [Digital Earth Australia](https://www.dea.ga.gov.au): coast from [DEA Tidal Composites](https://knowledge.dea.ga.gov.au/data/product/dea-tidal-composites/) (Sentinel-2, low tide, 2024), inland from [DEA GeoMAD](https://knowledge.dea.ga.gov.au/data/product/dea-geometric-median-and-median-absolute-deviation-landsat/) (Landsat 8 and 9, 2025). Contains modified Copernicus Sentinel and USGS Landsat data | CC BY 4.0 |
| Vessels (online, optional layer) | [Open Waters AIS](https://openwaters.io/ais/), around WA passing on AIS from [AISHub](https://www.aishub.net) contributors and [aisstream.io](https://aisstream.io) | Not open data. Free for personal use and display with credit; AISHub's terms don't allow passing on the raw data, so the app never stores or exports it. aisstream.io publishes no licence |
| Bag and size limits | Facts from WA DPIRD's [Recreational fishing guide 2026](https://www.dpird.wa.gov.au/siteassets/documents/fisheries/recreational/guides-and-infographics/recreational-fishing-guide.pdf) (June 2026, edition 2), checked 25 November 2026. The guide is © State of Western Australia and has no open licence, so only the limits, dates and places are used, in our own wording | Not open data |
| Closure areas for bag limits (Abrolhos, Cockburn and Warnbro Sounds, blue swimmer crab) | WA DPIRD Fisheries Guide (DPIRD-061), via SLIP, simplified | CC BY 4.0 |
| Species pictures | [iNaturalist](https://www.inaturalist.org) observers and [Wikimedia Commons](https://commons.wikimedia.org) contributors, resized. Author, licence and page for each are in [species_images.json](pipeline/species_images.json), and in the app when you tap a picture | Public domain, CC0, CC BY or CC BY-SA, by picture |
| Label font (bundled with the app) | Noto Sans, via [OpenFreeMap](https://openfreemap.org) glyphs (`pipeline/fetch_glyphs.sh`) | SIL OFL 1.1 |

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

The depth estimate and no-fishing alerts read the chart file itself, so they work wherever the boat is, on screen or not, and the alerts also vibrate with the screen off while a trip records. The route check still reads the map on screen: it zooms out to fit the route first.

**Marks and hazards** are bundled with the app and aren't a substitute for official charts: isolated rocks
and obstructions known only from hydrographic surveys (AHO ENCs) are not in any free dataset.

## Roadmap

- [ ] More high-detail areas from WA DoT surveys (Two Rocks to Preston Beach and Kalbarri to Horrocks are done)
- [ ] Newer national depth grid (AusBathyTopo 2026), and GA's 30 m Northern Australia grid
- [ ] Depths adjusted to low-tide chart datum
- [x] Bundle fonts so labels work fully offline on first launch
- [x] Zone alerts and depth that work off screen (read from the chart file, not the map on screen)
- [x] Offline satellite imagery for chosen areas
- [x] Update the app and chart from GitHub inside the app
- [ ] Photos in backups
- [ ] Trips and catches in the desktop planner
- [ ] Sea surface temperature and chlorophyll overlays (IMOS)
- [ ] Tides (WA Department of Transport tide stations) and marine weather
- [ ] Bag/size limits and seasonal closures by zone
- [ ] Other states

## For developers

```
android/     Kotlin app using MapLibre Native (reads a local .pmtiles chart file)
pipeline/    Scripts that build the offline chart (wa.pmtiles) and relief pack from public data
web/         Desktop planner: plain JavaScript, built into one HTML file by build.py
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

The first run downloads about 3 GB. Outside the detailed areas (Two Rocks to Preston Beach, Kalbarri to Horrocks) the 250 m grid is smoothed before contouring so
lines aren't stair-stepped, which looks better but adds no real detail.

**Relief pack** (needs `pip install pillow pmtiles`):
`python3 pipeline/scripts/build_relief.py pipeline/out/wa-relief.pmtiles`

**Refresh only the zones:** `python3 pipeline/scripts/fetch_zones.py pipeline/build/zones.geojson`.
**Refresh the bundled marks and hazards:**
`python3 pipeline/scripts/fetch_hazards.py android/app/src/main/assets/hazards.geojson`
**Refresh the bundled place names:**
`python3 pipeline/scripts/fetch_places.py android/app/src/main/assets/places.geojson`

The chart and relief can also be built with the *Build WA chart data* and *Build WA reef relief pack*
workflows. They make a workflow artifact, and only publish a release if **publish** is ticked.

**Desktop planner:** `python3 web/build.py` (no dependencies), tests with `node web/test.mjs`.

### Signing

Every APK must be signed with the same key, or Android refuses to install it over the existing app. CI
reads the key from three repository secrets: `ANDROID_KEYSTORE_B64` (base64 of a PKCS12 keystore),
`ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_ALIAS`. Create them once with
`scripts/create-signing-key.sh` (needs `openssl` and `gh`), and back up the keystore outside the repo.
Forks don't get the secrets, so their builds use a throwaway key (`-tempkey` in the filename) and can't
update an official install.
