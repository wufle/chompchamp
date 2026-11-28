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
      catches: (o.catches || []).map(cleanCatch),
      track,
    };
  }

  /** For the phone: routes, plan and marks. Never the track (the phone appends it) or settings. */
  function exportBackup(s) {
    return JSON.stringify({
      app: 'ChompChamp', format: 1, exported: new Date().toISOString().slice(0, 19) + 'Z',
      routes: s.routes.map(cleanRoute), plan: cleanRoute(s.plan), marks: s.marks.map(cleanMark),
      markGroups: (s.groups || []).map(cleanGroup),
      // Catches only if there are any: the phone merges them into its catch log by id.
      ...(s.catches && s.catches.length ? { catches: s.catches.map(cleanCatch) } : {}),
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
    if (s.track && s.track.length > 1) {
      out.push('  <trk><name>Track</name><trkseg>');
      for (const p of s.track) out.push(`    <trkpt lat="${p.lat}" lon="${p.lon}">${p.t ? `<time>${iso(p.t)}</time>` : ''}</trkpt>`);
      out.push('  </trkseg></trk>');
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
    return { routes, plan: null, marks, groups: [], catches: [], track };
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

  // ---- Navigation (mirrors Navigator.kt) ------------------------------------------------------------
  /** Cross-track distance in nm from the leg a -> b: positive = right of the track. */
  function xteNm(p, a, b) {
    const d13 = distanceNm(a, p) / R_NM, t13 = rad(bearing(a, p)), t12 = rad(bearing(a, b));
    return Math.asin(Math.max(-1, Math.min(1, Math.sin(d13) * Math.sin(t13 - t12)))) * R_NM;
  }
  /** Speed (kn) and course (°T) between two timed fixes, or null if too close in time or space. */
  function sogCog(a, b) {
    const dt = (b.t - a.t) / 3600000;
    if (!(dt > 0)) return null;
    const d = distanceNm(a, b);
    return { sog: d / dt, cog: d * M_PER_NM >= 3 ? bearing(a, b) : null };
  }
  const compass = (deg) => ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'][Math.round((((deg % 360) + 360) % 360) / 22.5) % 16];
  /** A ring of [n] points [lon, lat] around p, [m] metres out (for circles on the map). */
  function circle(p, m, n = 64) {
    const out = [];
    for (let i = 0; i <= n; i++) {
      const a = 2 * Math.PI * i / n;
      out.push([p.lon + (m * Math.sin(a)) / (M_PER_NM * 60 * Math.cos(rad(p.lat))), p.lat + (m * Math.cos(a)) / (M_PER_NM * 60)]);
    }
    return out;
  }

  // ---- Tides (mirrors Tides.kt: EOT20 constituents, worked out on the device) ------------------------------
  const NODAL = {
    none: () => [1, 0],
    m2: (n) => [1 - 0.037 * Math.cos(n), -2.1 * Math.sin(n)],
    o1: (n) => [1.009 + 0.187 * Math.cos(n) - 0.015 * Math.cos(2 * n), 10.8 * Math.sin(n) - 1.3 * Math.sin(2 * n) + 0.2 * Math.sin(3 * n)],
    k1: (n) => [1.006 + 0.115 * Math.cos(n) - 0.009 * Math.cos(2 * n), -8.9 * Math.sin(n) + 0.7 * Math.sin(2 * n)],
    k2: (n) => [1.024 + 0.286 * Math.cos(n) + 0.008 * Math.cos(2 * n), -17.7 * Math.sin(n) + 0.7 * Math.sin(2 * n)],
    j1: (n) => [1.013 + 0.168 * Math.cos(n) - 0.017 * Math.cos(2 * n), -12.9 * Math.sin(n) + 1.3 * Math.sin(2 * n) - 0.2 * Math.sin(3 * n)],
    mf: (n) => [1.043 + 0.414 * Math.cos(n), -23.7 * Math.sin(n) + 2.7 * Math.sin(2 * n) - 0.4 * Math.sin(3 * n)],
    mm: (n) => [1 - 0.130 * Math.cos(n), 0],
    m4: (n) => { const [f, u] = NODAL.m2(n); return [f * f, 2 * u]; },
  };
  // Doodson numbers (tau, s, h, p, N', p1), phase offset, nodal correction. The diurnal offsets were checked
  // against tide gauges in the app (Tides.kt): don't change them without checking again.
  const CONS = {
    m2: [[2, 0, 0, 0, 0, 0], 0, 'm2'], s2: [[2, 2, -2, 0, 0, 0], 0, 'none'], n2: [[2, -1, 0, 1, 0, 0], 0, 'm2'],
    k2: [[2, 2, 0, 0, 0, 0], 0, 'k2'], '2n2': [[2, -2, 0, 2, 0, 0], 0, 'm2'], t2: [[2, 2, -3, 0, 0, 1], 0, 'none'],
    k1: [[1, 1, 0, 0, 0, 0], -90, 'k1'], o1: [[1, -1, 0, 0, 0, 0], 90, 'o1'], p1: [[1, 1, -2, 0, 0, 0], 90, 'none'],
    q1: [[1, -2, 0, 1, 0, 0], 90, 'o1'], j1: [[1, 2, 0, -1, 0, 0], -90, 'j1'], m4: [[4, 0, 0, 0, 0, 0], 0, 'm4'],
    mf: [[0, 2, 0, 0, 0, 0], 0, 'mf'], mm: [[0, 1, 0, -1, 0, 0], 0, 'mm'], ssa: [[0, 0, 2, 0, 0, 0], 0, 'none'], sa: [[0, 0, 1, 0, 0, 0], 0, 'none'],
  };
  class Tides {
    /** [json] is the app's assets/tides.json. */
    constructor(json) {
      this.cons = json.constituents.map((n) => { const c = CONS[n]; if (!c) throw new Error(`unknown constituent ${n}`); return c; });
      this.stations = json.stations.map(([lat, lon, near, amp, pha]) => ({ lat, lon, near, amp: amp.map((a) => a / 100), pha }));
    }
    nearest(lat, lon, maxKm = 60) {
      const k = Math.cos(rad(lat));
      let best = null, bd = Infinity;
      for (const s of this.stations) { const d = (s.lat - lat) ** 2 + ((s.lon - lon) * k) ** 2; if (d < bd) { bd = d; best = s; } }
      return best && distanceNm({ lat, lon }, best) * 1.852 <= maxKm ? best : null;
    }
    height(st, t) {
      const days = t / 86400000 - 10957.5;
      const s = 218.3164 + 13.17639648 * days, h = 280.4661 + 0.98564736 * days, p = 83.3535 + 0.11140353 * days;
      const nDeg = 125.0445 - 0.05295377 * days, p1 = 282.9384 + 0.0000471 * days, n = rad(nDeg);
      const hourUt = (((t % 86400000) + 86400000) % 86400000) / 3600000;
      const tau = 15 * hourUt + 180 + h - s;
      let out = 0;
      this.cons.forEach(([d, off, nod], i) => {
        const [f, u] = NODAL[nod](n);
        const v = d[0] * tau + d[1] * s + d[2] * h + d[3] * p - d[4] * nDeg + d[5] * p1 + off;
        out += f * st.amp[i] * Math.cos(rad(v + u - st.pha[i]));
      });
      return out;
    }
    /** Highs and lows between [from] and [to]: [{ time, metres, high }]. */
    turns(st, from, to) {
      const step = 600000, out = [];
      let t0 = from - step, h0 = this.height(st, t0), t1 = from, h1 = this.height(st, t1);
      while (t1 <= to) {
        const t2 = t1 + step, h2 = this.height(st, t2);
        if ((h1 > h0 && h1 >= h2) || (h1 < h0 && h1 <= h2)) {
          const den = h0 - 2 * h1 + h2, dt = Math.abs(den) > 1e-9 ? 0.5 * (h0 - h2) / den : 0;
          const tt = t1 + Math.round(dt * step);
          if (tt >= from && tt <= to) out.push({ time: tt, metres: this.height(st, tt), high: h1 > h0 });
        }
        t0 = t1; h0 = h1; t1 = t2; h1 = h2;
      }
      return out;
    }
  }

  // ---- Bag and size limits (mirrors BagLimits.kt and CatchLimits.kt) ---------------------------------------
  const LIMIT_AREAS = [['north', 'North Coast'], ['gascoyne', 'Gascoyne Coast'], ['west', 'West Coast'], ['abrolhos', 'Abrolhos Islands'], ['south', 'South Coast']];
  const areaLabel = (k) => (LIMIT_AREAS.find((a) => a[0] === k) || [k, k])[1];
  /** Lower case letters and digits, single spaces, no apostrophes or brackets. */
  const plain = (s) => String(s).toLowerCase().replace(/'/g, '').replace(/[^a-z0-9]+/g, ' ').trim();
  const dayOf = (t) => { const d = new Date(t); return (d.getMonth() + 1) * 100 + d.getDate(); };
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const dayName = (d) => `${d % 100} ${MONTHS[Math.floor(d / 100) - 1]}`;
  const parseDay = (s) => Number(s.split('-')[0]) * 100 + Number(s.split('-')[1]);

  class BagLimits {
    /** [json] = bag_limits.json, [areas] = limit_areas.json. */
    constructor(json, areas) {
      this.root = json;
      this.areas = areas.areas || {};
      this.source = json.source; this.url = json.url; this.checked = json.checked; this.reviewBy = json.reviewBy;
    }
    areaAt(p) {
      if (p.lat < -39 || p.lat > -11 || p.lon < 108 || p.lon > 129.1) return null;
      if (this.inside('abrolhos', p)) return 'abrolhos';
      if (p.lat > -27) return p.lon >= 114 + 50 / 60 && p.lat >= -(21 + 46 / 60) ? 'north' : 'gascoyne';
      // The South Coast starts at 115°30' E; further north that longitude is the west coast around Perth.
      if (p.lon >= 117 || (p.lon >= 115.5 && p.lat < -33.9)) return 'south';
      return 'west';
    }
    inside(area, p) {
      return (this.areas[area] || []).some((ring) => {
        let odd = false;
        for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
          const [ax, ay] = ring[i], [bx, by] = ring[j];
          if ((ay > p.lat) !== (by > p.lat) && p.lon < (bx - ax) * (p.lat - ay) / (by - ay) + ax) odd = !odd;
        }
        return odd;
      });
    }
    /** A plain value, or one per area with "other" / "*" for the areas not named. */
    static pick(o, key, area) {
      const v = o[key];
      if (v === undefined || v === null) return null;
      if (typeof v !== 'object') return String(v);
      const c = v[area] ?? v.other ?? v['*'];
      return c === undefined || c === null ? null : String(c);
    }
    entry(s, area) {
      const names = [s.name, ...(s.aka || [])];
      return {
        name: s.name, size: BagLimits.pick(s, 'size', area), bag: BagLimits.pick(s, 'bag', area), boat: BagLimits.pick(s, 'boat', area),
        note: BagLimits.pick(s, 'note', area), isProtected: !!s.protected, boatOpen: !!s.boatOpen,
        closures: (s.closures || []).filter((c) => c.regions.includes(area)).map((c) => ({ where: c.where, from: parseDay(c.from), to: parseDay(c.to),
          area: c.area || null, note: c.note || '', dates: `${dayName(parseDay(c.from))} to ${dayName(parseDay(c.to))}` })),
        search: names.join(' ').toLowerCase().replace(/'/g, ''),
        names: names.flatMap((n) => { const p = n.split(', '); return [plain(n), p.length === 2 ? plain(`${p[1]} ${p[0]}`) : null].filter(Boolean); }),
      };
    }
    /** Everything that applies in [area], keeping species that match every word of [query]. */
    find(area, query = '') {
      const words = query.toLowerCase().split(/[^a-z0-9-]+/).filter(Boolean);
      return this.root.categories.map((c) => {
        const entries = c.species.filter((s) => !s.regions || s.regions.includes(area)).map((s) => this.entry(s, area))
          .filter((e) => words.every((w) => e.search.includes(w)));
        const closure = c.boatClosure && c.boatClosure.regions.includes(area) ? c.boatClosure.text : null;
        return entries.length ? { name: c.name, total: BagLimits.pick(c, 'total', area), boatClosed: closure, note: BagLimits.pick(c, 'note', area), entries } : null;
      }).filter(Boolean);
    }
    /** The species a typed name means: exact names first (all of them), else every species with all the words. */
    lookup(area, text) {
      const t = plain(text);
      if (!t) return [];
      const all = this.find(area).flatMap((g) => g.entries.map((e) => [g, e]));
      let hit = all.filter(([, e]) => e.names.includes(t));
      if (hit.length) return hit;
      if (t.endsWith('s')) { hit = all.filter(([, e]) => e.names.includes(t.slice(0, -1))); if (hit.length) return hit; }
      const words = t.split(' ');
      return all.filter(([, e]) => words.every((w) => e.search.includes(w)));
    }
    /** All species names, for suggestions. */
    names() { return [...new Set(this.root.categories.flatMap((c) => c.species.map((s) => s.name)))]; }
  }
  const closureOn = (c, day) => (c.from <= c.to ? day >= c.from && day <= c.to : day >= c.from || day <= c.to);
  const sameDay = (a, b) => new Date(a).toDateString() === new Date(b).toDateString();
  const cmText = (mm) => { const v = mm / 10; return `${Number.isInteger(v) ? v : Number(v.toFixed(1))} cm`; };

  /**
   * Limits for a catch being logged and what's wrong with keeping it (mirrors CatchLimits.kt).
   * -> { area, lines, problems }. [kept] is the rest of the catch log.
   */
  function checkCatch(limits, { species, count = 1, lengthCm = null, time = Date.now(), pos: p = null }, kept = []) {
    if (!species || !species.trim()) return { area: null, lines: [], problems: [] };
    if (!p) return { area: null, lines: ["No position, so the area's limits aren't known."], problems: [] };
    const area = limits.areaAt(p);
    if (!area) return { area: null, lines: ['Outside WA waters: no limits listed.'], problems: [] };
    const found = limits.lookup(area, species), name = species.trim();
    if (!found.length) return { area, lines: [`"${name}" isn't in the bag limits list. Any finfish not listed: bag 30. Check the name in the Limits tab.`], problems: [] };
    if (found.length > 4) {
      return { area, lines: [`${found.length} species match "${name}": ${found.slice(0, 6).map(([, e]) => e.name).join(', ')}${found.length > 6 ? '…' : ''}. Type the full name to see its limits.`], problems: [] };
    }
    const lines = [], problems = [], many = found.length > 1, day = dayOf(time);
    const today = kept.filter((c) => !c.released && sameDay(c.time, time));
    const same = (c, pred) => limits.lookup(area, c.species || '').some(pred);
    for (const [g, e] of found) {
      const who = many ? `${e.name}: ` : '';
      if (e.isProtected) { lines.push(`${e.name}: protected, may not be taken.`); problems.push(`${who}Protected: it may not be taken. Release it.`); continue; }
      const boatClosed = g.boatClosed !== null && !e.boatOpen;
      lines.push(`${e.name}: ${[e.size ? `size ${e.size}` : 'no size limit', e.bag ? `bag ${e.bag}${boatClosed ? ' (shore only)' : ''}` : null, e.boat ? `boat ${e.boat}` : null].filter(Boolean).join(' · ')}`);
      if (e.note) lines.push(`   ${e.note}`);
      const mm = lengthCm ? lengthCm * 10 : null;
      if (mm && e.size && !/^max/i.test(e.size)) {
        const slot = /(\d+(?:\.\d+)?)\s*to\s*(\d+(?:\.\d+)?)/.exec(e.size);
        const nums = (e.size.match(/\d+(\.\d+)?/g) || []).map(Number);
        if (slot) {
          if (mm < Number(slot[1])) problems.push(`${who}Undersize: ${cmText(mm)} is under the ${cmText(Number(slot[1]))} minimum.`);
          if (mm > Number(slot[2])) problems.push(`${who}Oversize: ${cmText(mm)} is over the ${cmText(Number(slot[2]))} maximum.`);
        } else if (nums.length && mm < Math.max(...nums)) {
          problems.push(nums.length === 1 ? `${who}Undersize: ${cmText(mm)} is under the ${cmText(nums[0])} minimum.` : `${who}May be undersize: ${cmText(mm)}; the limit is ${e.size}.`);
        }
      }
      for (const c of e.closures.filter((x) => closureOn(x, day))) {
        const here = c.area !== null && limits.inside(c.area, p);
        if (c.area !== null && !here) continue;
        const text = ` (${c.dates}).${c.note ? ` ${c.note}` : ''}`;
        if (here) problems.push(`${who}Closed here now${text}`); else lines.push(`   Closed now in ${c.where}${text}`);
      }
      if (boatClosed) problems.push(`${who}${g.boatClosed} in this area: from the shore only.`);
      const bag = /^\d+$/.test((e.bag || '').trim()) ? Number(e.bag) : null;
      if (bag !== null) {
        const n = today.filter((c) => same(c, ([, x]) => x.name === e.name)).reduce((s, c) => s + (c.count || 1), 0) + count;
        if (n > bag) problems.push(`${who}Over the bag: ${n} kept today, the bag is ${bag} per person.`);
      }
    }
    for (const g of [...new Map(found.map(([x]) => [x.name, x])).values()]) {
      const total = /^\d+$/.test((g.total || '').trim()) ? Number(g.total) : null;
      if (total === null) continue;
      lines.push(`${g.name}: total ${total} a day, all species combined.`);
      const n = today.filter((c) => same(c, ([x]) => x.name === g.name)).reduce((s, c) => s + (c.count || 1), 0) + count;
      if (n > total) problems.push(`Over the ${g.name.toLowerCase()} total: ${n} kept today, the limit is ${total} per person.`);
      if (g.note) lines.push(`   ${g.note}`);
    }
    return { area, lines, problems: [...new Set(problems)] };
  }

  // ---- Catches (same JSON as Catch.toJson in Trips.kt) ---------------------------------------------------
  const num = (v) => { const n = Number(v); return v === null || v === undefined || v === '' || !isFinite(n) ? null : n; };
  function cleanCatch(o) {
    const lat = num(o.lat), lon = num(o.lon);
    const c = { id: /^[A-Za-z0-9_-]{1,64}$/.test(o.id || '') ? o.id : uuid(), time: Math.round(Number(o.time)) || Date.now(),
      species: str(o.species, 60), count: Math.min(999, Math.max(1, Math.round(Number(o.count)) || 1)), released: !!o.released,
      bait: str(o.bait, 100), notes: str(o.notes, 1000) };
    if (lat !== null && lon !== null && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) { c.lat = lat; c.lon = lon; }
    const cm = num(o.cm), kg = num(o.kg);
    if (cm !== null && cm > 0) c.cm = cm;
    if (kg !== null && kg > 0) c.kg = kg;
    if (o.depth) c.depth = str(o.depth, 40);
    if (Array.isArray(o.photos) && o.photos.length) c.photos = o.photos.map((p) => str(p, 80));
    if (o.mark) c.mark = str(o.mark, 80);
    return c;
  }
  const trimNum = (v, d = 1) => String(Number(v.toFixed(d)));
  const catchTitle = (c) => `${c.count > 1 ? `${c.count} × ` : ''}${c.species || 'Fish'}`;
  const catchDetails = (c) => [c.cm ? `${trimNum(c.cm)} cm` : null, c.kg ? `${trimNum(c.kg, 2)} kg` : null, c.released ? 'released' : 'kept'].filter(Boolean).join(' · ');
  function catchesCsv(list) {
    // Quoted, and never starting with a character a spreadsheet would treat as a formula.
    const q = (s) => `"${(/^[=+\-@]/.test(s) ? `'${s}` : s).replace(/"/g, '""')}"`;
    const p2 = (n) => String(n).padStart(2, '0');
    const t = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`; };
    return 'time,species,count,length_cm,weight_kg,kept,latitude,longitude,depth_m,bait,notes\n' + [...list].sort((a, b) => a.time - b.time).map((c) =>
      [t(c.time), q(c.species), c.count, c.cm ? trimNum(c.cm) : '', c.kg ? trimNum(c.kg, 2) : '', c.released ? 'released' : 'kept',
        c.lat !== undefined ? c.lat.toFixed(6) : '', c.lon !== undefined ? c.lon.toFixed(6) : '', q(c.depth || ''), q(c.bait), q(c.notes)].join(',') + '\n').join('');
  }

  // ---- Forecasts from Open-Meteo (the app's Wind.kt, for one point) ---------------------------------------
  /** -> { lat, lon, fetched, hours: [{ t, kn, gust, from, wave, sea, seaS, seaFrom, swell, swellS, swellFrom }] } */
  function parseForecast(wind, marine, lat, lon, fetched) {
    const h = wind.hourly, m = marine && marine.hourly;
    const at = new Map();
    if (m) m.time.forEach((t, i) => at.set(t, i));
    const v = (a, i) => (a && i !== undefined && a[i] !== null && a[i] !== undefined ? Number(a[i]) : null);
    return { lat, lon, fetched, hours: h.time.map((t, i) => {
      const j = at.get(t);
      return { t: t * 1000, kn: v(h.wind_speed_10m, i), gust: v(h.wind_gusts_10m, i), from: v(h.wind_direction_10m, i),
        wave: v(m?.wave_height, j), sea: v(m?.wind_wave_height, j), seaS: v(m?.wind_wave_period, j), seaFrom: v(m?.wind_wave_direction, j),
        swell: v(m?.swell_wave_height, j), swellS: v(m?.swell_wave_period, j), swellFrom: v(m?.swell_wave_direction, j) };
    }) };
  }
  /** The forecast hour nearest [t], or null outside it. */
  function forecastAt(f, t) {
    if (!f || !f.hours.length) return null;
    const first = f.hours[0].t, last = f.hours[f.hours.length - 1].t;
    if (t < first - 1800000 || t > last + 1800000) return null;
    return f.hours[Math.max(0, Math.min(f.hours.length - 1, Math.round((t - first) / 3600000)))];
  }
  const windText = (h) => (h && h.kn !== null ? `${Math.round(h.kn)} kn${h.gust !== null && h.gust >= h.kn ? ` gusting ${Math.round(h.gust)}` : ''}${h.from !== null ? ` from ${compass(h.from)}` : ''}` : '');
  function wavesText(h) {
    if (!h || h.wave === null) return '';
    const part = (m, s, from) => (m === null ? null : `${m.toFixed(1)} m${s !== null ? ` ${Math.round(s)} s` : ''}${from !== null ? ` from ${compass(from)}` : ''}`);
    return [part(h.swell, h.swellS, h.swellFrom) && `Swell ${part(h.swell, h.swellS, h.swellFrom)}`, part(h.sea, h.seaS, h.seaFrom) && `Sea ${part(h.sea, h.seaS, h.seaFrom)}`, `Combined ${h.wave.toFixed(1)} m`].filter(Boolean).join(' · ');
  }

  return { distanceNm, bearing, routeLengthNm, segDistM, fmtNm, fmtBearing, fmtDuration, fmtDdm, fmtPos, uuid,
    emptyRoute, cleanRoute, cleanMark, parseBackup, exportBackup, exportGpx, parseGpx, mergeById, mergeMarks,
    GROUP_COLORS, groupName, cleanGroup, findGroup, ensureGroup, tidyGroups, decodeTile, ChartReader, depthText, checkRoute, pos,
    fold, placeKind, placeZoom, searchPlaces, sourceLines,
    xteNm, sogCog, compass, circle, Tides, BagLimits, LIMIT_AREAS, areaLabel, plain, dayOf, closureOn, checkCatch,
    cleanCatch, catchTitle, catchDetails, catchesCsv, trimNum, parseForecast, forecastAt, windText, wavesText };
})();
if (typeof globalThis !== 'undefined') globalThis.ChompCore = Core;
