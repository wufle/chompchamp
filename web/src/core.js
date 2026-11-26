// Pure logic with no DOM: geodesy, data formats, chart reading, route check.
// Mirrors Geo.kt, Storage.kt and UserData.kt in the Android app. Also loaded by web/test.mjs.
const Core = (() => {
  // ---- Geodesy (nautical miles, degrees true) --------------------------------------
  const R_NM = 3440.065, M_PER_NM = 1852;
  const rad = (d) => d * Math.PI / 180, deg = (r) => r * 180 / Math.PI;

  function distanceNm(a, b) {
    const dLat = rad(b.lat - a.lat), dLon = rad(b.lon - a.lon);
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
    return 2 * R_NM * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
  }

  function bearing(a, b) {
    const y = Math.sin(rad(b.lon - a.lon)) * Math.cos(rad(b.lat));
    const x = Math.cos(rad(a.lat)) * Math.sin(rad(b.lat)) -
      Math.sin(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.cos(rad(b.lon - a.lon));
    return (deg(Math.atan2(y, x)) + 360) % 360;
  }

  const routeLengthNm = (pts) => pts.slice(1).reduce((s, p, i) => s + distanceNm(pts[i], p), 0);

  /** Metres from point p to the segment a-b (flat approximation, fine for a few miles). */
  function segDistM(p, a, b) {
    const k = Math.cos(rad(p.lat)) * 60 * M_PER_NM, m = 60 * M_PER_NM;
    const ax = (a.lon - p.lon) * k, ay = (a.lat - p.lat) * m;
    const bx = (b.lon - p.lon) * k, by = (b.lat - p.lat) * m;
    const dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
    const t = len2 ? Math.min(1, Math.max(0, -(ax * dx + ay * dy) / len2)) : 0;
    return Math.hypot(ax + t * dx, ay + t * dy);
  }

  // ---- Formatting --------------------------------------------------------------------
  const fmtNm = (nm) => nm < 0.1 ? `${Math.round(nm * M_PER_NM)} m` : `${nm.toFixed(nm < 10 ? 2 : 1)} nm`;
  const fmtBearing = (d) => `${String(Math.round((d + 360) % 360) % 360).padStart(3, '0')}°`;
  function fmtDuration(h) {
    if (!isFinite(h) || h < 0 || h > 99) return '--';
    const m = Math.round(h * 60);
    return m >= 60 ? `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m` : `${m}m`;
  }
  function fmtDdm(v, isLat) {
    const hemi = isLat ? (v < 0 ? 'S' : 'N') : (v < 0 ? 'W' : 'E');
    // Round first, so 59.9996' becomes the next degree instead of 60.000'.
    const total = Math.round(Math.abs(v) * 60000), d = Math.floor(total / 60000), m = (total % 60000) / 1000;
    return `${String(d).padStart(isLat ? 2 : 3, '0')}°${m.toFixed(3).padStart(6, '0')}'${hemi}`;
  }
  const fmtPos = (p) => `${fmtDdm(p.lat, true)}  ${fmtDdm(p.lon, false)}`;

  function uuid() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
    const b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
    return [...b].map((x, i) => ([4, 6, 8, 10].includes(i) ? '-' : '') + x.toString(16).padStart(2, '0')).join('');
  }

  // ---- Data: validate everything that comes from a file ----------------------------------
  const str = (v, max = 200) => (v === undefined || v === null ? '' : String(v)).slice(0, max);
  function pos(o) {
    const lat = Number(o.lat), lon = Number(o.lon);
    if (!isFinite(lat) || !isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new Error('bad position');
    return { lat, lon };
  }
  const emptyRoute = () => ({ id: uuid(), name: '', waypoints: [] });
  const cleanRoute = (o) => ({
    id: str(o.id) || uuid(), name: str(o.name),
    waypoints: (o.waypoints || []).map((w) => ({ ...pos(w), name: str(w.name) })),
  });
  function cleanMark(o) {
    const m = { id: str(o.id) || uuid(), ...pos(o), name: str(o.name), time: Math.round(Number(o.time)) || Date.now() };
    if (o.depth !== undefined && o.depth !== null) m.depth = str(o.depth, 40);
    // Photos stay on the phone; keep their ids so a round trip doesn't detach them.
    if (Array.isArray(o.photos) && o.photos.length) m.photos = o.photos.map((p) => str(p, 80));
    // No group key = a file from before groups existed, so a merge keeps the group we have.
    if (o.group !== undefined) m.group = groupName(o.group);
    return m;
  }

  // ---- Mark groups (same rules as MarkGroups.kt; "" = Ungrouped) ------------------------------
  const GROUP_COLORS = [['Yellow', '#FDD835'], ['Orange', '#FB8C00'], ['Red', '#E53935'], ['Pink', '#EC407A'], ['Purple', '#8E24AA'],
    ['Blue', '#1E88E5'], ['Teal', '#00ACC1'], ['Green', '#43A047'], ['White', '#FFFFFF'], ['Grey', '#757575']];
  function groupName(s) {
    const n = str(s, 400).trim().replace(/\s+/g, ' ').slice(0, 60).trim();
    return n.toLowerCase() === 'ungrouped' ? '' : n;
  }
  const cleanGroup = (o) => ({ name: groupName(o.name), color: /^#[0-9a-f]{6}$/i.test(o.color || '') ? o.color : GROUP_COLORS[0][1], visible: o.visible !== false });
  const findGroup = (groups, name) => groups.find((g) => g.name.toLowerCase() === name.toLowerCase());
  /** Get or create; returns the stored name. Changes [groups]. */
  function ensureGroup(groups, raw) {
    const name = groupName(raw), have = findGroup(groups, name);
    if (have) return have.name;
    const used = groups.map((g) => g.color), all = GROUP_COLORS.map((c) => c[1]);
    const color = name === '' ? all[0] : all.find((c) => !used.includes(c)) || all[groups.length % all.length];
    groups.push({ name, color, visible: true });
    return name;
  }
  /** Make sure Ungrouped and every group in use exist, and sort them. Changes both lists. */
  function tidyGroups(groups, marks) {
    ensureGroup(groups, '');
    for (const m of marks) m.group = ensureGroup(groups, m.group || '');
    groups.sort((a, b) => (a.name === '') !== (b.name === '') ? (a.name === '' ? -1 : 1) : a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  }
  function mergeMarks(list, incoming) {
    return mergeById(list, incoming.map((m) => {
      if (m.group !== undefined) return m;
      const old = list.find((x) => x.id === m.id);
      return old && old.group !== undefined ? { ...m, group: old.group } : m;
    }));
  }

  function parseBackup(text) {
    const o = JSON.parse(text);
    if (!o || o.app !== 'ChompChamp') throw new Error("that isn't a ChompChamp backup");
    const track = [];
    const a = Array.isArray(o.track3) ? o.track3 : Array.isArray(o.track) ? o.track : [];
    const step = Array.isArray(o.track3) ? 3 : 2;
    for (let i = 0; i + step <= a.length; i += step) {
      track.push({ ...pos({ lat: a[i], lon: a[i + 1] }), t: step === 3 ? Number(a[i + 2]) || 0 : 0 });
    }
    return {
      routes: (o.routes || []).map(cleanRoute),
      plan: o.plan ? cleanRoute(o.plan) : null,
      marks: (o.marks || []).map(cleanMark),
      groups: (o.markGroups || []).map(cleanGroup),
      track,
    };
  }

  /** For the phone: routes, plan and marks. Never the track (the phone appends it) or settings. */
  function exportBackup(s) {
    return JSON.stringify({
      app: 'ChompChamp', format: 1, exported: new Date().toISOString().slice(0, 19) + 'Z',
      routes: s.routes.map(cleanRoute), plan: cleanRoute(s.plan), marks: s.marks.map(cleanMark),
      markGroups: (s.groups || []).map(cleanGroup),
    }, null, 1);
  }

  const esc = (t) => String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const iso = (t) => new Date(t).toISOString().slice(0, 19) + 'Z';
  function exportGpx(s) {
    const out = ['<?xml version="1.0" encoding="UTF-8"?>',
      '<gpx version="1.1" creator="ChompChamp" xmlns="http://www.topografix.com/GPX/1/1">',
      `  <metadata><time>${iso(Date.now())}</time></metadata>`];
    for (const m of s.marks) {
      out.push(`  <wpt lat="${m.lat}" lon="${m.lon}"><time>${iso(m.time)}</time><name>${esc(m.name)}</name>` +
        (m.depth ? `<desc>Depth (est.) ${esc(m.depth)} m</desc>` : '') + '<sym>Fishing Area</sym>' + (m.group ? `<type>${esc(m.group)}</type>` : '') + '</wpt>');
    }
    const routes = [...s.routes];
    if (s.plan.waypoints.length && !routes.some((r) => r.id === s.plan.id)) {
      routes.push({ ...s.plan, name: s.plan.name || 'Current plan' });
    }
    for (const r of routes) {
      out.push(`  <rte><name>${esc(r.name || 'Route')}</name>`);
      for (const w of r.waypoints) out.push(`    <rtept lat="${w.lat}" lon="${w.lon}"><name>${esc(w.name)}</name></rtept>`);
      out.push('  </rte>');
    }
    out.push('</gpx>', '');
    return out.join('\n');
  }

  /** [doc] is a parsed XML Document. wpt -> marks, rte -> routes, trk -> track, with new ids. */
  function parseGpx(doc) {
    if (doc.getElementsByTagName('parsererror').length) throw new Error("that isn't a valid GPX file");
    const all = (el, tag) => [...el.getElementsByTagNameNS('*', tag)];
    const child = (el, tag) => {
      const c = [...el.childNodes].find((n) => n.nodeType === 1 && n.localName === tag);
      return c ? c.textContent.trim() : '';
    };
    const at = (el) => pos({ lat: el.getAttribute('lat'), lon: el.getAttribute('lon') });
    const time = (el) => Date.parse(child(el, 'time')) || 0;
    const marks = all(doc, 'wpt').map((el, i) =>
      cleanMark({ ...at(el), name: child(el, 'name') || `Mark ${i + 1}`, time: time(el) || Date.now(), group: child(el, 'type') }));
    const routes = all(doc, 'rte').map((el) => cleanRoute({
      name: child(el, 'name') || 'Imported route',
      waypoints: all(el, 'rtept').map((p, i) => ({ ...at(p), name: child(p, 'name') || `WP${i + 1}` })),
    })).filter((r) => r.waypoints.length);
    const track = all(doc, 'trkpt').map((el) => ({ ...at(el), t: time(el) }));
    if (!marks.length && !routes.length && !track.length) throw new Error('no waypoints, routes or tracks found');
    return { routes, plan: null, marks, groups: [], track };
  }

  function mergeById(list, incoming) {
    const out = [...list];
    for (const x of incoming) {
      const i = out.findIndex((y) => y.id === x.id);
      if (i >= 0) out[i] = x; else out.push(x);
    }
    return out;
  }

  // ---- Vector tile reading (just enough MVT to look up polygons) -----------------------------
  function pbf(buf, start = 0, end = buf.length) {
    let p = start;
    const varint = () => {
      let r = 0, m = 1, b;
      do { b = buf[p++]; r += (b & 127) * m; m *= 128; } while (b >= 128);
      return r;
    };
    return {
      more: () => p < end,
      varint,
      tag() { const t = varint(); return [Math.floor(t / 8), t & 7]; },
      sub() { const n = varint(); const s = p; p += n; return [s, p]; },
      skip(w) { if (w === 0) varint(); else if (w === 1) p += 8; else if (w === 2) { const n = varint(); p += n; } else if (w === 5) p += 4; else throw new Error('bad tile'); },
      f32() { const v = new DataView(buf.buffer, buf.byteOffset + p, 4).getFloat32(0, true); p += 4; return v; },
      f64() { const v = new DataView(buf.buffer, buf.byteOffset + p, 8).getFloat64(0, true); p += 8; return v; },
    };
  }
  const zz = (n) => (n % 2 ? -(n + 1) / 2 : n / 2);
  const utf8 = new TextDecoder();

  function readValue(buf, s, e) {
    const r = pbf(buf, s, e);
    let v = null;
    while (r.more()) {
      const [f, w] = r.tag();
      if (f === 1) { const [a, b] = r.sub(); v = utf8.decode(buf.subarray(a, b)); }
      else if (f === 2) v = r.f32();
      else if (f === 3) v = r.f64();
      else if (f === 4 || f === 5) v = r.varint();
      else if (f === 6) v = zz(r.varint());
      else if (f === 7) v = !!r.varint();
      else r.skip(w);
    }
    return v;
  }

  /** -> { layerName: { extent, features: [{ props, rings, box }] } } for polygon layers in [want]. */
  function decodeTile(buf, want) {
    const layers = {};
    const t = pbf(buf);
    while (t.more()) {
      const [f, w] = t.tag();
      if (f !== 3) { t.skip(w); continue; }
      const [ls, le] = t.sub();
      const l = pbf(buf, ls, le);
      let name = '', extent = 4096;
      const keys = [], vals = [], feats = [];
      while (l.more()) {
        const [lf, lw] = l.tag();
        if (lf === 1) { const [a, b] = l.sub(); name = utf8.decode(buf.subarray(a, b)); }
        else if (lf === 2) feats.push(l.sub());
        else if (lf === 3) { const [a, b] = l.sub(); keys.push(utf8.decode(buf.subarray(a, b))); }
        else if (lf === 4) { const [a, b] = l.sub(); vals.push(readValue(buf, a, b)); }
        else if (lf === 5) extent = l.varint();
        else l.skip(lw);
      }
      if (!want.includes(name)) continue;
      const features = [];
      for (const [fs, fe] of feats) {
        const r = pbf(buf, fs, fe);
        let type = 0, geom = null;
        const props = {};
        while (r.more()) {
          const [ff, fw] = r.tag();
          if (ff === 2) {
            const [a, b] = r.sub(); const q = pbf(buf, a, b);
            while (q.more()) { const k = keys[q.varint()]; props[k] = vals[q.varint()]; }
          } else if (ff === 3) type = r.varint();
          else if (ff === 4) geom = r.sub();
          else r.skip(fw);
        }
        if (type !== 3 || !geom) continue;
        const q = pbf(buf, geom[0], geom[1]);
        const rings = []; const box = [Infinity, Infinity, -Infinity, -Infinity];
        let x = 0, y = 0, ring = null;
        while (q.more()) {
          const c = q.varint(), cmd = c & 7, n = c >> 3;
          if (cmd === 7) continue;
          for (let i = 0; i < n; i++) {
            x += zz(q.varint()); y += zz(q.varint());
            if (cmd === 1) { ring = []; rings.push(ring); }
            ring.push(x, y);
            if (x < box[0]) box[0] = x; if (y < box[1]) box[1] = y;
            if (x > box[2]) box[2] = x; if (y > box[3]) box[3] = y;
          }
        }
        features.push({ props, rings, box });
      }
      layers[name] = { extent, features };
    }
    return layers;
  }

  function inside(f, x, y) {
    if (x < f.box[0] || x > f.box[2] || y < f.box[1] || y > f.box[3]) return false;
    let c = false;
    for (const r of f.rings) {
      const n = r.length;
      for (let i = 0, j = n - 2; i < n; j = i, i += 2) {
        if ((r[i + 1] > y) !== (r[j + 1] > y) && x < (r[j] - r[i]) * (y - r[i + 1]) / (r[j + 1] - r[i + 1]) + r[i]) c = !c;
      }
    }
    return c;
  }

  /** Looks things up in the chart file itself, so it works for places that aren't on screen. */
  class ChartReader {
    /** hasSource: the chart has a "source" layer (0.17.12 on), so no source feature at sea means the 250 m grid. */
    constructor(getTile, zoom = 14, hasSource = false) { this.getTile = getTile; this.z = zoom; this.hasSource = hasSource; this.cache = new Map(); }

    tile(tx, ty) {
      const key = `${tx}/${ty}`;
      let t = this.cache.get(key);
      if (t) { this.cache.delete(key); this.cache.set(key, t); return t; }
      t = this.getTile(this.z, tx, ty).then((d) => d ? decodeTile(new Uint8Array(d), ['land', 'water', 'depth', 'zones', 'source']) : null);
      t.catch(() => this.cache.delete(key));
      this.cache.set(key, t);
      if (this.cache.size > 300) this.cache.delete(this.cache.keys().next().value);
      return t;
    }

    /** -> { known, land, depth: [dmin, dmax] | null, zones: [props], source: props | null (charts before 0.17.12) } */
    async at(p) {
      const n = 2 ** this.z;
      const x = (p.lon + 180) / 360 * n;
      const y = (1 - Math.asinh(Math.tan(rad(p.lat))) / Math.PI) / 2 * n;
      const t = await this.tile(Math.floor(x), Math.floor(y));
      const res = { known: !!t, land: false, depth: null, zones: [], source: null };
      if (!t) return res;
      const hits = (name) => {
        const l = t[name];
        if (!l) return [];
        const lx = (x % 1) * l.extent, ly = (y % 1) * l.extent;
        return l.features.filter((f) => inside(f, lx, ly));
      };
      res.land = hits('land').length > 0;
      res.source = hits('source')[0]?.props || null;
      const d = hits('depth').map((f) => [Number(f.props.dmin), Number(f.props.dmax)]);
      // Bands can overlap by a hair at their edges: believe the shallower one.
      if (d.length) res.depth = d.reduce((a, b) => (b[0] < a[0] ? b : a));
      if (!res.source && this.hasSource && !res.land && res.depth) res.source = NATIONAL_GRID;
      const seen = new Set();
      for (const f of hits('zones')) {
        const k = JSON.stringify(f.props);
        if (!seen.has(k)) { seen.add(k); res.zones.push(f.props); }
      }
      return res;
    }
  }

  /** Where a depth comes from outside the detailed areas (same as ChartReader.NATIONAL_GRID in the app). */
  const NATIONAL_GRID = { src: 'AusBathyTopo 250 m national grid (Geoscience Australia), compiled from charts and surveys', acc: 4 };

  /** Where a depth came from and how far to trust it (mirrors ChartReader.sourceLines in the app). */
  function sourceLines(src) {
    if (!src || src.acc === undefined) return [];
    const acc = Number(src.acc), near = src.near === undefined ? null : Number(src.near);
    const how = {
      1: 'Good: measured by a survey',
      2: 'Fair: an older survey, or estimated close to measured depths',
      3: 'Poor: estimated between survey lines. Reefs between them can be missing',
      4: 'Rough: a 250 m grid compiled from charts and surveys. Reefs and banks smaller than about 500 m can be missing, and it can be several metres out near the coast',
    }[acc] || 'None: no survey here, so the depth is unknown';
    const dist = near === null ? null : near <= 100 ? 'within 100 m' : near >= 5000 ? 'more than 2 km away' : `up to ${near} m away`;
    return [`Source: ${src.src || 'unknown'}`, `Accuracy: ${how}.`, dist ? `Nearest measured depth: ${dist}.` : null].filter(Boolean);
  }

  function depthText(r) {
    if (!r || !r.known) return '--';
    if (r.land) return 'land';
    if (!r.depth) return '--';
    return r.depth[1] >= 2000 ? `>${r.depth[0]} m` : `${r.depth[0]}–${r.depth[1]} m`;
  }

  const HAZARD_KINDS = ['rock', 'wreck', 'obstruction', 'danger'];
  const HAZARD_NEAR_M = 100;

  /**
   * Walks every leg through the chart. -> [{ level: 'stop'|'warn'|'info', text, at }]
   * Samples every [stepM] metres, always at least both ends of the leg.
   */
  async function checkRoute(reader, waypoints, safetyM, hazards, { stepM = 10, onProgress, cancelled } = {}) {
    const issues = [];
    for (let i = 0; i + 1 < waypoints.length; i++) {
      const a = waypoints[i], b = waypoints[i + 1];
      const label = `Leg ${i + 1} (${a.name || `WP${i + 1}`} → ${b.name || `WP${i + 2}`})`;
      const steps = Math.max(1, Math.ceil(distanceNm(a, b) * M_PER_NM / stepM));
      let land = null, unknown = null, shallow = null;
      const zones = new Map();
      for (let s = 0; s <= steps; s++) {
        const t = s / steps, p = { lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t };
        const r = await reader.at(p);
        if (r.land) land = land || p;
        else if (!r.depth) unknown = unknown || p;
        if (r.depth && !r.land && (!shallow || r.depth[0] < shallow.min)) shallow = { min: r.depth[0], p };
        for (const z of r.zones) if (z.zone_class === 'notake' && !zones.has(z.name)) zones.set(z.name, p);
        if (s % 500 === 0) {
          if (onProgress) onProgress((i + t) / (waypoints.length - 1));
          await new Promise((ok) => setTimeout(ok));
          if (cancelled && cancelled()) return null;
        }
      }
      if (land) issues.push({ level: 'stop', text: `${label} crosses land`, at: land });
      if (shallow && shallow.min < safetyM) {
        issues.push({ level: 'warn', text: `${label}: depth may be under ${safetyM} m (shallowest band starts at ${shallow.min} m)`, at: shallow.p });
      }
      if (unknown) issues.push({ level: 'warn', text: `${label}: no depth data for part of this leg`, at: unknown });
      for (const h of hazards) {
        if (!HAZARD_KINDS.includes(h.properties.kind)) continue;
        const [lon, lat] = h.geometry.coordinates, p = { lat, lon };
        const m = segDistM(p, a, b);
        if (m <= HAZARD_NEAR_M) {
          const what = { rock: 'rock', wreck: 'wreck', obstruction: 'obstruction', danger: 'isolated danger mark' }[h.properties.kind];
          issues.push({ level: 'warn', text: `${label}: passes ${Math.round(m)} m from a ${what}${h.properties.name ? ` (${h.properties.name})` : ''}`, at: p });
        }
      }
      for (const [name, p] of zones) issues.push({ level: 'info', text: `${label} passes through no-fishing zone: ${name} (transit OK, no fishing)`, at: p });
    }
    return issues;
  }

  // ---- Place names (mirrors Places.kt) ------------------------------------------------------------
  const PLACE_KINDS = { city: 'City', town: 'Town', village: 'Village', suburb: 'Suburb', hamlet: 'Locality', marina: 'Marina',
    harbour: 'Harbour', ramp: 'Boat ramp', jetty: 'Jetty', lighthouse: 'Lighthouse', island: 'Island', cape: 'Point', bay: 'Bay',
    beach: 'Beach', reef: 'Reef', bank: 'Bank', water: 'Lake or estuary' };
  const placeKind = (k) => PLACE_KINDS[k] || 'Place';
  /** The zoom a place's label first shows at, by rank. */
  const placeZoom = (rank) => ({ 1: 5, 2: 7, 3: 9, 4: 10.5, 5: 12 })[rank] || 13.5;
  /** Lower case, no accents or punctuation, so "Hillarys", "hillary's" and "Hillarys," all match. */
  const fold = (s) => String(s).normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase()
    .replace(/['’`.]/g, '').replace(/[^a-z0-9]+/g, ' ').trim();

  /** Places (GeoJSON features) with every word of the query in their name, nearest town or kind; best first. */
  function searchPlaces(features, query, from, limit = 60) {
    const q = fold(query);
    if (!q) return [];
    const words = q.split(' ');
    const hits = [];
    for (const f of features) {
      const p = f.properties;
      if (!f._s) Object.defineProperty(f, '_s', { value: fold([p.name, p.near || '', placeKind(p.kind), p.kind === 'ramp' ? 'ramp launch slipway' : ''].join(' ')) });
      if (!words.every((w) => f._s.includes(w))) continue;
      const n = fold(p.name);
      const score = n === q ? 0 : n.startsWith(q) ? 1 : words.every((w) => ` ${n} `.includes(` ${w}`)) ? 2 : 3;
      const [lon, lat] = f.geometry.coordinates;
      hits.push({ f, score, band: Math.floor((p.rank || 6) / 2), dist: from ? distanceNm(from, { lat, lon }) : 0 });
    }
    hits.sort((a, b) => a.score - b.score || a.band - b.band || a.dist - b.dist || (a.f.properties.name < b.f.properties.name ? -1 : 1));
    return hits.slice(0, limit).map((h) => h.f);
  }

  return { distanceNm, bearing, routeLengthNm, segDistM, fmtNm, fmtBearing, fmtDuration, fmtDdm, fmtPos, uuid,
    emptyRoute, cleanRoute, cleanMark, parseBackup, exportBackup, exportGpx, parseGpx, mergeById, mergeMarks,
    GROUP_COLORS, groupName, cleanGroup, findGroup, ensureGroup, tidyGroups, decodeTile, ChartReader, depthText, checkRoute, pos,
    fold, placeKind, placeZoom, searchPlaces, sourceLines };
})();
if (typeof globalThis !== 'undefined') globalThis.ChompCore = Core;
