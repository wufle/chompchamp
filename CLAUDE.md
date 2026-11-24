# ChompChamp

Offline Android fishing chartplotter (a basic ECDIS) for Western Australia, built only on free data.
Kotlin + MapLibre Native app in `android/`; Python/GDAL/tippecanoe chart pipeline in `pipeline/`.
README.md covers features, signing, data sources/licences and zone classification.

## Rules from the owner

- **Never publish a release unless asked.** No `gh release create`, and no data workflow with
  `publish=true`. Normal pushes and artifact-only builds are fine.
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

## Building

There is no Java, Android SDK, GDAL or tippecanoe on the dev machine, so **CI is the compiler**:

- Push to `android/**` -> `android.yml` -> artifact `chompchamp-apk` (7 days). Add `[skip ci]` if no build is needed.
- `gh workflow run chart-data.yml -f publish=false` -> artifact `wa` (`wa.pmtiles`).
- `gh workflow run relief-data.yml -f publish=false` -> artifact `wa-relief`.
- Check a build: `gh run view <id> --log-failed | grep -E " e: |error:"`.

Bump `versionCode` and `versionName` in `android/app/build.gradle.kts` for every APK the user will install.
A `-tempkey` filename means the signing secrets were missing, and it won't update an installed app.

There are no automated tests. Nothing can be run on a device from here, so say so when reporting.
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
| `Hazards.kt` | Nav marks, wrecks and rocks from `assets/hazards.geojson` |
| `SettingsPanel.kt`, `Settings.kt`, `Appearance.kt` | Docked panel (Layers, Display); behaviour options; size, opacity, palette |
| `Navigator.kt`, `Geo.kt` | Guidance, drift, track; geodesy and formatting |
| `Storage.kt`, `UserData.kt`, `Photos.kt` | JSON persistence; backup/GPX; mark photos (not in backups yet) |

App icon: edit the shapes in `branding/make_icon.py` and re-run it; it writes the launcher vectors and `branding/logo.svg`.

`assets/chart-style.json` is the base style; `buildStyleJson()` injects the relief layer after `land`.

Gotchas:

- Depth estimates, zone alerts and the route check use `queryRenderedFeatures`, so they only work for
  what is on screen. Layers marked `queried` in `MapLayers` are hidden with opacity 0, never `visibility: none`.
- Water without a depth band means *unknown*, not safe. Don't treat a missing depth as deep.
- Data-field cards are placed with translation; reset it when moving one into the group or it gets clipped.
- Inside `apply {}` on a View, locals named `alpha` or `value` clash with the View's own properties.
- `Slider` values must land on the step: use whole percents in steps of 5.
- Pref keys (`f.<mode>.*`, `layer_*`, `set_*`), overlay keys and the track format are stored on the
  user's phone. Don't rename them; keep reading old formats.

## Pipeline (`pipeline/build.sh`)

1. `fetch_zones.py`: fishing closures and marine parks. Classify by **schedule heading**, not order title
   (marine park orders ban fishing park-wide, then allow it back by schedule).
2. `build_bathy.sh`: statewide AusBathyTopo 250 m bands, contours and land.
3. `build_metro.py`: Perth metro, Cockburn Sound and Swan/Canning from DoT 5 m grids and soundings, with
   OpenStreetMap coast. Never invents depths for rivers; smoothing is shoal-biased.
4. `clip_metro.py`: cuts the metro box out of the statewide layers.
5. `build_tiles.sh`: tippecanoe and tile-join into `out/wa.pmtiles` (layers: depth, land, water, contour, zones).

`fetch_hazards.py` and `build_relief.py` run separately; the first refreshes the bundled asset.

Heights are mean sea level (AusBathyTopo) or AHD (DoT), not low-tide chart datum.
DoT files: `https://s3-ap-southeast-2.amazonaws.com/transport.wa/Bathymetry/{Rasters,PointData}/`.
Requests to outside services use the User-Agent `chompchamp-pipeline/0.1`, with no personal details.
