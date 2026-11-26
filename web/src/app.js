// ChompChamp Planner: map, interface and storage. Everything runs from this one file.
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
      else if (k in e && k !== 'list') e[k] = v;
      else e.setAttribute(k, v);
    }
    for (const c of kids.flat()) if (c !== null && c !== undefined && c !== false) e.append(c);
    return e;
  }

  // ---- State (kept in this browser) ----------------------------------------------------
  const KEY = 'chompchamp.planner.v1';
  const state = { routes: [], plan: C.emptyRoute(), marks: [], groups: [], track: [], speed: 15, safety: 5, layers: {}, view: null, tab: 'routes' };
  let storageOk = true;
  try {
    const s = JSON.parse(localStorage.getItem(KEY) || '{}');
    const safe = (f, d) => { try { return f(); } catch (e) { return d; } };
    state.routes = (s.routes || []).map((r) => safe(() => C.cleanRoute(r))).filter(Boolean);
    state.marks = (s.marks || []).map((m) => safe(() => C.cleanMark(m))).filter(Boolean);
    state.groups = (s.groups || []).map((g) => safe(() => C.cleanGroup(g))).filter(Boolean);
    if (s.plan) state.plan = safe(() => C.cleanRoute(s.plan), state.plan);
    state.track = Array.isArray(s.track) ? s.track : [];
    for (const k of ['speed', 'safety', 'layers', 'view', 'tab']) if (s[k] !== undefined && s[k] !== null) state[k] = s[k];
  } catch (e) { storageOk = false; }
  C.tidyGroups(state.groups, state.marks);
  let saveTimer = 0;
  function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(saveNow, 250);
  }
  function saveNow() {
    clearTimeout(saveTimer);
    try { localStorage.setItem(KEY, JSON.stringify(state)); storageOk = true; } catch (e) {
      if (storageOk) toast("This browser won't save your work. Export it from the Data tab before closing.");
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
    }
    return kind;
  }

  async function openChartFile(file) {
    const kind = await useFile(file, file.name);
    let kept = true;
    try { await idb.put(kind, { name: file.name, blob: file }); } catch (e) { kept = false; }
    rebuildStyle(); renderAll();
    if (kind === 'chart') {
      const h = files.chart.header, c = map.getCenter();
      if (c.lng < h.minLon || c.lng > h.maxLon || c.lat < h.minLat || c.lat > h.maxLat) map.jumpTo({ center: [115.75, -32.05], zoom: 9 });
    }
    toast((kind === 'chart' ? 'Chart opened' : 'Reef relief opened') + (kept ? '' : ". This browser couldn't keep it, so you'll need to choose it again next time."));
  }

  // ---- Layers (same names, defaults and opacities as the phone app) -----------------------------
  const L = (id, prop, base) => ({ id, prop, base });
  const LAYERS = [
    ['Chart', [
      ['satellite', 'Satellite (online)', false, [L('satellite-land', 'raster-opacity', 1), L('satellite', 'raster-opacity', 1)]],
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
      ['route', 'Route', true, [L('route-line', 'line-opacity', 1), L('route-mid', 'circle-opacity', 0.6), L('route-wpts', 'circle-opacity', 1), L('route-wpts', 'circle-stroke-opacity', 1), L('route-labels', 'text-opacity', 1)]],
      ['marks', 'Marks', true, [L('marks', 'circle-opacity', 1), L('marks', 'circle-stroke-opacity', 1), L('marks-label', 'text-opacity', 1)]],
      ['track', 'Track', true, [L('track-line', 'line-opacity', 0.75)]],
    ]],
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
    for (const k of ['track', 'route-line', 'route-mid', 'route-wpts', 'marks', 'issue']) s.sources[k] = { type: 'geojson', data: fc([]) };
    const halo = { 'text-halo-color': '#ffffff', 'text-halo-width': 1.5 };
    const BLUE = '#1B4F72', NAVY = '#0D47A1', POINTS = ['ramp', 'harbour', 'marina', 'jetty', 'lighthouse'];
    const rank = ['to-number', ['get', 'rank']];
    layers.push(
      // Place names, like Places.kt: bigger places from further out (rank 1 from zoom 5, rank 6 from 13.5).
      { id: 'places', type: 'symbol', source: 'places', minzoom: 5,
        filter: ['step', ['zoom'], ['<=', rank, 1], 7, ['<=', rank, 2], 9, ['<=', rank, 3], 10.5, ['<=', rank, 4], 12, ['<=', rank, 5], 13.5, true],
        layout: { 'text-field': ['get', 'name'], 'text-font': FONT, 'text-max-width': 8, 'symbol-sort-key': rank,
          'text-size': ['match', ['get', 'kind'], 'city', 16, 'town', 13.5, ['village', 'island', 'bay', 'water'], 12, 'suburb', 11.5, ['beach', 'jetty'], 10.5, 11],
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
      { id: 'track-line', type: 'line', source: 'track', paint: { 'line-color': '#7b1fa2', 'line-width': 2 } },
      { id: 'route-line', type: 'line', source: 'route-line', layout: { 'line-cap': 'round' }, paint: { 'line-color': MAGENTA, 'line-width': 3 } },
      { id: 'route-mid', type: 'circle', source: 'route-mid', paint: { 'circle-radius': 5, 'circle-color': '#ffffff', 'circle-stroke-color': MAGENTA, 'circle-stroke-width': 1 } },
      { id: 'route-wpts', type: 'circle', source: 'route-wpts', paint: { 'circle-radius': 7, 'circle-color': '#ffffff', 'circle-stroke-color': MAGENTA, 'circle-stroke-width': 3 } },
      { id: 'route-labels', type: 'symbol', source: 'route-wpts', layout: { 'text-field': ['get', 'label'], 'text-font': FONT, 'text-size': 12,
        'text-offset': [0, -1.6], 'text-allow-overlap': true, 'text-ignore-placement': true }, paint: { 'text-color': MAGENTA, ...halo } },
      { id: 'marks', type: 'circle', source: 'marks', paint: { 'circle-radius': 7, 'circle-color': ['to-color', ['get', 'color'], '#fdd835'], 'circle-stroke-color': '#4e342e', 'circle-stroke-width': 2 } },
      { id: 'marks-label', type: 'symbol', source: 'marks', layout: { 'text-field': ['get', 'name'], 'text-font': FONT, 'text-size': 11,
        'text-offset': [0, 1.5], 'text-optional': true }, paint: { 'text-color': '#4e342e', ...halo } },
      { id: 'issue', type: 'circle', source: 'issue', paint: { 'circle-radius': 16, 'circle-color': '#C62828', 'circle-opacity': 0.12, 'circle-stroke-color': '#C62828', 'circle-stroke-width': 2.5 } },
    );
    s.layers = layers;
    return s;
  }

  // ---- Map -------------------------------------------------------------------------------------
  const v = state.view;
  const map = new maplibregl.Map({
    container: 'map', style: buildStyle(), attributionControl: false, maxZoom: 18, dragRotate: false, pitchWithRotate: false,
    center: v && isFinite(v.lon) && isFinite(v.lat) ? [v.lon, v.lat] : [115.75, -32.05], zoom: v && isFinite(v.zoom) ? v.zoom : 9,
  });
  map.touchZoomRotate.disableRotation();
  map.keyboard.disableRotation();
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
  map.addControl(new maplibregl.ScaleControl({ unit: 'nautical', maxWidth: 140 }), 'bottom-right');
  map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');
  // Show the data credits for a few seconds, then fold them into the (i) button.
  setTimeout(() => { const d = document.querySelector('.maplibregl-ctrl-attrib'); if (d) { d.classList.remove('maplibregl-compact-show'); d.removeAttribute('open'); } }, 6000);
  map.on('styleimagemissing', (e) => {
    if (e.id.startsWith('hz-') && !map.hasImage(e.id)) map.addImage(e.id, hazardIcon(e.id.slice(3)), { pixelRatio: 2 });
    if (e.id.startsWith('pl-') && !map.hasImage(e.id)) map.addImage(e.id, placeIcon(e.id === 'pl-ramp'), { pixelRatio: 2 });
  });
  map.on('style.load', () => { applyLayers(); drawData(); });
  map.on('moveend', () => { const c = map.getCenter(); state.view = { lon: c.lng, lat: c.lat, zoom: map.getZoom() }; save(); });
  const rebuildStyle = () => map.setStyle(buildStyle(), { diff: false });

  function setData(id, features) { const s = map.getSource(id); if (s) s.setData(fc(features)); }
  function drawData() {
    const w = state.plan.waypoints;
    setData('route-line', w.length > 1 ? [line(w)] : []);
    setData('route-wpts', w.map((p, i) => point(p, { i, label: p.name || String(i + 1) })));
    setData('route-mid', tool === 'route' ? w.slice(1).map((p, i) => point({ lat: (p.lat + w[i].lat) / 2, lon: (p.lon + w[i].lon) / 2 }, { i })) : []);
    const groups = Object.fromEntries(state.groups.map((g) => [g.name, g]));
    setData('marks', state.marks.filter((m) => groups[m.group]?.visible !== false).map((m) => point(m, { id: m.id, name: m.name, color: groups[m.group]?.color || '#FDD835' })));
    setData('track', state.track.length > 1 ? [line(state.track)] : []);
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

  // ---- Place search ------------------------------------------------------------------------------
  {
    const input = $('search-input'), list = $('search-results');
    let hits = [], sel = 0;
    const close = () => { list.hidden = true; list.replaceChildren(); };
    const go = (f) => {
      const [lon, lat] = f.geometry.coordinates;
      close(); input.blur();
      map.easeTo({ center: [lon, lat], zoom: Math.min(15, Math.max(map.getZoom(), C.placeZoom(f.properties.rank) + 1)), duration: 1200 });
      if (!layerState('places', true).on) toast(`${f.properties.name}. Place names are off in Layers.`);
    };
    const show = () => {
      const c = map.getCenter();
      hits = C.searchPlaces(DATA.places.features, input.value, { lat: c.lat, lon: c.lng }, 40);
      sel = 0;
      if (!input.value.trim()) return close();
      list.replaceChildren(...(hits.length ? hits.map((f, i) => {
        const p = f.properties, [lon, lat] = f.geometry.coordinates;
        const sub = [C.placeKind(p.kind), p.near && !p.name.includes(p.near) ? `near ${p.near}` : null,
          `${C.fmtNm(C.distanceNm({ lat: c.lat, lon: c.lng }, { lat, lon }))} from map centre`].filter(Boolean).join(' · ');
        return el('li', {}, el('button' + (i === 0 ? '.sel' : ''), { type: 'button', onmousedown: (e) => e.preventDefault(), onclick: () => go(f) },
          p.name, el('span.muted', {}, sub), p.detail ? el('span.muted', {}, p.detail) : null));
      }) : [el('li.empty', {}, `Nothing called "${input.value.trim()}". Try fewer letters.`)]));
      list.hidden = false;
    };
    input.addEventListener('input', show);
    input.addEventListener('focus', () => { if (input.value.trim()) show(); });
    input.addEventListener('blur', close);
    input.addEventListener('keydown', (e) => {
      const btns = list.querySelectorAll('button');
      if (e.key === 'Escape') { input.value = ''; close(); input.blur(); }
      else if (e.key === 'Enter') { if (hits[sel]) go(hits[sel]); }
      else if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && btns.length) {
        e.preventDefault();
        btns[sel]?.classList.remove('sel');
        sel = (sel + (e.key === 'ArrowDown' ? 1 : btns.length - 1)) % btns.length;
        btns[sel].classList.add('sel'); btns[sel].scrollIntoView({ block: 'nearest' });
      }
    });
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

  // ---- Small UI helpers ---------------------------------------------------------------------------
  let toastTimer = 0;
  function toast(text) {
    $('toast').textContent = text; $('toast').hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4500);
  }
  /** Modal. buttons: [[label, value, cls]]. Resolves to the value, or null if dismissed. */
  function dialog(title, body, buttons = [['OK', true, 'primary']], input = null) {
    const d = $('dialog'), f = d.querySelector('form');
    if (d.open) d.close();
    return new Promise((ok) => {
      let result = null;
      const field = input !== null ? el('input', { type: 'text', value: input, ariaLabel: title }) : null;
      const done = (val) => { result = val; d.close(); };
      const btns = buttons.map(([label, val, cls = 'btn']) => el(`button.${cls.split(' ').join('.')}`, { type: 'button', onclick: () => done(field && val === true ? field.value.trim() : val) }, label));
      f.replaceChildren(el('h2', {}, title), ...[body].flat().filter(Boolean).map((b) => (typeof b === 'string' ? el('p', {}, b) : b)), field || '', el('div.btns', {}, btns));
      f.onsubmit = (e) => { e.preventDefault(); done(field ? field.value.trim() : true); };
      d.onclose = () => { d.onclose = null; ok(result); };
      d.showModal();
      if (field) field.select(); else btns[btns.length - 1].focus();
    });
  }
  const confirmBox = (title, text, yes = 'OK') => dialog(title, text, [['Cancel', null], [yes, true, 'primary']]);
  const askText = (title, value) => dialog(title, null, [['Cancel', null], ['OK', true, 'primary']], value);

  function openMenu(x, y, heading, items) {
    const m = $('menu');
    m.replaceChildren(el('div.muted', {}, heading), ...items.map(([label, fn]) => el('button', { role: 'menuitem', onclick: () => { m.hidden = true; fn(); } }, label)));
    m.hidden = false;
    m.style.left = `${Math.min(x, innerWidth - m.offsetWidth - 8)}px`;
    m.style.top = `${Math.min(y, innerHeight - m.offsetHeight - 8)}px`;
    m.querySelector('button').focus();
  }
  addEventListener('pointerdown', (e) => { if (!$('menu').contains(e.target)) $('menu').hidden = true; }, true);

  function pickFile(accept, fn) {
    const f = $('file');
    f.value = ''; f.accept = accept;
    f.onchange = () => { if (f.files[0]) fn(f.files[0]); };
    f.click();
  }
  function download(name, text, type) {
    const a = el('a', { href: URL.createObjectURL(new Blob([text], { type })), download: name });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
  }
  const stamp = () => { const d = new Date(), p = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`; };
  const mb = (n) => `${Math.round(n / 1e6)} MB`;

  // ---- Route planning -----------------------------------------------------------------------------
  let tool = null;            // null | 'route' | 'mark'
  const undo = [];
  let issues = null, checking = null;
  const snapshot = () => { undo.push(JSON.stringify(state.plan.waypoints)); if (undo.length > 200) undo.shift(); };
  function planChanged() {
    issues = null; if (checking) checking.stop = true; setData('issue', []);
    save(); drawData(); renderRoutes();
  }
  function undoLast() { if (undo.length) { state.plan.waypoints = JSON.parse(undo.pop()); planChanged(); } }
  function addWaypoint(p) { snapshot(); state.plan.waypoints.push({ ...p, name: '' }); planChanged(); }

  function setTool(t) {
    tool = tool === t ? null : t;
    map.getCanvas().style.cursor = tool ? 'crosshair' : '';
    map.doubleClickZoom[tool ? 'disable' : 'enable']();
    $('hint').hidden = !tool;
    $('hint').textContent = tool === 'route' ? 'Click to add waypoints. Drag a point to move it, or a small dot to bend a leg. Esc to finish.'
      : tool === 'mark' ? 'Click the map to place a mark. Esc to cancel.' : '';
    drawData(); renderRoutes(); renderMarks();
  }

  async function replacePlanOk(what) {
    const w = state.plan.waypoints;
    if (!w.length) return true;
    const saved = state.routes.find((r) => r.id === state.plan.id);
    if (saved && JSON.stringify(C.cleanRoute(saved)) === JSON.stringify(C.cleanRoute(state.plan))) return true;
    return confirmBox(what, 'The current route has changes that are not saved. Discard them?', 'Discard');
  }
  async function newRoute() {
    if (!await replacePlanOk('New route')) return;
    state.plan = C.emptyRoute(); undo.length = 0; planChanged();
  }
  async function openRoute(r) {
    if (!await replacePlanOk('Open route')) return;
    state.plan = C.cleanRoute(r); undo.length = 0; planChanged(); fit(state.plan.waypoints);
  }
  async function saveRoute() {
    let name = state.plan.name;
    if (!name) { name = await askText('Route name', `Route ${state.routes.length + 1}`); if (!name) return; }
    state.plan.name = name;
    const i = state.routes.findIndex((r) => r.id === state.plan.id);
    if (i >= 0) state.routes[i] = C.cleanRoute(state.plan); else state.routes.push(C.cleanRoute(state.plan));
    save(); renderRoutes(); toast(`Saved "${name}"`);
  }
  async function deleteRoute(r) {
    if (!await confirmBox('Delete route', `Delete "${r.name || 'Unnamed route'}"?`, 'Delete')) return;
    state.routes = state.routes.filter((x) => x.id !== r.id);
    save(); renderRoutes();
  }
  function fit(pts) {
    if (!pts.length) return;
    const b = new maplibregl.LngLatBounds();
    pts.forEach((p) => b.extend([p.lon, p.lat]));
    map.fitBounds(b, { padding: 80, maxZoom: 14, duration: 500 });
  }
  async function runCheck() {
    if (!reader) { toast('Open a chart first (Data tab)'); return; }
    const job = { stop: false, pct: 0 };
    checking = job; issues = null; renderRoutes();
    try {
      const res = await C.checkRoute(reader, state.plan.waypoints.map((w) => ({ ...w })), state.safety, DATA.hazards.features, {
        onProgress: (p) => { const e = $('check-pct'); if (e && checking === job) e.textContent = `Checking… ${Math.round(p * 100)}%`; },
        cancelled: () => job.stop,
      });
      if (!job.stop) issues = res;
    } catch (e) { toast(`Check failed: ${e.message}`); }
    if (checking === job) checking = null;
    renderRoutes();
  }

  function renderRoutes() {
    const w = state.plan.waypoints, pane = $('pane-routes');
    const len = C.routeLengthNm(w), out = [];
    out.push(el('div.btns', {},
      el('button.btn', { id: 'draw', ariaPressed: String(tool === 'route'), onclick: () => setTool('route') }, tool === 'route' ? 'Finish drawing' : 'Draw route'),
      el('button.btn', { disabled: !undo.length, title: 'Ctrl+Z', onclick: undoLast }, 'Undo'),
      el('button.btn', { disabled: w.length < 2, onclick: () => { snapshot(); w.reverse(); planChanged(); } }, 'Reverse'),
      el('button.btn', { onclick: newRoute }, 'New'),
      el('button.btn', { disabled: !w.length, onclick: saveRoute }, 'Save')));
    out.push(el('div.row', {}, el('input', { type: 'text', placeholder: 'Route name', ariaLabel: 'Route name', value: state.plan.name,
      onchange: (e) => { state.plan.name = e.target.value.trim(); save(); } })));
    out.push(el('div.totals', {},
      el('span.pill', {}, w.length > 1 ? C.fmtNm(len) : `${w.length} waypoint${w.length === 1 ? '' : 's'}`),
      el('span', {}, w.length > 1 ? `${C.fmtDuration(len / state.speed)} at ` : 'Speed ',
        el('select', { ariaLabel: 'Planning speed', onchange: (e) => { state.speed = Number(e.target.value); save(); renderRoutes(); } },
          [5, 8, 10, 15, 20, 25, 30].map((k) => el('option', { value: k, selected: k === state.speed }, `${k} kn`))))));
    if (!w.length) out.push(el('p.empty', {}, 'Press Draw route, then click the map to add waypoints.'));
    let run = 0;
    w.forEach((p, i) => {
      out.push(el('div.row', {}, el('button.icon.num', { title: 'Show on map', ariaLabel: `Show waypoint ${i + 1}`, onclick: () => map.easeTo({ center: [p.lon, p.lat] }) }, String(i + 1)),
        el('input.bare.grow', { type: 'text', value: p.name, placeholder: `Waypoint ${i + 1}`, ariaLabel: `Name of waypoint ${i + 1}`,
          onchange: (e) => { snapshot(); p.name = e.target.value.trim(); planChanged(); } }),
        el('button.icon', { title: 'Delete waypoint', ariaLabel: `Delete waypoint ${i + 1}`, onclick: () => { snapshot(); w.splice(i, 1); planChanged(); } }, '✕')));
      if (i + 1 < w.length) {
        const d = C.distanceNm(p, w[i + 1]); run += d;
        out.push(el('div.leg.muted', {}, `↓ ${C.fmtNm(d)} · ${C.fmtBearing(C.bearing(p, w[i + 1]))} · ${C.fmtDuration(d / state.speed)} · total ${C.fmtNm(run)}`));
      }
    });
    if (w.length > 1) {
      out.push(el('h3', {}, 'Route check'), el('div.row', {}, el('span.grow', {}, 'Warn when shallower than'),
        el('select', { ariaLabel: 'Safety depth', onchange: (e) => { state.safety = Number(e.target.value); issues = null; save(); renderRoutes(); } },
          [2, 3, 5, 10, 20].map((k) => el('option', { value: k, selected: k === state.safety }, `${k} m`)))));
      out.push(el('div.btns', {}, checking
        ? [el('span', { id: 'check-pct' }, 'Checking…'), el('button.btn', { onclick: () => { checking.stop = true; } }, 'Cancel')]
        : el('button.primary', { id: 'check', onclick: runCheck }, 'Check route')));
      if (issues) {
        if (!issues.length) out.push(el('p', {}, `No land, water under ${state.safety} m, charted hazards or no-fishing zones found on the route.`));
        out.push(el('ul.issues', {}, issues.map((x) => el('li', {}, el('button', { onclick: () => { setData('issue', [point(x.at, {})]); map.easeTo({ center: [x.at.lon, x.at.lat], zoom: Math.max(map.getZoom(), 14) }); } },
          el(`span.tag.${x.level}`, {}, { stop: 'LAND', warn: 'CARE', info: 'ZONE' }[x.level]), el('span', {}, x.text))))));
        out.push(el('p.muted', {}, 'Depths are survey data around Perth and a 250 m model elsewhere, measured from about mean sea level: expect less water at low tide. Small reefs, rocks and bars can be missed. Check official charts.'));
      }
    }
    out.push(el('h3', {}, 'Saved routes'));
    if (!state.routes.length) out.push(el('p.empty', {}, 'None yet.'));
    state.routes.forEach((r) => out.push(el('div.row', {},
      el('button.link.grow.clip', { onclick: () => openRoute(r) }, r.name || 'Unnamed route'),
      el('span.muted', {}, C.fmtNm(C.routeLengthNm(r.waypoints))),
      el('button.icon', { title: 'Delete route', ariaLabel: `Delete route ${r.name}`, onclick: () => deleteRoute(r) }, '✕'))));
    pane.replaceChildren(...out);
  }

  // ---- Marks ---------------------------------------------------------------------------------------
  let markFilter = '';
  async function addMark(p) {
    if (tool === 'mark') setTool('mark');
    let depth = null;
    if (reader) { try { const r = await reader.at(p); if (r.depth && !r.land) depth = C.depthText(r).replace(' m', ''); } catch (e) { /* no depth */ } }
    const name = await askText('New mark', `Mark ${state.marks.length + 1}`);
    if (name === null) return;
    const m = { id: C.uuid(), ...p, name: name || `Mark ${state.marks.length + 1}`, time: Date.now(), group: openGroup === null ? '' : openGroup };
    if (depth) m.depth = depth;
    const g = C.findGroup(state.groups, m.group);
    if (g) g.visible = true;   // a new mark must never be invisible
    state.marks.push(m); save(); drawData(); renderMarks();
  }
  async function showMark(m) {
    const info = [C.fmtPos(m), m.depth ? `Depth (est.): ${m.depth} m` : '', m.time ? new Date(m.time).toLocaleString() : '',
      m.photos ? `${m.photos.length} photo${m.photos.length === 1 ? '' : 's'} on the phone` : ''].filter(Boolean).join('\n');
    const grp = groupSelect(m.group, (name) => { m.group = name; marksChanged(); });
    const a = await dialog(m.name || 'Mark', [info, el('div.row', {}, el('span', {}, 'Group'), grp)], [['Delete', 'del', 'btn danger'], ['Rename', 'ren'], ['Add to route', 'rte'], ['Close', null, 'primary']]);
    if (a === 'rte') { snapshot(); state.plan.waypoints.push({ lat: m.lat, lon: m.lon, name: m.name }); planChanged(); toast('Added to the route'); }
    if (a === 'ren') { const n = await askText('Rename mark', m.name); if (n) { m.name = n; save(); drawData(); renderMarks(); } }
    if (a === 'del' && await confirmBox('Delete mark', `Delete "${m.name}"?`, 'Delete')) {
      state.marks = state.marks.filter((x) => x.id !== m.id); save(); drawData(); renderMarks();
    }
  }
  // Groups: null = the list of groups, otherwise the name of the group being looked at.
  let openGroup = null;
  const picked = new Set();
  const groupLabel = (g) => g.name || 'Ungrouped';
  const marksIn = (name) => state.marks.filter((m) => m.group === name);
  const nMarks = (n) => `${n} mark${n === 1 ? '' : 's'}`;
  function marksChanged() { C.tidyGroups(state.groups, state.marks); save(); drawData(); renderMarks(); }

  /** <select> of groups with "New group…" at the end. */
  function groupSelect(value, onPick, first = null) {
    const s = el('select', { ariaLabel: 'Group' });
    const fill = (v) => s.replaceChildren(...[first && el('option', { value: '\u0001' }, first), ...state.groups.map((g) => el('option', { value: g.name, selected: g.name === v }, groupLabel(g))), el('option', { value: '\u0000' }, 'New group…')].filter(Boolean));
    fill(value);
    s.addEventListener('change', () => {
      if (s.value !== '\u0000') { if (s.value !== '\u0001') onPick(s.value); return; }
      // A plain prompt field in place: this can sit inside the one dialog the page has.
      const inp = el('input', { type: 'text', placeholder: 'New group name', ariaLabel: 'New group name' });
      let done = false;
      const finish = (keep) => {
        if (done) return; done = true;
        const name = keep && C.groupName(inp.value) ? C.ensureGroup(state.groups, inp.value) : value;
        fill(name); inp.replaceWith(s);
        if (name !== value) { value = name; onPick(name); }
      };
      inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); finish(true); } else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); finish(false); } });
      inp.addEventListener('blur', () => finish(true));
      s.replaceWith(inp); inp.focus();
    });
    return s;
  }
  /** Ask for a group. Resolves to its name, or null if cancelled. */
  async function askGroup(title, text, suggestion = '') {
    let choice = suggestion ? C.groupName(suggestion) : '';
    const sel = groupSelect(choice, (n) => { choice = n; });
    if (choice && !C.findGroup(state.groups, choice)) { sel.prepend(el('option', { value: choice, selected: true }, `New group: ${choice}`)); sel.value = choice; }
    const ok = await dialog(title, [text, el('div.row', {}, el('span', {}, 'Group'), sel)].filter(Boolean), [['Cancel', null], ['OK', true, 'primary']]);
    return ok ? C.ensureGroup(state.groups, choice) : null;
  }
  async function renameGroup(g) {
    const n = C.groupName(await askText('Rename group', g.name) || '');
    if (!n || n === g.name) return;
    const clash = C.findGroup(state.groups, n);
    if (clash && clash !== g) { toast(`There's already a group called ${groupLabel(clash)}`); return; }
    const list = marksIn(g.name);
    g.name = n; list.forEach((m) => { m.group = n; }); openGroup = n; marksChanged();
  }
  async function deleteGroup(g) {
    const n = marksIn(g.name).length;
    const how = await dialog(`Delete ${groupLabel(g)}?`, g.name ? `It has ${nMarks(n)}. Keep them (they become ungrouped) or delete them too? Deleting marks can't be undone.` : `Delete all ${nMarks(n)} that aren't in a group? This can't be undone.`,
      [['Cancel', null], ['Delete marks too', 'all', 'btn danger'], ...(g.name ? [['Keep marks', 'keep', 'primary']] : [])]);
    if (!how) return;
    if (how === 'all') state.marks = state.marks.filter((m) => m.group !== g.name);
    else marksIn(g.name).forEach((m) => { m.group = ''; });
    if (g.name) state.groups = state.groups.filter((x) => x !== g);
    openGroup = null; picked.clear(); marksChanged();
  }
  function markRows() {
    const q = markFilter.toLowerCase();
    const list = state.marks.filter((m) => (openGroup === null || m.group === openGroup) && m.name.toLowerCase().includes(q)).sort((a, b) => b.time - a.time);
    if (!list.length) return [el('p.empty', {}, q ? 'No marks match.' : 'No marks here yet.')];
    return list.map((m) => el('div.row', {},
      openGroup === null ? null : el('input', { type: 'checkbox', ariaLabel: `Select ${m.name}`, checked: picked.has(m.id), onchange: (e) => { if (e.target.checked) picked.add(m.id); else picked.delete(m.id); updatePicked(); } }),
      el('button.link.grow.clip', { onclick: () => map.easeTo({ center: [m.lon, m.lat], zoom: Math.max(map.getZoom(), 13) }) }, m.name || 'Mark'),
      m.depth ? el('span.muted', {}, `${m.depth} m`) : null,
      el('button.icon', { title: 'Details', ariaLabel: `Details of ${m.name}`, onclick: () => showMark(m) }, '⋯')));
  }
  function updatePicked() {
    const bar = $('picked'); if (!bar) return;
    bar.hidden = !picked.size;
    $('picked-n').textContent = `${picked.size} selected`;
  }
  function renderMarks() {
    const g = openGroup === null ? null : state.groups.find((x) => x.name === openGroup);
    if (openGroup !== null && !g) openGroup = null;
    for (const id of picked) if (!state.marks.some((m) => m.id === id && m.group === openGroup)) picked.delete(id);
    const rows = el('div', {});
    const showRows = () => rows.replaceChildren(...(openGroup === null && !markFilter ? groupRows() : markRows()));
    const out = [el('div.btns', {},
      el('button.btn', { ariaPressed: String(tool === 'mark'), onclick: () => setTool('mark') }, tool === 'mark' ? 'Cancel' : 'Add mark'),
      g ? null : el('button.btn', { onclick: async () => { const n = C.groupName(await askText('New group', '') || ''); if (n) { C.ensureGroup(state.groups, n); marksChanged(); } } }, 'New group'))];
    if (g) {
      out.push(el('button.link', { onclick: () => { openGroup = null; picked.clear(); renderMarks(); } }, '‹ All groups'),
        el('div.row', {}, el('span.dot', { style: `background:${g.color}` }), el('strong.grow.clip', {}, groupLabel(g)),
          el('select', { ariaLabel: 'Colour', onchange: (e) => { g.color = e.target.value; marksChanged(); } }, C.GROUP_COLORS.map(([n, c]) => el('option', { value: c, selected: c.toLowerCase() === g.color.toLowerCase() }, n)))),
        el('div.btns', {}, g.name ? el('button.btn', { onclick: () => renameGroup(g) }, 'Rename') : null,
          el('button.btn', { onclick: () => { const all = marksIn(g.name), on = picked.size < all.length; picked.clear(); if (on) all.forEach((m) => picked.add(m.id)); renderMarks(); } }, 'Select all'),
          el('button.btn.danger', { disabled: !g.name && !marksIn('').length, onclick: () => deleteGroup(g) }, g.name ? 'Delete group' : 'Delete all')),
        el('div.btns', { id: 'picked', hidden: true }, el('span', { id: 'picked-n' }),
          el('button.btn', { onclick: async () => { const ids = new Set(picked), n = await askGroup('Move marks', `Move ${nMarks(ids.size)} to`); if (n === null) return; state.marks.forEach((m) => { if (ids.has(m.id)) m.group = n; }); picked.clear(); marksChanged(); toast(C.findGroup(state.groups, n).visible ? 'Moved' : 'Moved. That group is hidden on the map.'); } }, 'Move…'),
          el('button.btn.danger', { onclick: async () => { const ids = new Set(picked); if (!await confirmBox('Delete marks', `Delete ${nMarks(ids.size)}? This can't be undone.`, 'Delete')) return; state.marks = state.marks.filter((m) => !ids.has(m.id)); picked.clear(); marksChanged(); } }, 'Delete…')));
    }
    out.push(el('input', { type: 'search', placeholder: g ? 'Search this group' : `Search ${nMarks(state.marks.length)}`, ariaLabel: 'Search marks', value: markFilter,
      oninput: (e) => { markFilter = e.target.value; showRows(); } }), rows);
    showRows();
    $('pane-marks').replaceChildren(...out.filter(Boolean));
    updatePicked();
  }
  function groupRows() {
    const out = [el('h3', {}, 'Groups shown on the map')];
    for (const g of state.groups) {
      out.push(el('div.row', {}, el('input', { type: 'checkbox', checked: g.visible, ariaLabel: `Show ${groupLabel(g)}`, onchange: (e) => { g.visible = e.target.checked; save(); drawData(); } }),
        el('span.dot', { style: `background:${g.color}` }),
        el('button.link.grow.clip', { onclick: () => { openGroup = g.name; picked.clear(); renderMarks(); } }, groupLabel(g)),
        el('span.muted', {}, `${marksIn(g.name).length} ›`)));
    }
    const hidden = state.groups.some((g) => !g.visible);
    out.push(el('div.btns', {}, el('button.btn', { onclick: () => { state.groups.forEach((g) => { g.visible = hidden; }); marksChanged(); } }, hidden ? 'Show all' : 'Hide all')));
    if (!state.marks.length) out.push(el('p.empty', {}, 'No marks yet. Add one here, right-click the map, or import a backup from your phone in the Data tab.'));
    return out;
  }

  // ---- Layers pane ------------------------------------------------------------------------------------
  function renderLayers() {
    const out = [];
    for (const [title, group] of LAYERS) {
      out.push(el('h3', {}, title));
      for (const [key, label, def] of group) {
        const s = layerState(key, def);
        const set = (patch) => { state.layers[key] = { ...layerState(key, def), ...patch }; save(); applyLayers(); };
        const pct = el('span.pct', {}, `${s.pct}%`);
        const row = el(`div.layer${s.on ? '' : '.off'}`, {},
          el('input', { type: 'checkbox', id: `l-${key}`, checked: s.on, onchange: (e) => { set({ on: e.target.checked }); row.classList.toggle('off', !e.target.checked); } }),
          el('label', { htmlFor: `l-${key}` }, label),
          el('input', { type: 'range', min: 0, max: 100, step: 5, value: s.pct, ariaLabel: `${label} opacity`, oninput: (e) => { pct.textContent = `${e.target.value}%`; set({ pct: Number(e.target.value) }); } }),
          pct);
        out.push(row);
      }
    }
    out.push(el('p.empty', {}, 'Depth, zones and the route check read the chart file, so they work even when a layer is hidden.'),
      el('button.btn', { onclick: async () => { if (await confirmBox('Reset layers', 'Put every layer back to its default?', 'Reset')) { state.layers = {}; save(); applyLayers(); renderLayers(); } } }, 'Reset layers'));
    $('pane-layers').replaceChildren(...out);
  }

  // ---- Data pane -----------------------------------------------------------------------------------------
  const CHART_ACCEPT = '.pmtiles,application/octet-stream,application/vnd.pmtiles';
  const chooseChart = () => pickFile(CHART_ACCEPT, (f) => openAny(f));
  async function removeFile(kind) {
    if (!await confirmBox('Remove file', `Remove the ${kind === 'chart' ? 'chart' : 'reef relief'} from this browser? Your original file isn't touched.`, 'Remove')) return;
    files[kind] = null; if (kind === 'chart') reader = null;
    try { await idb.del(kind); } catch (e) { /* nothing stored */ }
    rebuildStyle(); renderAll();
  }
  async function importText(text, name) {
    const t = text.trimStart();
    const isGpx = !t.startsWith('{');
    const b = t.startsWith('{') ? C.parseBackup(t) : C.parseGpx(new DOMParser().parseFromString(t, 'application/xml'));
    const list = [`${b.routes.length} route(s)${b.plan && b.plan.waypoints.length ? ' and the current plan' : ''}`, `${b.marks.length} mark(s)`, `${b.track.length} track point(s)`].join('\n');
    const how = await dialog(`Import ${name}`, [list, 'Merge keeps what you have and updates routes and marks that are in both. Replace swaps your routes, marks and track for the ones in this file.'],
      [['Cancel', null], ['Replace', 'replace', 'btn danger'], ['Merge', 'merge', 'primary']]);
    if (!how) return;
    if (how === 'replace' && !await confirmBox('Replace everything?', 'Your routes, marks and track in the planner will be replaced. This can\'t be undone.', 'Replace')) return;
    const replace = how === 'replace';
    if (isGpx && b.marks.length) {
      // Keeping imports in their own group makes them easy to hide or delete again.
      const named = new Set(b.marks.map((m) => m.group).filter(Boolean)).size;
      let keep = false;
      if (named) {
        const c = await dialog('Groups', `This file sorts its marks into ${named} group${named === 1 ? '' : 's'}.`, [['Cancel', null], ['Put all in one group', 'one'], ['Keep its groups', 'keep', 'primary']]);
        if (!c) return;
        keep = c === 'keep';
      }
      if (!keep) {
        const n = await askGroup('Choose a group', `Put ${nMarks(b.marks.length)} in`, name.replace(/\.[^.]*$/, '').replace(/[_-]+/g, ' '));
        if (n === null) return;
        b.marks.forEach((m) => { m.group = n; });
      }
    }
    state.routes = C.mergeById(replace ? [] : state.routes, b.routes);
    state.marks = C.mergeMarks(replace ? [] : state.marks, b.marks);
    if (replace && !isGpx) state.groups = [];
    for (const g of b.groups) if (!C.findGroup(state.groups, g.name)) state.groups.push(g);
    C.tidyGroups(state.groups, state.marks);
    if (replace || b.track.length) state.track = b.track;
    if (replace) state.plan = b.plan || C.emptyRoute();
    else if (b.plan && b.plan.waypoints.length && !state.plan.waypoints.length) state.plan = b.plan;
    undo.length = 0; planChanged(); renderAll();
    toast('Imported');
    fit([...b.marks, ...b.routes.flatMap((r) => r.waypoints)]);
  }
  async function openAny(file) {
    try {
      const head = new Uint8Array(await file.slice(0, 7).arrayBuffer());
      if (String.fromCharCode(...head) === 'PMTiles') { toast('Opening chart…'); await openChartFile(file); }
      else if (file.size > 50e6 || head[0] === 0x50) throw new Error("that isn't a chart, backup or GPX file (if it's a zip, unzip it first)");
      else await importText(await file.text(), file.name);
    } catch (e) { dialog("Couldn't open that file", `${file.name}: ${e.message}`); }
  }
  function renderData() {
    const fileRow = (kind, label, none) => el('div.row', {}, el('div.grow', {}, label, el('div.muted.clip', {}, files[kind] ? `${files[kind].name} · ${mb(files[kind].size)}` : none)),
      files[kind] ? el('button.btn', { onclick: () => removeFile(kind) }, 'Remove') : null);
    $('pane-data').replaceChildren(
      el('h3', {}, 'Charts'),
      fileRow('chart', 'Chart', 'Not opened yet'),
      fileRow('relief', 'Reef relief', 'Not opened: loads online instead'),
      el('div.btns', {}, el('button.btn', { onclick: chooseChart }, 'Open .pmtiles file…')),
      el('h3', {}, 'From your phone'),
      el('p.muted', {}, 'On the phone: ☰ → Data → Export backup file. Copy it here and import it.'),
      el('div.btns', {}, el('button.btn', { onclick: () => pickFile('.json,.gpx,application/json,application/gpx+xml', openAny) }, 'Import backup or GPX…')),
      el('h3', {}, 'To your phone'),
      el('p.muted', {}, 'Copy the backup to the phone, then ☰ → Data → Import and choose Merge. Routes and marks you deleted here stay on the phone when merging.'),
      el('div.btns', {},
        el('button.btn', { onclick: () => download(`chompchamp-planner-${stamp()}.json`, C.exportBackup(state), 'application/json') }, 'Export backup'),
        el('button.btn', { onclick: () => download(`chompchamp-${stamp()}.gpx`, C.exportGpx(state), 'application/gpx+xml') }, 'Export GPX')),
      el('h3', {}, 'This browser'),
      el('p.muted', {}, 'Your routes and marks are saved in this browser only. Clearing its data deletes them, so export a backup now and then.'),
      el('div.btns', {}, el('button.btn.danger', { onclick: async () => {
        if (!await confirmBox('Delete my data', 'Delete all routes, marks and track from the planner? The charts stay. This can\'t be undone.', 'Delete')) return;
        Object.assign(state, { routes: [], marks: [], groups: [], track: [], plan: C.emptyRoute() }); C.tidyGroups(state.groups, []); undo.length = 0; planChanged(); renderAll();
      } }, 'Delete my data…')),
      el('h3', {}, 'About'),
      el('p.muted', {}, `Planner ${DATA.version}. Source code, data credits and licences are on GitHub.`),
      el('p', {}, el('a', { href: 'https://github.com/wufle/chompchamp', target: '_blank', rel: 'noopener noreferrer', style: 'color:var(--accent)' }, 'github.com/wufle/chompchamp')));
  }

  function renderAll() { renderRoutes(); renderMarks(); renderLayers(); renderData(); $('welcome').hidden = !!files.chart; }
  function setTab(t) {
    if (!$(`pane-${t}`)) t = 'routes';
    state.tab = t; save();
    document.querySelectorAll('nav button').forEach((b) => { const on = b.dataset.tab === t; b.setAttribute('aria-selected', on); $(`pane-${b.dataset.tab}`).hidden = !on; });
  }
  document.querySelectorAll('nav button').forEach((b) => b.addEventListener('click', () => setTab(b.dataset.tab)));
  function collapse(on) { document.body.classList.toggle('collapsed', on); $('expand').hidden = !on; map.resize(); }
  $('collapse').onclick = () => collapse(true);
  $('expand').onclick = () => collapse(false);
  $('welcome-open').onclick = chooseChart;

  // ---- Map interaction -----------------------------------------------------------------------------------
  const ll = (e) => ({ lat: e.lngLat.lat, lon: e.lngLat.lng });
  const hit = (pt, layers, r = 8) => {
    const have = layers.filter((l) => map.getLayer(l));
    return have.length ? map.queryRenderedFeatures([[pt.x - r, pt.y - r], [pt.x + r, pt.y + r]], { layers: have })[0] : null;
  };

  let drag = null, swallowClick = false;
  map.on('mousedown', (e) => {
    swallowClick = false;
    if (e.originalEvent.button !== 0 || tool !== 'route') return;
    const w = hit(e.point, ['route-wpts']), m = w ? null : hit(e.point, ['route-mid']);
    if (!w && !m) return;
    e.preventDefault();   // don't pan
    drag = { i: w ? w.properties.i : m.properties.i + 1, insert: !w, from: e.point, moved: false };
  });
  map.on('mousemove', (e) => {
    if (drag) {
      if (!drag.moved) {
        if (Math.hypot(e.point.x - drag.from.x, e.point.y - drag.from.y) < 4) return;
        snapshot(); drag.moved = true;
        if (drag.insert) state.plan.waypoints.splice(drag.i, 0, { ...ll(e), name: '' });
      }
      Object.assign(state.plan.waypoints[drag.i], ll(e)); drawData();
      return;
    }
    if (tool === 'route') map.getCanvas().style.cursor = hit(e.point, ['route-wpts', 'route-mid']) ? 'grab' : 'crosshair';
    else if (!tool) map.getCanvas().style.cursor = hit(e.point, ['marks', 'hazards']) ? 'pointer' : '';
    readCursor(ll(e));
  });
  addEventListener('mouseup', () => {
    if (!drag) return;
    const moved = drag.moved; drag = null;
    swallowClick = true;     // the click that follows isn't a new waypoint
    if (moved) planChanged();
  });
  map.on('click', (e) => {
    if (swallowClick) { swallowClick = false; return; }
    if (tool === 'route') { addWaypoint(ll(e)); return; }
    if (tool === 'mark') { addMark(ll(e)); return; }
    const mk = hit(e.point, ['marks']);
    if (mk) { const m = state.marks.find((x) => x.id === mk.properties.id); if (m) showMark(m); return; }
    const hz = hit(e.point, ['hazards']);
    if (hz) { showHazard(hz); return; }
    showZones(ll(e), false);
  });
  map.on('contextmenu', (e) => {
    e.preventDefault();
    const p = ll(e), pos = C.fmtPos(p);
    const wp = hit(e.point, ['route-wpts']);
    const items = [];
    if (wp) items.push(['Delete this waypoint', () => { snapshot(); state.plan.waypoints.splice(wp.properties.i, 1); planChanged(); }]);
    items.push(['Add waypoint here', () => { addWaypoint(p); setTab('routes'); }], ['Add mark here', () => addMark(p)],
      ['Zones here', () => showZones(p, true)],
      ['Copy position', () => navigator.clipboard.writeText(pos).then(() => toast('Position copied'), () => toast(pos))]);
    openMenu(e.originalEvent.clientX, e.originalEvent.clientY, pos, items);
  });
  addEventListener('keydown', (e) => {
    if ($('dialog').open) return;
    const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(e.target.tagName) && e.target.type !== 'range' && e.target.type !== 'checkbox';
    if (e.key === 'Escape') { if (!$('menu').hidden) $('menu').hidden = true; else if (tool) setTool(tool); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z' && !typing) { e.preventDefault(); undoLast(); }
  });

  function showHazard(f) {
    const p = f.properties, [lon, lat] = f.geometry.coordinates;
    dialog(p.name || HAZARD_TEXT[p.kind] || 'Navigation mark', [HAZARD_TEXT[p.kind] || 'Navigation mark', p.detail || '',
      p.virtual ? 'Virtual mark: AIS only, nothing physical in the water' : '', p.owner ? `Maintained by: ${p.owner}` : '', C.fmtPos({ lat, lon }),
      p.src === 'dot' ? 'Source: WA Department of Transport, Navigation Aids (DOT-004), CC BY 4.0' : 'Source: © OpenStreetMap contributors (OpenSeaMap), ODbL. Position may be approximate.']
      .filter(Boolean).join('\n'));
  }
  async function showZones(p, always) {
    if (!reader) return;
    let r;
    try { r = await reader.at(p); } catch (e) { return; }
    if (!r.zones.length) { if (always) toast('No zones here'); return; }
    const body = [];
    for (const z of r.zones) {
      body.push(el('p', {}, el('strong', {}, `${{ notake: 'NO FISHING', restricted: 'Restricted' }[z.zone_class] || 'Marine park'}: ${z.name || '?'}`),
        z.detail ? `\n${z.detail}` : '', z.source ? `\n${z.source}` : '',
        /^https?:\/\//i.test(z.link || '') ? [document.createElement('br'), el('a', { href: z.link, target: '_blank', rel: 'noopener noreferrer' }, z.link)] : ''));
    }
    dialog('Zones here', body);
  }

  let cursorPos = null, cursorBusy = false;
  async function readCursor(p) {
    $('s-pos').textContent = C.fmtPos(p);
    cursorPos = p;
    if (!reader || cursorBusy) return;
    cursorBusy = true;
    try {
      while (cursorPos) {
        const q = cursorPos; cursorPos = null;
        const r = await reader.at(q);
        $('s-depth').textContent = C.depthText(r);
        const z = r.zones.find((x) => x.zone_class === 'notake') || r.zones.find((x) => x.zone_class === 'restricted');
        $('s-zone').textContent = z ? `${z.zone_class === 'notake' ? 'NO FISHING' : 'Restricted'}: ${z.name}` : '';
        $('s-zone').className = z ? z.zone_class : '';
      }
    } catch (e) { $('s-depth').textContent = '--'; }
    cursorBusy = false;
  }

  let dragDepth = 0;
  addEventListener('dragenter', (e) => { e.preventDefault(); dragDepth++; document.body.classList.add('drop'); });
  addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; document.body.classList.remove('drop'); } });
  addEventListener('dragover', (e) => e.preventDefault());
  addEventListener('drop', (e) => {
    e.preventDefault(); dragDepth = 0; document.body.classList.remove('drop');
    const f = e.dataTransfer.files[0]; if (f) openAny(f);
  });

  // ---- Start --------------------------------------------------------------------------------------------
  setTab(state.tab);
  (async () => {
    for (const kind of ['chart', 'relief']) {
      try { const f = await idb.get(kind); if (f) await useFile(f.blob, f.name); } catch (e) { /* not kept, or storage is blocked */ }
    }
    if (files.chart || files.relief) rebuildStyle();
    renderAll();
  })();
  globalThis.__planner = { map, state, files, get reader() { return reader; } };   // used by the automated tests
})();
