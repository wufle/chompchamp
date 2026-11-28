// ChompChamp Planner: the phone app's chartplotter in a browser. Everything runs from this one file.
(() => {
  'use strict';
  const C = Core;
  const $ = (id) => document.getElementById(id);
  /** el('div.cls', {props}, children...). Text always goes in as text, never as HTML. */
  function el(spec, props = {}, ...kids) {
    const [tag, ...cls] = spec.split('.');
    const e = document.createElement(tag);
    if (cls.length) e.className = cls.join(' ');
    for (const [k, v] of Object.entries(props)) {
      if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (k === 'dataset') Object.assign(e.dataset, v);
      else if (k === 'style') e.style.cssText = v;
      else if (k in e && k !== 'list') e[k] = v;
      else e.setAttribute(k, v);
    }
    for (const c of kids.flat()) if (c !== null && c !== undefined && c !== false) e.append(c);
    return e;
  }

  /** Touch screen with no mouse (no hover readout, hold for the menu). */
  const TOUCH = matchMedia('(hover: none) and (pointer: coarse)').matches;
  const HOVER = matchMedia('(hover: hover)').matches;
  const PHONE = matchMedia('(max-width: 700px)').matches;
  const IOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  if (TOUCH) document.body.classList.add('touch');

  // ---- State (kept in this browser) ----------------------------------------------------
  const KEY = 'chompchamp.planner.v1';
  const state = { routes: [], plan: C.emptyRoute(), marks: [], groups: [], track: [], catches: [], trips: [], layers: {}, view: null,
    settings: {}, look: {}, fields: {}, mode: 'chart', nav: null, modeAfterNav: 'chart', recording: false, recSince: 0, anchor: null,
    allCatches: false, shownTrip: null, gps: false };
  let storageOk = true;
  try {
    const s = JSON.parse(localStorage.getItem(KEY) || '{}');
    const safe = (f, d) => { try { return f(); } catch (e) { return d; } };
    state.routes = (s.routes || []).map((r) => safe(() => C.cleanRoute(r))).filter(Boolean);
    state.marks = (s.marks || []).map((m) => safe(() => C.cleanMark(m))).filter(Boolean);
    state.groups = (s.groups || []).map((g) => safe(() => C.cleanGroup(g))).filter(Boolean);
    state.catches = (s.catches || []).map((c) => safe(() => C.cleanCatch(c))).filter(Boolean);
    state.trips = (s.trips || []).map((t) => safe(() => C.cleanTrip(t))).filter(Boolean);
    if (s.plan) state.plan = safe(() => C.cleanRoute(s.plan), state.plan);
    state.track = (Array.isArray(s.track) ? s.track : []).filter((p) => p && isFinite(p.lat) && isFinite(p.lon)).map((p) => ({ lat: p.lat, lon: p.lon, t: p.t || 0, sog: p.sog >= 0 ? p.sog : -1 }));
    for (const k of ['layers', 'view', 'settings', 'look', 'fields', 'mode', 'nav', 'modeAfterNav', 'recording', 'recSince', 'anchor', 'allCatches', 'shownTrip', 'gps']) {
      if (s[k] !== undefined && s[k] !== null) state[k] = s[k];
    }
    // From planner 0.3: its settings lived elsewhere.
    const d = s.display || {};
    if (s.speed && state.settings.plan_kn === undefined) state.settings.plan_kn = s.speed;
    if (s.safety && state.settings.safety_m === undefined) state.settings.safety_m = s.safety;
    if (d.anchorR && state.settings.anchor_m === undefined) state.settings.anchor_m = [20, 30, 50, 75, 100, 150].reduce((a, b) => (Math.abs(b - d.anchorR) < Math.abs(a - d.anchorR) ? b : a));
    if (d.gps !== undefined && s.gps === undefined) state.gps = !!d.gps;
    if (d.sound === false && state.settings.sound === undefined) state.settings.sound = 0;
    if (d.awake === false && state.settings.awake === undefined) state.settings.awake = 0;
    if (state.anchor && !(isFinite(state.anchor.lat) && isFinite(state.anchor.lon))) state.anchor = null;
    if (!['chart', 'route', 'nav', 'fish'].includes(state.mode)) state.mode = 'chart';
  } catch (e) { storageOk = false; }
  C.tidyGroups(state.groups, state.marks);
  // Sizes and transparency of everything over the map (Appearance.kt); the Display panel sets them.
  const KNOBS = [['field_scale', 0.6, 2, 1], ['field_alpha', 0, 1, 0.7], ['control_scale', 0.7, 1.6, 1], ['control_alpha', 0.25, 1, 0.85],
    ['catch_scale', 0.5, 2, 1], ['place_scale', 0.6, 2, 1], ['boat_scale', 0.5, 2.5, 1], ['boat_alpha', 0.3, 1, 1]];
  const LOOK_DEFAULTS = { ...Object.fromEntries(KNOBS.map((k) => [k[0], k[3]])), boat_shape: 'arrow', boat_colour: 'natural', scale_bar: true, bag_button: true, rec_button: true };
  const look = () => ({ ...LOOK_DEFAULTS, ...state.look });

  let saveTimer = 0;
  function save() { clearTimeout(saveTimer); saveTimer = setTimeout(saveNow, 250); }
  function saveNow() {
    clearTimeout(saveTimer);
    try { localStorage.setItem(KEY, JSON.stringify(state)); storageOk = true; } catch (e) {
      if (storageOk) toast("This browser won't save your work. Export a backup (☰ → Data) before closing.");
      storageOk = false;
    }
  }
  addEventListener('pagehide', saveNow);

  // ---- Chart files: kept in IndexedDB so they only need choosing once ------------------------
  const idb = {
    open: () => new Promise((ok, no) => {
      const r = indexedDB.open('chompchamp-planner', 1);
      r.onupgradeneeded = () => r.result.createObjectStore('files');
      r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error);
    }),
    async run(mode, fn) {
      const db = await idb.open();
      try {
        return await new Promise((ok, no) => {
          const tx = db.transaction('files', mode), q = fn(tx.objectStore('files'));
          tx.oncomplete = () => ok(q.result); tx.onerror = tx.onabort = () => no(tx.error);
        });
      } finally { db.close(); }
    },
    get: (k) => idb.run('readonly', (s) => s.get(k)),
    put: (k, v) => idb.run('readwrite', (s) => s.put(v, k)),
    del: (k) => idb.run('readwrite', (s) => s.delete(k)),
  };

  const protocol = new pmtiles.Protocol();
  maplibregl.addProtocol('pmtiles', protocol.tile);
  // Label glyphs are built in, so labels work offline.
  const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0)).buffer;
  maplibregl.addProtocol('ccglyph', async (req) => {
    const range = /\/(\d+-\d+)$/.exec(req.url);
    const g = range && DATA.glyphs[range[1]];
    return { data: g ? unb64(g) : new ArrayBuffer(0) };
  });

  class BlobSource {
    constructor(blob, key) { this.blob = blob; this.key = key; }
    getKey() { return this.key; }
    async getBytes(offset, length) { return { data: await this.blob.slice(offset, offset + length).arrayBuffer() }; }
  }
  const files = { chart: null, relief: null };   // { name, size, archive, key, header }
  let reader = null, serial = 0;

  async function useFile(blob, name) {
    const key = `f${++serial}`;
    const archive = new pmtiles.PMTiles(new BlobSource(blob, key));
    let header;
    try { header = await archive.getHeader(); } catch (e) { throw new Error("that isn't a .pmtiles chart file (if it's a zip, unzip it first)"); }
    const kind = header.tileType === 1 ? 'chart' : 'relief';
    protocol.add(archive);
    files[kind] = { name, size: blob.size, archive, key, header };
    if (kind === 'chart') {
      reader = new C.ChartReader(async (z, x, y) => { const t = await archive.getZxy(z, x, y); return t && t.data; }, header.maxZoom);
      const r0 = reader;
      archive.getMetadata().then((m) => { r0.hasSource = (m.vector_layers || []).some((l) => l.id === 'source'); }, () => {});
    }
    return kind;
  }
  async function openChartFile(file) {
    const kind = await useFile(file, file.name);
    let kept = true;
    try { await idb.put(kind, { name: file.name, blob: file }); } catch (e) { kept = false; }
    rebuildStyle(); chartChanged();
    if (kind === 'chart') {
      const h = files.chart.header, c = map.getCenter();
      if (c.lng < h.minLon || c.lng > h.maxLon || c.lat < h.minLat || c.lat > h.maxLat) map.jumpTo({ center: [115.6, -32.0], zoom: 8 });
    }
    toast((kind === 'chart' ? 'Chart imported' : 'Reef relief imported') + (kept ? '' : ". This browser couldn't keep it, so you'll need to open it again next time."));
  }
  const chartChanged = () => { $('welcome').hidden = !!files.chart; windChanged(); };

  // ---- Layers (MapLayers.kt: the same names, defaults and opacities) ------------------------------------------------------------------------
  const L = (id, prop, base) => ({ id, prop, base });
  const LAYERS = [
    ['Chart', [
      ['satellite', 'Satellite', false, [L('satellite-land', 'raster-opacity', 1), L('satellite', 'raster-opacity', 1)]],
      ['relief', 'Reef relief (5 m)', true, [L('relief', 'raster-opacity', 0.9)]],
      ['depth', 'Depth shading', true, [L('water-fill', 'fill-opacity', 1), L('depth-fill', 'fill-opacity', 1)]],
      ['contours', 'Contours', true, [L('contour', 'line-opacity', 0.8)]],
      ['contour_labels', 'Depth labels', true, [L('contour-label', 'text-opacity', 1)]],
      ['land', 'Land', true, [L('land', 'fill-opacity', 1)]],
      ['hazards', 'Marks & hazards', true, [L('hazards', 'icon-opacity', 1), L('hazards-label', 'text-opacity', 1)]],
      ['places', 'Place names', true, [L('places', 'text-opacity', 1), L('places', 'icon-opacity', 1)]],
    ]],
    ['Zones', [
      ['notake', 'No fishing', true, [L('zones-notake-fill', 'fill-opacity', 0.22), L('zones-notake-line', 'line-opacity', 1), L('zones-notake-label', 'text-opacity', 1)]],
      ['restricted', 'Restricted', true, [L('zones-restricted-fill', 'fill-opacity', 0.15), L('zones-restricted-line', 'line-opacity', 1)]],
      ['parks', 'Marine parks', true, [L('zones-park-fill', 'fill-opacity', 0.06), L('zones-park-line', 'line-opacity', 1)]],
    ]],
    ['My data', [
      ['route', 'Route', true, [L('route-line', 'line-opacity', 1), L('nav-line', 'line-opacity', 1), L('route-mid', 'circle-opacity', 0.6), L('route-wpts', 'circle-opacity', 1),
        L('route-wpts', 'circle-stroke-opacity', 1), L('route-labels', 'text-opacity', 1)]],
      ['marks', 'Marks', true, [L('marks', 'circle-opacity', 1), L('marks', 'circle-stroke-opacity', 1), L('marks-label', 'text-opacity', 1)]],
      ['drift', 'Drift line', true, [L('drift-line', 'line-opacity', 1), L('drift-end', 'circle-opacity', 1), L('drift-end', 'circle-stroke-opacity', 1)]],
      ['track', 'Track', true, [L('track-line', 'line-opacity', 0.75)]],
      ['saved_trip', 'Saved trip', true, [L('trip-line', 'line-opacity', 0.9), L('trip-dots', 'circle-opacity', 1), L('trip-dots', 'circle-stroke-opacity', 1)]],
      ['catches', 'Catches', true, [L('catches', 'icon-opacity', 1)]],
      ['anchor', 'Anchor circle', true, [L('anchor-fill', 'fill-opacity', 0.12), L('anchor-line', 'line-opacity', 1)]],
    ]],
    ['Live (needs internet)', [['wind', 'Wind (forecast)', false, []], ['waves', 'Waves (forecast)', false, []]]],
  ];
  const layerState = (key, def) => ({ on: def, pct: 100, ...(state.layers[key] || {}) });

  function applyLayers() {
    // How much satellite shows: on but at 0% is the same as off.
    const satState = layerState('satellite', false);
    const sat = satState.on ? satState.pct / 100 : 0;
    for (const [, group] of LAYERS) for (const [key, , def, parts] of group) {
      const s = layerState(key, def);
      // As satellite fades in, depth shading fades to 40% and the coarse land polygons fade out.
      const show = s.on && !(key === 'land' && sat >= 1);
      const f = (s.pct / 100) * (key === 'depth' ? 1 - 0.6 * sat : key === 'land' ? 1 - sat : 1);
      for (const p of parts) {
        if (!map.getLayer(p.id)) continue;
        map.setLayoutProperty(p.id, 'visibility', show ? 'visible' : 'none');
        map.setPaintProperty(p.id, p.prop, p.base * f);
      }
    }
  }

  const RELIEF_ONLINE = 'https://public-services.slip.wa.gov.au/public/rest/services/Landgate_Public_Maps/WA_Bathymetry/MapServer/export' +
    '?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true&layers=show:2&f=image';
  const MAGENTA = '#d81b60', FONT = ['Noto Sans Regular'];
  const fc = (features) => ({ type: 'FeatureCollection', features });
  const line = (pts) => ({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: pts.map((p) => [p.lon, p.lat]) } });
  const point = (p, properties) => ({ type: 'Feature', properties, geometry: { type: 'Point', coordinates: [p.lon, p.lat] } });
  const placeSize = (k) => ['*', k, ['match', ['get', 'kind'], 'city', 16, 'town', 13.5, ['village', 'island', 'bay', 'water'], 12, 'suburb', 11.5, ['beach', 'jetty'], 10.5, 11]];

  function buildStyle() {
    const s = JSON.parse(JSON.stringify(DATA.style));
    s.glyphs = 'ccglyph://{fontstack}/{range}';
    const att = s.sources.chart.attribution;
    const tiles = (f) => ({ tiles: [`pmtiles://${f.key}/{z}/{x}/{y}`], minzoom: f.header.minZoom, maxzoom: f.header.maxZoom,
      bounds: [f.header.minLon, f.header.minLat, f.header.maxLon, f.header.maxLat] });
    const layers = [];
    for (const l of s.layers) {
      if (l.source === 'chart' && !files.chart) continue;
      layers.push(l);
      if (l.id === 'land') layers.push({ id: 'relief', type: 'raster', source: 'relief', paint: { 'raster-opacity': 0.9 } });
    }
    if (files.chart) s.sources.chart = { type: 'vector', ...tiles(files.chart), attribution: att }; else delete s.sources.chart;
    if (files.chart) {
      const credit = 'Bathymetry imagery © WA Department of Transport (CC BY 4.0)';
      s.sources.relief = files.relief ? { type: 'raster', tileSize: 256, ...tiles(files.relief), attribution: credit }
        : { type: 'raster', tileSize: 256, tiles: [RELIEF_ONLINE], minzoom: 10, maxzoom: 18, bounds: [112, -36, 129.5, -13], attribution: credit };
    }
    s.sources.hazards = { type: 'geojson', data: DATA.hazards, attribution: 'Marks © WA Department of Transport (CC BY 4.0), © OpenStreetMap contributors' };
    s.sources.places = { type: 'geojson', data: DATA.places, attribution: 'Places © OpenStreetMap contributors, boat ramps © WA Department of Transport (CC BY 4.0)' };
    for (const k of ['track', 'trip', 'trip-dots', 'route-line', 'route-mid', 'route-wpts', 'nav-line', 'drift', 'drift-end', 'marks', 'issue', 'place-hit', 'catches', 'anchor', 'boat', 'boat-acc']) {
      s.sources[k] = { type: 'geojson', data: fc([]) };
    }
    const halo = { 'text-halo-color': '#ffffff', 'text-halo-width': 1.5 };
    const BLUE = '#1B4F72', NAVY = '#0D47A1', POINTS = ['ramp', 'harbour', 'marina', 'jetty', 'lighthouse'];
    const rank = ['to-number', ['get', 'rank']], Lk = look();
    const active = ['==', ['get', 'active'], true];
    layers.push(
      // Place names, like Places.kt: bigger places from further out (rank 1 from zoom 5, rank 6 from 13.5).
      { id: 'places', type: 'symbol', source: 'places', minzoom: 5,
        filter: ['step', ['zoom'], ['<=', rank, 1], 7, ['<=', rank, 2], 9, ['<=', rank, 3], 10.5, ['<=', rank, 4], 12, ['<=', rank, 5], 13.5, true],
        layout: { 'text-field': ['get', 'name'], 'text-font': FONT, 'text-max-width': 8, 'symbol-sort-key': rank, 'text-size': placeSize(Lk.place_scale),
          'text-letter-spacing': ['match', ['get', 'kind'], 'city', 0.04, 'town', 0.02, 0],
          'icon-image': ['match', ['get', 'kind'], 'ramp', 'pl-ramp', ['harbour', 'marina'], 'pl-harbour', ''], 'icon-optional': true,
          'text-anchor': ['match', ['get', 'kind'], POINTS, 'left', 'center'],
          'text-offset': ['match', ['get', 'kind'], POINTS, ['literal', [0.9, 0]], ['literal', [0, 0]]] },
        paint: { 'text-color': ['match', ['get', 'kind'], ['bay', 'water', 'reef', 'bank'], BLUE, ['ramp', 'harbour', 'marina'], NAVY, '#263238'],
          'text-halo-color': '#ffffff', 'text-halo-width': 1.4 } },
      { id: 'hazards', type: 'symbol', source: 'hazards', minzoom: 9, layout: { 'icon-image': ['concat', 'hz-', ['get', 'kind']],
        'icon-size': ['interpolate', ['linear'], ['zoom'], 9, 0.55, 12, 0.8, 15, 1], 'icon-allow-overlap': true, 'icon-ignore-placement': true } },
      { id: 'hazards-label', type: 'symbol', source: 'hazards', minzoom: 14.5, filter: ['!=', ['get', 'name'], ''], layout: { 'text-field': ['get', 'name'],
        'text-font': FONT, 'text-size': 10.5, 'text-anchor': 'top', 'text-offset': [0, 1.1], 'text-max-width': 8 }, paint: { 'text-color': '#263238', ...halo } },
      // NavOverlays.kt, in the same order.
      { id: 'anchor-fill', type: 'fill', source: 'anchor', paint: { 'fill-color': '#1565c0', 'fill-opacity': 0.12 } },
      { id: 'anchor-line', type: 'line', source: 'anchor', paint: { 'line-color': '#1565c0', 'line-width': 2 } },
      { id: 'trip-line', type: 'line', source: 'trip', layout: { 'line-cap': 'round' }, paint: { 'line-color': '#1e88e5', 'line-width': 2.5, 'line-opacity': 0.9 } },
      { id: 'track-line', type: 'line', source: 'track', paint: { 'line-color': '#7b1fa2', 'line-width': 2, 'line-opacity': 0.75 } },
      { id: 'route-line', type: 'line', source: 'route-line', layout: { 'line-cap': 'round' }, paint: { 'line-color': MAGENTA, 'line-width': 3 } },
      { id: 'nav-line', type: 'line', source: 'nav-line', paint: { 'line-color': MAGENTA, 'line-width': 2, 'line-dasharray': [2, 2] } },
      { id: 'drift-line', type: 'line', source: 'drift', paint: { 'line-color': '#ef6c00', 'line-width': 3, 'line-dasharray': [1.5, 1] } },
      { id: 'drift-end', type: 'circle', source: 'drift-end', paint: { 'circle-radius': 5, 'circle-color': '#ef6c00', 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 1.5 } },
      { id: 'route-mid', type: 'circle', source: 'route-mid', paint: { 'circle-radius': 5, 'circle-color': '#ffffff', 'circle-opacity': 0.6, 'circle-stroke-color': MAGENTA, 'circle-stroke-width': 1 } },
      { id: 'route-wpts', type: 'circle', source: 'route-wpts', paint: { 'circle-radius': ['case', active, 10, 7], 'circle-color': ['case', active, MAGENTA, '#ffffff'],
        'circle-stroke-color': MAGENTA, 'circle-stroke-width': 3 } },
      { id: 'route-labels', type: 'symbol', source: 'route-wpts', layout: { 'text-field': ['get', 'label'], 'text-font': FONT, 'text-size': 12,
        'text-offset': [0, -1.6], 'text-allow-overlap': true, 'text-ignore-placement': true }, paint: { 'text-color': MAGENTA, ...halo } },
      { id: 'trip-dots', type: 'circle', source: 'trip-dots', paint: { 'circle-radius': ['to-number', ['get', 'r']], 'circle-color': ['to-color', ['get', 'color']],
        'circle-stroke-color': '#ffffff', 'circle-stroke-width': 2 } },
      { id: 'catches', type: 'symbol', source: 'catches', layout: { 'icon-image': 'catch', 'icon-size': Lk.catch_scale, 'icon-allow-overlap': true, 'icon-ignore-placement': true } },
      { id: 'marks', type: 'circle', source: 'marks', paint: { 'circle-radius': 7, 'circle-color': ['to-color', ['get', 'color'], '#fdd835'], 'circle-stroke-color': '#4e342e', 'circle-stroke-width': 2 } },
      { id: 'marks-label', type: 'symbol', source: 'marks', layout: { 'text-field': ['get', 'name'], 'text-font': FONT, 'text-size': 11,
        'text-offset': [0, 1.5] }, paint: { 'text-color': '#4e342e', ...halo } },
      { id: 'boat-acc', type: 'fill', source: 'boat-acc', paint: { 'fill-color': '#1565C0', 'fill-opacity': 0.12 } },
      { id: 'boat', type: 'symbol', source: 'boat', layout: { 'icon-image': 'own-boat', 'icon-rotate': ['get', 'rot'], 'icon-rotation-alignment': 'map',
        'icon-allow-overlap': true, 'icon-ignore-placement': true } },
      { id: 'place-hit-ring', type: 'circle', source: 'place-hit', paint: { 'circle-radius': 14, 'circle-opacity': 0, 'circle-stroke-color': NAVY, 'circle-stroke-width': 3 } },
      { id: 'place-hit-label', type: 'symbol', source: 'place-hit', layout: { 'text-field': ['get', 'name'], 'text-font': FONT, 'text-size': 13,
        'text-anchor': 'top', 'text-offset': [0, 1.5], 'text-max-width': 10, 'text-allow-overlap': true, 'text-ignore-placement': true },
        paint: { 'text-color': NAVY, 'text-halo-color': '#ffffff', 'text-halo-width': 1.8 } },
      { id: 'issue', type: 'circle', source: 'issue', paint: { 'circle-radius': 16, 'circle-color': '#C62828', 'circle-opacity': 0.12, 'circle-stroke-color': '#C62828', 'circle-stroke-width': 2.5 } },
    );
    s.layers = layers;
    return s;
  }

  // ---- Map -------------------------------------------------------------------------------------
  const v = state.view;
  const map = new maplibregl.Map({
    container: 'map', style: buildStyle(), attributionControl: false, maxZoom: 18, dragRotate: false, pitchWithRotate: false,
    center: v && isFinite(v.lon) && isFinite(v.lat) ? [v.lon, v.lat] : [115.6, -32.0], zoom: v && isFinite(v.zoom) ? v.zoom : 8,
  });
  map.touchZoomRotate.disableRotation();
  map.keyboard.disableRotation();
  map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-left');
  // Show the data credits for a few seconds, then fold them into the (i) button.
  setTimeout(() => { const d = document.querySelector('.maplibregl-ctrl-attrib'); if (d) { d.classList.remove('maplibregl-compact-show'); d.removeAttribute('open'); } }, 6000);
  map.on('styleimagemissing', (e) => {
    if (map.hasImage(e.id)) return;
    if (e.id.startsWith('hz-')) map.addImage(e.id, hazardIcon(e.id.slice(3)), { pixelRatio: 2 });
    if (e.id.startsWith('pl-')) map.addImage(e.id, placeIcon(e.id === 'pl-ramp'), { pixelRatio: 2 });
    if (e.id === 'catch') map.addImage('catch', catchIcon(), { pixelRatio: 2 });
    if (e.id === 'own-boat') updateBoatIcon();
  });
  map.on('style.load', () => { applyLayers(); updateBoatIcon(); drawData(); });
  map.on('moveend', () => { const c = map.getCenter(); state.view = { lon: c.lng, lat: c.lat, zoom: map.getZoom() }; save(); });
  const rebuildStyle = () => map.setStyle(buildStyle(), { diff: false });

  function setData(id, features) { const s = map.getSource(id); if (s) s.setData(fc(features)); }
  function drawData() {
    drawRoute();
    const groups = Object.fromEntries(state.groups.map((g) => [g.name, g]));
    setData('marks', visibleMarks().map((m) => point(m, { id: m.id, name: m.name, color: groups[m.group]?.color || '#FDD835' })));
    drawTrack(); drawCatches(); drawBoat(); drawNav();
    const t = state.trips.find((x) => x.id === state.shownTrip);
    if (t) showTripOnMap(t, tripPoints(t.id), false); else if (state.shownTrip) showTripOnMap(null, [], false);
  }
  /** The plan in Route mode, or the route being followed with the waypoint steered to. */
  function drawRoute() {
    const r = state.mode === 'route' ? state.plan : nav.active ? nav.route : null, w = r ? r.waypoints : [];
    const activeI = state.mode === 'route' ? -1 : nav.index;
    setData('route-line', w.length > 1 ? [line(w)] : []);
    setData('route-wpts', w.map((p, i) => point(p, { i, label: p.name || String(i + 1), active: i === activeI })));
    // With a mouse, a small dot halfway along each leg: drag it to bend the leg.
    setData('route-mid', state.mode === 'route' && HOVER ? w.slice(1).map((p, i) => point({ lat: (p.lat + w[i].lat) / 2, lon: (p.lon + w[i].lon) / 2 }, { i })) : []);
  }

  /** A little fish on a round badge (NavOverlays.catchIcon). 24 units at 2x. */
  function catchIcon() {
    const cv = Object.assign(document.createElement('canvas'), { width: 48, height: 48 }), c = cv.getContext('2d');
    c.scale(2, 2);
    const m = 12;
    c.fillStyle = '#ffffff'; c.beginPath(); c.arc(m, m, 11, 0, 7); c.fill();
    c.fillStyle = '#00838F'; c.beginPath(); c.arc(m, m, 9.5, 0, 7); c.fill();
    c.fillStyle = '#ffffff'; c.beginPath(); c.ellipse(m - 1.5, m, 5, 3.5, 0, 0, 7); c.fill();
    c.beginPath(); c.moveTo(m + 2, m); c.lineTo(m + 6.5, m - 3.5); c.lineTo(m + 6.5, m + 3.5); c.closePath(); c.fill();
    c.fillStyle = '#00838F'; c.beginPath(); c.arc(m - 4, m - 0.8, 0.9, 0, 7); c.fill();
    return c.getImageData(0, 0, 48, 48);
  }
  /** Boat ramp: a slope on a white disc. Harbour: an anchor-like hook on dark blue. Like Places.kt, 16 units at 2x. */
  function placeIcon(ramp) {
    const cv = Object.assign(document.createElement('canvas'), { width: 32, height: 32 }), c = cv.getContext('2d');
    c.scale(2, 2); c.lineJoin = c.lineCap = 'round';
    const m = 8, NAVY = '#0D47A1';
    c.beginPath(); c.arc(m, m, m - 1, 0, 7);
    c.fillStyle = ramp ? '#ffffff' : NAVY; c.fill();
    c.strokeStyle = ramp ? NAVY : '#ffffff'; c.lineWidth = ramp ? 1.6 : 1.4;
    if (ramp) {
      c.stroke();
      c.beginPath(); c.moveTo(m - 4, m + 3); c.lineTo(m + 4, m - 3); c.moveTo(m - 4, m + 3); c.lineTo(m + 4, m + 3); c.stroke();
    } else {
      c.beginPath(); c.moveTo(m, m - 4); c.lineTo(m, m + 4); c.moveTo(m - 2.5, m - 2); c.lineTo(m + 2.5, m - 2); c.stroke();
      c.beginPath(); c.ellipse(m, m + 1.6, 4, 2.6, 0, 0.35, 2.8); c.stroke();
      c.beginPath(); c.arc(m, m - 4.6, 1.1, 0, 7); c.fillStyle = '#ffffff'; c.fill();
    }
    return c.getImageData(0, 0, 32, 32);
  }
  /** Simplified IALA region A symbols, drawn like Hazards.kt. 22 units square at 2x. */
  function hazardIcon(kind) {
    const n = 44, cv = Object.assign(document.createElement('canvas'), { width: n, height: n }), c = cv.getContext('2d');
    c.scale(2, 2); c.lineJoin = c.lineCap = 'round';
    const K = '#111111', Y = '#FDD835', RED = '#D32F2F', W = '#ffffff', m = 11;
    const circ = (x, y, r) => { c.beginPath(); c.arc(x, y, r, 0, 7); };
    const fill = (col) => { c.fillStyle = col; c.fill(); };
    const stroke = (col, w) => { c.strokeStyle = col; c.lineWidth = w; c.stroke(); };
    const poly = (...p) => { c.beginPath(); p.forEach(([x, y], i) => (i ? c.lineTo(x, y) : c.moveTo(x, y))); c.closePath(); };
    const tri = (cx, top, h, up) => (up ? poly([cx, top], [cx + h, top + h * 1.7], [cx - h, top + h * 1.7]) : poly([cx - h, top], [cx + h, top], [cx, top + h * 1.7]));
    const segs = (list) => { for (const [col, w] of [[W, 4], [K, 1.8]]) { c.beginPath(); list.forEach(([a, b, x, y]) => { c.moveTo(m + a, m + b); c.lineTo(m + x, m + y); }); stroke(col, w); } };
    if (kind === 'port') { c.beginPath(); c.rect(m - 5, m - 6, 10, 12); fill(RED); stroke(W, 2.5); stroke(K, 1); }
    else if (kind === 'stbd') { tri(m, m - 7, 7, true); fill('#2E7D32'); stroke(W, 2.5); stroke(K, 1); }
    else if (kind.startsWith('card_')) {
      const d = kind[5];
      const bands = { n: [K, Y], s: [Y, K], e: [K, Y, K], w: [Y, K, Y] }[d] || [K, Y], h = 11 / bands.length;
      bands.forEach((col, i) => { c.fillStyle = col; c.fillRect(m - 3.5, m - 1 + i * h, 7, h); });
      c.beginPath(); c.rect(m - 3.5, m - 1, 7, 11); stroke(W, 1);
      const [a, b] = { n: [1, 1], s: [0, 0], e: [1, 0], w: [0, 1] }[d] || [1, 1];
      tri(m, 0, 3.2, a); fill(K); tri(m, m - 5.8, 3.2, b); fill(K);
    } else if (kind === 'danger') {
      circ(m, m + 3, 5.5); fill(K); c.fillStyle = RED; c.fillRect(m - 5.5, m + 1.8, 11, 2.4);
      circ(m, m + 3, 5.5); stroke(W, 1); circ(m, m - 7, 1.8); fill(K); circ(m, m - 3, 1.8); fill(K);
    } else if (kind === 'special') {
      circ(m, m, 6); fill(Y); stroke(K, 1); c.beginPath(); c.moveTo(m - 3, m - 3); c.lineTo(m + 3, m + 3); c.moveTo(m + 3, m - 3); c.lineTo(m - 3, m + 3); stroke(K, 1.6);
    } else if (kind === 'safe') { circ(m, m, 6); fill(W); c.save(); c.clip(); c.fillStyle = RED; c.fillRect(m - 2, 0, 4, 22); c.restore(); circ(m, m, 6); stroke(RED, 1.2); }
    else if (kind === 'light') {
      c.beginPath(); c.moveTo(m, m + 1); c.bezierCurveTo(m + 8, m - 4, m + 4, m - 10, m, m - 10); c.bezierCurveTo(m - 4, m - 10, m - 8, m - 4, m, m + 1);
      fill('#C2185B'); stroke(W, 1); circ(m, m + 2, 2.5); fill(K);
    } else if (kind === 'lead') { poly([m, m - 5], [m + 5, m], [m, m + 5], [m - 5, m]); fill('#EF6C00'); stroke(W, 1); }
    else if (kind === 'wreck') segs([[-8, 0, 8, 0], [-4, -4, -4, 4], [0, -5, 0, 5], [4, -4, 4, 4]]);
    else if (kind === 'rock') segs([[-6, 0, 6, 0], [0, -6, 0, 6], [-4, -4, 4, 4], [4, -4, -4, 4]]);
    else if (kind === 'obstruction') { circ(m, m, 7); fill('rgba(61,139,214,.33)'); c.setLineDash([2, 2]); stroke(K, 1.4); }
    else { circ(m, m, 4.5); fill('#B0BEC5'); stroke(K, 1); }
    return c.getImageData(0, 0, n, n);
  }
  const HAZARD_TEXT = {
    port: 'Port lateral mark (keep to port when entering harbour)', stbd: 'Starboard lateral mark (keep to starboard when entering harbour)',
    card_n: 'North cardinal: safe water to the NORTH', card_e: 'East cardinal: safe water to the EAST',
    card_s: 'South cardinal: safe water to the SOUTH', card_w: 'West cardinal: safe water to the WEST',
    danger: 'Isolated danger: hazard at the mark, safe water around it', special: 'Special mark (zone, cable, outfall, racing etc.)',
    safe: 'Safe water / mid-channel mark', light: 'Light', lead: 'Leading mark: line up front and rear to follow the channel',
    wreck: 'Wreck', rock: 'Rock', obstruction: 'Obstruction',
  };

  // ---- Files in and out ---------------------------------------------------------------------------------------------
  function pickFile(accept, fn) {
    const f = $('file');
    f.value = ''; f.accept = accept;
    f.onchange = () => { if (f.files[0]) fn(f.files[0]); };
    f.click();
  }
  async function download(name, text, type) {
    // On a phone, the share sheet is the way to save to Files or send it.
    if (TOUCH && navigator.canShare) {
      const file = new File([text], name, { type: 'text/plain' });
      if (navigator.canShare({ files: [file] })) {
        try { await navigator.share({ files: [file] }); return; } catch (e) { if (e.name === 'AbortError') return; }
      }
    }
    const a = el('a', { href: URL.createObjectURL(new Blob([text], { type })), download: name });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
    toast('Exported');
  }
  const p2 = (n) => String(n).padStart(2, '0');
  const stampOf = (t) => { const d = new Date(t); return `${d.getFullYear()}${p2(d.getMonth() + 1)}${p2(d.getDate())}-${p2(d.getHours())}${p2(d.getMinutes())}`; };
  const stamp = () => stampOf(Date.now());
  const mb = (n) => `${Math.round(n / 1e6)} MB`;
  // iOS greys out files it has no type for, and it has none for .pmtiles.
  const CHART_ACCEPT = IOS ? '' : '.pmtiles,application/octet-stream,application/vnd.pmtiles';
  const chooseChart = () => pickFile(CHART_ACCEPT, (f) => openAny(f));
  async function removeFile(kind) {
    if (!await confirmBox(null, `Remove the ${kind === 'chart' ? 'chart' : 'reef relief'} from this browser? Your original file isn't touched.`, 'Remove')) return;
    files[kind] = null; if (kind === 'chart') reader = null;
    try { await idb.del(kind); } catch (e) { /* nothing stored */ }
    rebuildStyle(); chartChanged();
  }
  async function openAny(file) {
    try {
      const head = new Uint8Array(await file.slice(0, 7).arrayBuffer());
      if (String.fromCharCode(...head) === 'PMTiles') { toast('Importing chart…'); await openChartFile(file); }
      else if (file.size > 64e6 || head[0] === 0x50) throw new Error("that isn't a chart, backup or GPX file (if it's a zip, unzip it first)");
      else await importText(await file.text(), file.name);
    } catch (e) { dialog(null, `Import failed: ${e.message}`); }
  }
  /** Import a backup or GPX file the way the phone does: choose what to bring in, then merge or replace. */
  async function importText(text, name) {
    const t = text.trimStart(), isGpx = !t.startsWith('{');
    if (isGpx && !/<gpx/i.test(t)) throw new Error('not a ChompChamp backup or GPX file');
    const b = isGpx ? C.parseGpx(new DOMParser().parseFromString(t, 'application/xml')) : C.parseBackup(t);
    const describe = { routes: `${b.routes.length} route(s)${b.plan && b.plan.waypoints.length ? ' + current plan' : ''}`, marks: `${b.marks.length} mark(s)`,
      track: `${b.track.length} track point(s)`, trips: `${(b.trips || []).length} trip(s), ${b.catches.length} catch(es)` };
    const available = [['routes', 'Routes'], ['marks', 'Marks'], ['track', 'Track'], ['trips', 'Saved trips & catch log']].filter(([k]) =>
      (k === 'routes' && (b.routes.length || (b.plan && b.plan.waypoints.length))) || (k === 'marks' && b.marks.length) || (k === 'track' && b.track.length) || (k === 'trips' && ((b.trips || []).length || b.catches.length)));
    if (!available.length) throw new Error('nothing to import in it');
    const parts = await pickParts(isGpx ? 'Import GPX' : 'Import backup', available, available.map((p) => p[0]), (p) => `${p[1]}: ${describe[p[0]]}`);
    if (!parts) return;
    if (isGpx && parts.includes('marks')) {
      // Keeping imports in their own group makes them easy to hide or delete again.
      const named = new Set(b.marks.map((m) => m.group).filter(Boolean)).size;
      const target = await pickGroup(`Put ${b.marks.length} marks in`, name.replace(/\.[^.]*$/, '').replace(/[_-]+/g, ' ').trim(), named ? `Groups from the file (${named})` : null);
      if (target === null) return;
      if (target !== undefined) b.marks.forEach((m) => { m.group = target; });
      applyBundle(b, parts, true);
    } else if (isGpx) applyBundle(b, parts, true);
    else {
      const how = await dialog('Merge or replace?', ["Merge adds the imported items to what's already in this browser.", 'Replace deletes the selected kinds of data here first.'],
        [['Replace', 'replace', 'neutral'], ['Cancel', null], ['Merge', 'merge', 'primary']]);
      if (how) applyBundle(b, parts, how === 'merge');
    }
  }
  function applyBundle(b, parts, merge) {
    const has = (k) => parts.includes(k);
    if (has('routes')) {
      state.routes = C.mergeById(merge ? state.routes : [], b.routes);
      if (!merge) state.plan = b.plan || C.emptyRoute();
      else if (b.plan && b.plan.waypoints.length && !state.plan.waypoints.length) state.plan = b.plan;
    }
    if (has('marks')) {
      state.marks = C.mergeMarks(merge ? state.marks : [], b.marks);
      if (!merge) state.groups = [];
      for (const g of b.groups) if (!C.findGroup(state.groups, g.name)) state.groups.push(g);
    }
    if (has('trips')) {
      if (!merge) { state.trips.forEach((t) => { try { localStorage.removeItem(TRIP_KEY + t.id); } catch (e) { /* gone */ } }); state.trips = []; state.shownTrip = null; }
      for (const { trip, points } of b.trips || []) saveTrip(trip, points);
      state.catches = C.mergeById(merge ? state.catches : [], b.catches);
    }
    if (has('track') && b.track.length) {
      // Merging: an imported track is its own saved trip. The same trip again keeps the longer one.
      if (merge || state.recording) {
        for (const { trip, points } of C.archiveTrack(b.track, null, true, () => true)) {
          const old = state.trips.find((x) => x.id === trip.id);
          if (!old || old.n < trip.n) saveTrip(trip, points);
        }
      } else state.track = b.track;
    }
    C.tidyGroups(state.groups, state.marks);
    undo.length = 0; save(); drawData(); refreshModePanel(); refreshMarks(); refreshTrips();
    toast(`Imported: ${parts.map((p) => ({ routes: `${b.routes.length} route(s)`, marks: `${b.marks.length} mark(s)`, track: `${b.track.length} track point(s)`, trips: `${(b.trips || []).length} trip(s), ${b.catches.length} catch(es)` }[p])).join(', ')}`);
    fit([...(has('marks') ? b.marks : []), ...(has('routes') ? b.routes.flatMap((r) => r.waypoints) : [])]);
  }
  function fit(pts) {
    if (!pts.length) return;
    unfollow();
    const b = new maplibregl.LngLatBounds();
    pts.forEach((p) => b.extend([p.lon, p.lat]));
    map.fitBounds(b, { padding: { top: 90, bottom: 160, left: 70, right: 70 }, maxZoom: 14, duration: 500 });
  }

  // ---- Route planner (Route mode) -------------------------------------------------------------------------------------------
  const undo = [];
  const snapshot = () => { undo.push(JSON.stringify(state.plan.waypoints)); if (undo.length > 200) undo.shift(); };
  function planChanged() { setData('issue', []); save(); drawRoute(); refreshModePanel(); refreshWaypoints(); }
  function undoLast() { if (undo.length) { state.plan.waypoints = JSON.parse(undo.pop()); planChanged(); } else if (state.plan.waypoints.length) { state.plan.waypoints.pop(); planChanged(); } }
  function setRouteTool(on) { map.getCanvas().style.cursor = on ? 'crosshair' : ''; map.doubleClickZoom[on ? 'disable' : 'enable'](); }
  function routeSummary() {
    const w = state.plan.waypoints, n = w.length;
    if (movingWaypoint !== null) return `Tap the new position for waypoint ${movingWaypoint + 1}`;
    if (!n) return HOVER ? 'Click the map to add waypoints. Click a waypoint to edit it, or drag it.' : 'Tap the map to add waypoints. Tap a waypoint to edit it.';
    const len = C.routeLengthNm(w);
    return `${state.plan.name || 'Unsaved route'} · ${n} waypoint${n === 1 ? '' : 's'} · ${C.fmtNm(len)} · ${C.fmtDuration(len / setting('plan_kn'))} @ ${setting('plan_kn')} kn`;
  }
  function routeTap(p, pt) {
    if (movingWaypoint !== null) {
      const w = state.plan.waypoints[movingWaypoint];
      if (w) { snapshot(); w.lat = p.lat; w.lon = p.lon; }
      movingWaypoint = null; planChanged(); return;
    }
    const hitW = hit(pt, ['route-wpts']);
    if (hitW) { waypointMenu(hitW.properties.i); return; }
    snapshot();
    state.plan.waypoints.push({ lat: p.lat, lon: p.lon, name: `WP${state.plan.waypoints.length + 1}` });
    planChanged();
  }
  async function waypointMenu(i) {
    const w = state.plan.waypoints[i], prev = state.plan.waypoints[i - 1];
    if (!w) return;
    const leg = prev ? `\nLeg from ${prev.name || `WP${i}`}: ${C.fmtNm(C.distanceNm(prev, w))} @ ${C.fmtBearing(C.bearing(prev, w))}` : '';
    const a = await dialog(`${w.name || `WP${i + 1}`}  (${i + 1} of ${state.plan.waypoints.length})`, `${C.fmtPos(w)}${leg}`,
      [['Rename', 'ren', 'neutral'], ['Delete', 'del'], ['Move', 'move', 'primary']]);
    if (a === 'move') { movingWaypoint = i; refreshModePanel(); }
    if (a === 'ren') { const n = await askText('Rename waypoint', w.name); if (n !== null) { snapshot(); w.name = n.slice(0, 60); planChanged(); } }
    if (a === 'del') { snapshot(); state.plan.waypoints.splice(i, 1); planChanged(); }
  }
  async function showRoutesMenu(anchor) {
    const has = state.plan.waypoints.length > 0, saved = state.routes.length > 0;
    menuGuard = Date.now() + 300;
    popup(anchor, [
      has ? ['Save route as…', async () => {
        const name = await askText('Route name', state.plan.name || `Route ${state.routes.length + 1}`);
        if (!name) return;
        const i = state.routes.findIndex((r) => r.name === name);
        state.plan.name = name;
        // A new name saves a copy; the same name replaces that route.
        if (i < 0) state.plan.id = C.uuid();
        const copy = C.cleanRoute({ ...state.plan, id: i >= 0 ? state.routes[i].id : state.plan.id });
        if (i >= 0) { state.routes[i] = copy; state.plan.id = copy.id; } else state.routes.push(copy);
        planChanged(); toast(`Saved "${name}"`);
      }] : null,
      saved ? ['Open saved route…', async () => { const r = await pickRoute('Open route'); if (r) { state.plan = C.cleanRoute(r); undo.length = 0; planChanged(); zoomToRoute(state.plan); } }] : null,
      has ? ['Reverse route', () => { snapshot(); state.plan.waypoints.reverse(); planChanged(); }] : null,
      ['New route', async () => { if (await confirmBox(null, 'Start a new route? Unsaved changes are lost.', 'New route')) { state.plan = C.emptyRoute(); undo.length = 0; planChanged(); } }],
      saved ? ['Delete saved route…', async () => { const r = await pickRoute('Delete route'); if (r) { state.routes = state.routes.filter((x) => x.id !== r.id); save(); toast(`Deleted "${r.name}"`); } }] : null,
      has ? ['Waypoint list…', showWaypoints] : null,
    ]);
  }
  async function pickRoute(title) {
    if (!state.routes.length) { toast('No saved routes'); return null; }
    const i = await pickList(title, state.routes.map((r) => `${r.name || 'Unnamed route'} · ${r.waypoints.length} wpts · ${C.fmtNm(C.routeLengthNm(r.waypoints))}`));
    return i === null ? null : state.routes[i];
  }
  function zoomToRoute(r) { if (r.waypoints.length > 1) fit(r.waypoints); }

  /** Waypoints with their legs, to rename or delete (the planner on a computer). */
  const WAYPOINTS = 'Waypoints';
  const refreshWaypoints = () => { if (panel.current === WAYPOINTS) showWaypoints(); };
  function showWaypoints() {
    panel.show(WAYPOINTS, (rows) => {
      const w = state.plan.waypoints;
      rows.note(routeSummary());
      if (!w.length) rows.note('No waypoints yet.');
      let run = 0;
      w.forEach((p, i) => {
        rows.view(el('div.wrow', {}, el('button.num', { type: 'button', title: 'Show on map', onclick: () => { unfollow(); map.easeTo({ center: [p.lon, p.lat] }); } }, String(i + 1)),
          el('input.line.bare', { type: 'text', value: p.name, placeholder: `WP${i + 1}`, ariaLabel: `Name of waypoint ${i + 1}`, onchange: (e) => { snapshot(); p.name = e.target.value.trim().slice(0, 60); planChanged(); } }),
          el('button.x', { type: 'button', title: 'Delete waypoint', ariaLabel: `Delete waypoint ${i + 1}`, onclick: () => { snapshot(); w.splice(i, 1); planChanged(); } }, icon('close'))));
        if (i + 1 < w.length) {
          const d = C.distanceNm(p, w[i + 1]); run += d;
          rows.view(el('div.leg', {}, `↓ ${C.fmtNm(d)} · ${C.fmtBearing(C.bearing(p, w[i + 1]))} · ${C.fmtDuration(d / setting('plan_kn'))} · total ${C.fmtNm(run)}`));
        }
      });
    });
  }

  /** Sample each leg against the chart for land, shallow water, hazards and no-fishing zones. */
  let checking = null;
  async function checkRoute() {
    if (state.plan.waypoints.length < 2) { toast('Add at least two waypoints'); return; }
    if (!reader) { toast('No chart loaded. Tap ☰ → Charts & updates to import wa.pmtiles.'); return; }
    if (checking) checking.stop = true;
    const job = { stop: false };
    checking = job;
    zoomToRoute(state.plan);
    const status = el('p.ptext', {}, 'Checking route…');
    panel.show('Route check', (rows) => rows.view(status), { onClose: () => { job.stop = true; setData('issue', []); } });
    let res;
    try {
      res = await C.checkRoute(reader, state.plan.waypoints.map((w) => ({ ...w })), setting('safety_m'), DATA.hazards.features, {
        onProgress: (p) => { if (checking === job) status.textContent = `Checking route… ${Math.round(p * 100)}%`; }, cancelled: () => job.stop });
    } catch (e) { status.textContent = `Check failed: ${e.message}`; return; }
    if (job.stop || checking !== job) return;
    checking = null;
    panel.show('Route check', (rows) => {
      if (!res.length) rows.text(`No land, shallow water (< ${setting('safety_m')} m), charted hazards or no-fishing zones found on the route.`);
      for (const x of res) rows.view(el('button.issue', { type: 'button', onclick: () => { setData('issue', [point(x.at, {})]); unfollow(); map.easeTo({ center: [x.at.lon, x.at.lat], zoom: Math.max(map.getZoom(), 14) }); } },
        el(`span.tag.${x.level}`, {}, { stop: '⛔', warn: '⚠', info: 'ℹ' }[x.level]), el('span', {}, x.text)));
      rows.note('Depths are survey data around Perth and a 250 m model elsewhere, measured from about mean sea level: expect less water at low tide. Small reefs, rocks and bars can be missed. Check official charts.');
    }, { onClose: () => setData('issue', []) });
  }

  // ---- Map taps, holds and the mouse -------------------------------------------------------------------------------------------
  const ll = (e) => ({ lat: e.lngLat.lat, lon: e.lngLat.lng });
  const hit = (pt, layers, r = TOUCH ? 24 : 12) => {
    const have = layers.filter((l) => map.getLayer(l));
    return have.length ? map.queryRenderedFeatures([[pt.x - r, pt.y - r], [pt.x + r, pt.y + r]], { layers: have })[0] : null;
  };
  let drag = null, swallowClick = false;
  const startDrag = (e, r) => {
    if (state.mode !== 'route' || movingWaypoint !== null) return false;
    const w = hit(e.point, ['route-wpts'], r), m = w ? null : hit(e.point, ['route-mid'], r);
    if (!w && !m) return false;
    e.preventDefault();   // don't pan
    drag = { i: w ? w.properties.i : m.properties.i + 1, insert: !w, from: e.point, moved: false };
    return true;
  };
  const dragTo = (e) => {
    if (!drag) return false;
    if (!drag.moved) {
      if (Math.hypot(e.point.x - drag.from.x, e.point.y - drag.from.y) < 6) return true;
      snapshot(); drag.moved = true;
      if (drag.insert) state.plan.waypoints.splice(drag.i, 0, { ...ll(e), name: `WP${state.plan.waypoints.length + 1}` });
    }
    Object.assign(state.plan.waypoints[drag.i], ll(e)); drawRoute();
    return true;
  };
  const endDrag = () => {
    if (!drag) return;
    const moved = drag.moved; drag = null;
    if (moved) { swallowClick = true; planChanged(); }
  };
  map.on('mousedown', (e) => { swallowClick = false; if (e.originalEvent.button === 0) startDrag(e, 10); });
  map.on('mousemove', (e) => {
    if (dragTo(e)) return;
    if (state.mode === 'route') map.getCanvas().style.cursor = hit(e.point, ['route-wpts', 'route-mid']) ? 'grab' : 'crosshair';
    else map.getCanvas().style.cursor = hit(e.point, ['marks', 'hazards', 'catches']) ? 'pointer' : '';
    if (HOVER && !TOUCH) readCursor(ll(e));
  });
  addEventListener('mouseup', endDrag);
  map.on('click', (e) => {
    if (swallowClick) { swallowClick = false; return; }
    if (fields.editing) return;
    closeMenu();
    const p = ll(e);
    if (state.mode === 'route') { routeTap(p, e.point); return; }
    const mk = hit(e.point, ['marks']);
    if (mk) { const m = state.marks.find((x) => x.id === mk.properties.id); if (m) { markMenu(m); return; } }
    const ct = hit(e.point, ['catches']);
    if (ct) { const c = state.catches.find((x) => x.id === ct.properties.id); if (c) { viewCatch(c); return; } }
    const hz = hit(e.point, ['hazards']);
    if (hz) { showHazard(hz); return; }
    showZones(p, false);
  });
  map.on('contextmenu', (e) => { e.preventDefault(); onLongPress(ll(e), e.point); });
  // Touch: hold for the long-press dialog, and drag waypoints with a finger in Route mode.
  let press = null;
  const endPress = () => { if (press) { clearTimeout(press.timer); press = null; } };
  map.on('touchstart', (e) => {
    swallowClick = false;
    const t = e.originalEvent.touches;
    endPress();
    if (t.length !== 1) { if (drag) { drag = null; drawRoute(); } return; }
    if (startDrag(e, 24)) return;
    const p = ll(e), from = e.point;
    press = { from, timer: setTimeout(() => { press = null; swallowClick = true; menuGuard = Date.now() + 450; if (navigator.vibrate) navigator.vibrate(20); onLongPress(p, from); }, 550) };
  });
  map.on('touchmove', (e) => { if (press && Math.hypot(e.point.x - press.from.x, e.point.y - press.from.y) > 10) endPress(); dragTo(e); });
  map.on('touchend', () => { endPress(); endDrag(); });
  map.on('touchcancel', () => { endPress(); endDrag(); });

  /** Hold the map (or right-click): what's here, and what to do with it. */
  async function onLongPress(p, pt) {
    if (fields.editing) return;
    if (state.mode === 'route') { const w = hit(pt, ['route-wpts']); if (w) waypointMenu(w.properties.i); return; }
    let r = null;
    if (reader) { try { r = await reader.at(p); } catch (e) { /* off the chart */ } }
    const depth = r && r.known ? depthOf(r) : '--';
    const b = lastPos(), lines = [`Depth (est.): ${/^[\d>]/.test(depth) ? `${depth} m` : depth}`];
    if (b) lines.push(C.fromBoat(b, p));
    const src = r && r.known && !r.land ? C.sourceLines(r.source) : [];
    if (src.length) lines.push('', ...src, 'Depths are from about mean sea level: less water at low tide.');
    const td = tideNow(p);
    if (td) {
      const now = r && r.known ? depthNow(p, r) : '--';
      lines.push('', `Tide now (est.): ${td.h >= 0 ? '+' : ''}${td.h.toFixed(2)} m, ${td.rising ? 'rising' : 'falling'}${now !== '--' && now !== 'land' ? `. Depth now about ${now} m` : ''}`);
    }
    const t = shownTime(), w = C.windAt(wind.forecast, p.lat, p.lon, t), wv = C.wavesAt(wind.forecast, p.lat, p.lon, t);
    if (w) lines.push(`Wind ${windTime === null ? 'now' : windLabel(t)} (forecast): ${C.windText(w)}`);
    if (wv) lines.push(`Waves (forecast): ${C.wavesText(wv)}`);
    const pos = C.fmtPos(p);
    const a = await dialog(pos, (close) => [el('p.msg', {}, lines.join('\n')), actionList(close, [
      ['Mark a catch here', () => markCatch(null, p)],
      ['Bag & size limits here', () => openBag(p)],
      ['Copy position', () => navigator.clipboard.writeText(pos).then(() => toast('Position copied'), () => toast(pos))]])],
    [['Zones here', 'zones', 'neutral'], ['Add mark', 'mark'], ['Navigate here', 'nav', 'primary']]);
    if (a === 'nav') goTo(p, 'Target');
    if (a === 'mark') addMark(p, r && r.known && !r.land && r.depth ? depth : undefined);
    if (a === 'zones') showZones(p, true);
  }
  addEventListener('keydown', (e) => {
    if (document.querySelector('dialog[open]')) return;
    const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName) && e.target.type !== 'range' && e.target.type !== 'checkbox';
    if (e.key === 'Escape') {
      if (!$('menu').hidden) closeMenu();
      else if (movingWaypoint !== null) { movingWaypoint = null; refreshModePanel(); }
      else if (fields.editing) fields.setEditing(false);
      else if (panel.isOpen) panel.hide();
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !typing && state.mode === 'route') { e.preventDefault(); undoLast(); }
  });

  function showHazard(f) {
    const p = f.properties, [lon, lat] = f.geometry.coordinates, at = { lat, lon }, b = lastPos();
    const what = HAZARD_TEXT[p.kind] || 'Navigation mark';
    const text = [what, p.detail ? p.detail[0].toUpperCase() + p.detail.slice(1) : '', p.virtual ? 'Virtual mark: AIS only, nothing physical in the water' : '',
      p.owner ? `Maintained by: ${p.owner}` : '', '', C.fmtPos(at), b ? C.fromBoat(b, at) : '', '',
      p.src === 'dot' ? 'Source: WA Department of Transport, Navigation Aids (DOT-004), CC BY 4.0' : 'Source: © OpenStreetMap contributors (OpenSeaMap), ODbL. Position may be approximate.']
      .filter((x, i, a) => x || (a[i - 1] && a[i + 1])).join('\n');
    dialog(p.name || what.split(' (')[0].split(':')[0], text, [['Navigate here', 'nav', 'neutral'], ['OK', null, 'primary']])
      .then((a) => { if (a === 'nav') goTo(at, (p.name || 'Mark').slice(0, 24)); });
  }
  async function showZones(p, always) {
    if (!reader) return;
    let r;
    try { r = await reader.at(p); } catch (e) { return; }
    if (!r.zones.length) { if (always) toast('No zones here'); return; }
    dialog('Zones here', r.zones.map((z) => el('p.msg', {}, el('strong', {}, `${{ notake: 'NO FISHING', restricted: 'Restricted' }[z.zone_class] || 'Marine park'}: ${z.name || '?'}`),
      z.detail ? `\n${z.detail}` : '', z.source ? `\nSource: ${z.source}` : '',
      /^https?:\/\//i.test(z.link || '') ? ['\n', el('a.link', { href: z.link, target: '_blank', rel: 'noopener noreferrer' }, z.link)] : '')));
  }

  // With a mouse: position, depth and zone under the pointer, next to the Layers button.
  let cursorPos = null, cursorBusy = false;
  async function readCursor(p) {
    const c = $('cursor');
    c.hidden = false;
    c.firstChild.textContent = C.fmtPos(p);
    cursorPos = p;
    if (!reader || cursorBusy) return;
    cursorBusy = true;
    try {
      while (cursorPos) {
        const q = cursorPos; cursorPos = null;
        const r = await reader.at(q);
        c.children[1].textContent = r.known ? depthText(depthOf(r)) : '--';
        const z = r.zones.find((x) => x.zone_class === 'notake') || r.zones.find((x) => x.zone_class === 'restricted');
        c.children[2].textContent = z ? `${z.zone_class === 'notake' ? 'NO FISHING' : 'Restricted'}: ${z.name}` : '';
        c.children[2].className = z ? z.zone_class : '';
      }
    } catch (e) { c.children[1].textContent = '--'; }
    cursorBusy = false;
  }
  $('map').addEventListener('mouseleave', () => { $('cursor').hidden = true; });

  let dragDepth = 0;
  addEventListener('dragenter', (e) => { e.preventDefault(); dragDepth++; document.body.classList.add('drop'); });
  addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; document.body.classList.remove('drop'); } });
  addEventListener('dragover', (e) => e.preventDefault());
  addEventListener('drop', (e) => {
    e.preventDefault(); dragDepth = 0; document.body.classList.remove('drop');
    const f = e.dataTransfer.files[0]; if (f) openAny(f);
  });

  // %%MODULES%%

  // ---- Start --------------------------------------------------------------------------------------------
  $('welcome-open').onclick = chooseChart;
  for (const [id, ic] of [['btn-menu', 'menu'], ['btn-bag', 'bag_fish'], ['btn-layers', 'layers'], ['btn-locate', 'my_location']]) $(id).append(icon(ic));
  document.querySelectorAll('.corner .blabel').forEach((l) => { l.onclick = () => l.previousElementSibling.click(); });
  applyLook();
  fields.arrange();
  updateBag();
  setRouteTool(state.mode === 'route');
  // Installed from the website: work offline, and keep the chart from being cleared.
  if ((location.protocol === 'https:' || location.hostname === 'localhost') && 'serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
  (async () => {
    for (const kind of ['chart', 'relief']) {
      try { const f = await idb.get(kind); if (f) await useFile(f.blob, f.name); } catch (e) { /* not kept, or storage is blocked */ }
    }
    if (files.chart || files.relief) rebuildStyle();
    chartChanged();
    if (files.chart && navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
    resumeGps();
    updateScaleBar();
  })();
  globalThis.__planner = { map, state, files, drawData, setMode, fields, get reader() { return reader; } };   // used by the automated tests
})();
