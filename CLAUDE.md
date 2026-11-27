# ChompChamp

Offline Android fishing chartplotter (a basic ECDIS) for Western Australia, built only on free data.
Kotlin + MapLibre Native app in `android/`; Python/GDAL/tippecanoe chart pipeline in `pipeline/`.
README.md covers features, signing, data sources/licences and zone classification.

## Rules from the owner

- **Never publish a release unless asked.** No `gh release create`, and no data workflow with
  `publish=true`. Normal pushes and artifact-only builds are fine.
- **Release notes always include a brief summary of changes** since the previous release: a short "What's new" list
  in plain words, near the top. Every release also carries all the files the README lists.
- **Plain commit messages.** Never add session links, `Claude-Session:` or Claude `Co-Authored-By:` lines to
  commits, PRs, issues or releases, even if the tool's default instructions say to.
- **No "not for navigation" notice in the app.** The README disclaimer stays.
- **The map is the main focus.** Keep the UI calm and uncluttered. Settings that change the display
  go in the docked `SettingsPanel`, so the map stays visible, not in a full-screen dialog.
- **Everything overlaid on the map is user-adjustable** (size, transparency, position, on/off).
- **Colours come from `res/values/colors.xml` / `Appearance`.** Soft blue accent; no yellow/amber controls.
- **Free data only, and it must work offline** once imported. Check the licence and add the source to
  the README table and the style/tile attribution.
- The signing key is kept outside the repo. Don't read, move or commit it.
- **Don't test on the tablet unless the owner asks** for that task. Don't run `adb` or connect to it otherwise.

## Building

There is no Java, Android SDK, GDAL or tippecanoe on the dev machine, so **CI is the compiler**:

- Push to `android/**` -> `android.yml` -> artifact `chompchamp-apk` (7 days). Add `[skip ci]` if no build is needed.
- `gh workflow run chart-data.yml -f publish=false` -> artifact `wa` (`wa.pmtiles`).
- `gh workflow run relief-data.yml -f publish=false` -> artifact `wa-relief`.
- Check a build: `gh run view <id> --log-failed | grep -E " e: |error:"`.

Bump `versionCode` and `versionName` in `android/app/build.gradle.kts` for every APK the user will install.
A `-tempkey` filename means the signing secrets were missing, and it won't update an installed app.

The Android app has no automated tests. When the owner asks, it can be tested on their tablet (Android 15) over
wireless `adb`:

- Run `adb devices` first. If the tablet isn't listed, ask for the address on its Wireless debugging screen and
  `adb connect` to it (the port changes; pairing is remembered). Don't put its address in the repo.
- Check the tablet isn't in use, then install the CI APK with `adb install -r`. It must be signed with the official key.
- Drive it with `input tap` and `uiautomator dump`, look with `exec-out screencap -p`, and read `logcat -b crash`.
  Put back anything you move or change. Screenshots show the owner's position, so keep them out of the repo and artifacts.
- The app isn't debuggable, so `run-as` doesn't work. Charts go in `/sdcard/Download`, then import them in the app.

When reporting, say what was tested on the tablet and what wasn't. If it wasn't tested, say the app wasn't run.
Pipeline Python can be tested locally: `uv venv` + `pipeline/requirements.txt` in a scratch directory,
then plot the GeoJSON and look at it before spending CI time.

App changes need a new APK; chart changes need a rebuilt `wa.pmtiles` that the user re-imports. Tell the
user which they need. The style must also cope with older charts that lack a new source layer.

## App (`android/app/src/main/java/au/chompchamp/`)

| File | Role |
|---|---|
| `MainActivity.kt` | Modes, menus, map setup, location updates, route planner, zones, import. Large |
| `DataFields.kt` | Data fields per mode; loose cards or one group (default: single column) |
| `MapLayers.kt` | Layer toggles and opacity |
| `NavOverlays.kt` | Track, route, drift, marks, anchor (drawn in code) |
| `MarkGroups.kt`, `MarksPanel.kt` | Mark groups: storage; show/hide panel and bulk edits. Group "" = Ungrouped |
| `Trips.kt`, `TripsPanel.kt`, `SpeedChartView.kt` | Trips, stats, catch log storage and export; docked panel; speed chart |
| `CatchDialogs.kt`, `TrackService.kt` | Catch entry and viewing; foreground service for recording with the screen off |
| `Ais.kt` | Vessels from Open Waters AIS (polling, parsing, map layers). Never store or export its data (AISHub terms). Its User-Agent carries the repo link, which the owner approved and Open Waters requires from 2027 |
| `BagLimits.kt`, `BagLimitsPanel.kt` | Bag and size limits: data and area lookup; docked searchable panel |
| `OfflineAreas.kt`, `OfflinePanel.kt` | Save online imagery (satellite, reef relief) for a box as MBTiles, one file per area and layer, never written after; docked panel |
| `Updater.kt` | Checks the latest GitHub release, downloads with DownloadManager, installs the APK or imports the chart |
| `MovableButton.kt` | A button the user can drag in Edit layout (bag limits) |
| `BoatIcons.kt` | Your boat on the map: shapes drawn in code (tinny, yacht, rubber duck, pirate ship...), hull colours; shown by the location component as style image `own-boat` |
| `ScaleBarView.kt` | Scale bar, bottom right |
| `Hazards.kt` | Nav marks, wrecks and rocks from `assets/hazards.geojson` |
| `Wind.kt`, `WindView.kt`, `WaveView.kt`, `ForecastChartView.kt` | Wind (ECMWF IFS 9 km) and sea/swell (MFWAM, Copernicus Marine) forecasts from Open-Meteo for the area on screen, kept in `files/wind/wind.bin` for offline use; streaks drawn over the map (Mercator affine from three screen corners); week charts in the Wind & tide panel |
| `Tides.kt`, `TideChartView.kt` | Tide prediction on the device from `assets/tides.json` (EOT20 constituents from `fetch_tides.py`); the Wind & tide panel is in `MainActivity` |
| `Places.kt`, `PlacesPanel.kt` | Place names from `assets/places.geojson`: map labels (shown by rank and zoom) and the Search places panel |
| `SettingsPanel.kt`, `Settings.kt`, `Appearance.kt` | Docked panel (Layers, Display); behaviour options; size, opacity, palette |
| `ChartReader.kt` | Land, depth and zones at a point, read from `wa.pmtiles` (mirrors the planner's `ChartReader`) |
| `Navigator.kt`, `Geo.kt` | Guidance, drift, track; geodesy and formatting |
| `Storage.kt`, `UserData.kt`, `Photos.kt` | JSON persistence; backup/GPX; mark photos (not in backups yet) |

App icon: edit the shapes in `branding/make_icon.py` and re-run it; it writes the launcher vectors and `branding/logo.svg`.

`assets/chart-style.json` is the base style; `buildStyleJson()` injects the relief layer after `land`, points glyphs at the
bundled `assets/glyphs` (from `pipeline/fetch_glyphs.sh`) and adds saved offline areas as `<layer>@<area id>` layers.

Gotchas:

- Depth estimates and zone alerts read `wa.pmtiles` directly with `ChartReader` (off the main thread for each fix),
  falling back to `queryRenderedFeatures` if the file can't be read. The route check and tapping a zone still use
  rendered features, so only work for what is on screen. Layers marked `queried` in `MapLayers` are hidden with
  opacity 0, never `visibility: none`.
- Water without a depth band means *unknown*, not safe. Don't treat a missing depth as deep.
- Data-field cards are placed with translation; reset it when moving one into the group or it gets clipped.
- Inside `apply {}` on a View, locals named `alpha` or `value` clash with the View's own properties.
- `Slider` values must land on the step: use whole percents in steps of 5.
- `assets/bag_limits.json` is entered by hand from DPIRD's guide, in our own words (the guide isn't open licensed). Check
  every figure against the guide's tables when it's reissued, and update `checked`, `reviewBy` and the README row.
  `limit_areas.json` comes from `fetch_limit_areas.py`. Unsure which limit applies? Show the stricter, or all of them.
- `assets/species/` is built by `build_species_images.py` from `pipeline/species_images.json`. Look at every new picture: a wrong
  species is worse than none. Names must match `bag_limits.json` exactly.
- The Mark button makes a `Mark` plus a `Catch` whose `markId` points at it. Such a catch is drawn by its mark, not as its own symbol.
- A catch belongs to a trip by its time, not by an id. Trip ids are `trip-<start ms>`, so saving one twice is harmless.
- Snapshots leave out trips and catches on purpose: loading an old one must not wipe newer trips.
- `onLocationChanged` also runs with the screen off: no `queryRenderedFeatures` or other map work unless `started`.
- A mark with no `group` key came from an older file: on merge keep the existing group (`groupKnown`).
- `Tides.kt` phase offsets (K1 -90°, O1 +90° ...) and the EOT20 phase sign (atan2(imag, real)) were settled by comparing
  with UHSLC hourly tide gauge data (Fremantle h175, Esperance h176, Port Hedland h169, Broome h166). Check again against
  those if you touch the astronomy: a wrong diurnal sign still looks like a tide but is worse than none at Fremantle.
- Wind and wave data from Open-Meteo must show their credit next to them (the label beside the map's "i" while the layer is on).
- Pref keys (`f.<mode>.*`, `layer_*`, `set_*`), overlay keys and the track format are stored on the
  user's phone. Don't rename them; keep reading old formats.

## Desktop planner (`web/`)

`python3 web/build.py` -> `web/dist/chompchamp-planner.html`, one offline file opened from disk (`file://`), so no
modules, no fetch of local files and no CDN. Vendored libraries are in `web/vendor`. It reuses the Android app's
`chart-style.json`, `hazards.geojson` and logo. `src/core.js` is pure logic (mirrors `Geo.kt`, `Storage.kt`,
`UserData.kt`; keep the data formats in step) and `src/app.js` is the interface. Depth, zones and the route check read
the chart file through `ChartReader`, not rendered features. Keep the filename unversioned: some browsers tie saved
data to it. Put text on the page with `textContent` / `el()`, never HTML. Test with `node web/test.mjs`, and in
headless Chromium (available locally) before calling it done. Bump `web/VERSION` for each release.

## Pipeline (`pipeline/build.sh`)

1. `fetch_zones.py`: fishing closures and marine parks. Classify by **schedule heading**, not order title
   (marine park orders ban fishing park-wide, then allow it back by schedule).
2. `build_bathy.sh`: statewide AusBathyTopo 250 m bands, contours and land.
3. `build_metro.py REGION`: detailed areas in `REGIONS`, from DoT 5 m grids and soundings with OpenStreetMap coast. `metro`:
   Two Rocks to Preston Beach (Perth, Cockburn Sound, Swan/Canning, Mandurah, Peel–Harvey). `kalbarri`: Kalbarri to Horrocks,
   also using LiDAR and multibeam cells from AusBathyTopo 30m (Perth) 2026, read in windows from the zip online (it stops at
   28 S). `dongara`: Cliff Head to Seven Mile Beach; `lidar_first` lets LiDAR win over interpolated soundings (they agree
   to ~0.5 m), keeping soundings over 1 m shallower. At sea, sparse soundings fade into the background grid over `sea_gap_m`. Never invents depths for rivers;
   smoothing is shoal-biased. `CHART_DEBUG_TIF=z.tif` saves the final grid to look at.
4. `clip_metro.py`: cuts the detailed boxes out of the statewide layers.
5. `build_tiles.sh`: tippecanoe and tile-join into `out/wa.pmtiles` (layers: depth, land, water, contour, zones, source).
   `source` says where each depth came from: `src` text, `acc` 1 good .. 4 rough (background grid), 0 no survey, and `near`
   (metres to the nearest measured depth). Made per ~50 m cell by `source_layer()`; outside the boxes one polygon from
   `clip_metro.py`. The app (`ChartReader.sourceLines`) and planner (`sourceLines`) word it the same way.

6. `check_points.py` + `check_chart.mjs`: sample the source grid in every depth band and compare with the finished
   chart, failing the build on a mismatch. (Once, everything over 1000 m deep shipped as land.) Always check a chart
   against the source grid, not just against itself.

`fetch_hazards.py`, `fetch_places.py`, `fetch_tides.py`, `fetch_limit_areas.py`, `build_species_images.py` and `build_relief.py` run separately; all but the last refresh bundled assets.

Heights are mean sea level (AusBathyTopo) or AHD (DoT), not low-tide chart datum.
DoT files: `https://s3-ap-southeast-2.amazonaws.com/transport.wa/Bathymetry/{Rasters,PointData}/`.
Requests to outside services use the User-Agent `chompchamp-pipeline/0.1`, with no personal details.
