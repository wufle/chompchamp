  // ---- Wind, waves and tide (Wind.kt, WindView.kt, WaveView.kt, Tides.kt) ------------------------------------------------
  const tides = (() => { try { return new C.Tides(DATA.tides); } catch (e) { return null; } })();
  function tideNow(p, t = Date.now()) {
    const st = tides && tides.nearest(p.lat, p.lon);
    if (!st) return null;
    const h = tides.height(st, t);
    return { h, rising: tides.height(st, t + 600000) > h, st };
  }
  /** The chart's depth band with the tide added: about how much water there is now. */
  function depthNow(p, r) {
    if (r.land) return 'land';
    if (!r.depth || r.depth[1] >= 2000) return depthOf(r);
    const t = tideNow(p);
    if (!t) return '--';
    return `${Math.max(0, r.depth[0] + t.h).toFixed(1)}–${Math.max(0, r.depth[1] + t.h).toFixed(1)}`;
  }

  // ---- The forecast for the area on screen, kept in this browser for when there's no signal ------------------------------------
  try { localStorage.removeItem('chompchamp.planner.forecast'); } catch (e) { /* the one-point forecast of planner 0.3 */ }
  const wind = { forecast: null, busy: false, error: null, lastTry: 0 };
  let windTime = null;   // the time picked on the Wind & tide panel, or null for now
  idb.get('wind').then((f) => { if (f && f.u && !wind.forecast) { wind.forecast = f; windChanged(); } }, () => {});
  const windAtNow = (p) => C.windAt(wind.forecast, p.lat, p.lon, Date.now());
  const wavesAtNow = (p) => C.wavesAt(wind.forecast, p.lat, p.lon, Date.now());
  const windOn = () => layerState('wind', false).on && !!files.chart;
  const wavesOn = () => layerState('waves', false).on && !!files.chart;
  const shownTime = () => windTime ?? Date.now();
  const windLabel = (t) => new Date(t).toLocaleString([], { weekday: 'short', hour: 'numeric' });

  /** Fetch a forecast for this view if the one kept doesn't cover it, is old, or runs out within a day. */
  function fetchWindForView(force = false) {
    if (!force && !(windOn() || wavesOn())) return;
    const b = map.getBounds(), w = b.getWest(), s = b.getSouth(), e = b.getEast(), n = b.getNorth();
    if (!force && C.windFits(wind.forecast, w, s, e, n, wavesOn())) return;
    if (wind.busy || (!force && Date.now() - wind.lastTry < 120000) || navigator.onLine === false) return;
    wind.busy = true; wind.lastTry = Date.now();
    windChanged();
    const g = C.windGrid(w, s, e, n);
    const chunks = [];
    for (let i = 0; i < g.pts.length; i += 100) chunks.push(g.pts.slice(i, i + 100));
    const ll = (c) => `latitude=${c.map((p) => p.lat.toFixed(4)).join(',')}&longitude=${c.map((p) => p.lon.toFixed(4)).join(',')}`;
    const get = async (url) => {
      const r = await fetch(url, { referrerPolicy: 'no-referrer', credentials: 'omit' });
      const body = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(body.reason || `HTTP ${r.status}`);
      return body;
    };
    (async () => {
      try {
        const winds = [];
        for (const c of chunks) winds.push(await get(`https://api.open-meteo.com/v1/forecast?${ll(c)}&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m` +
          '&models=ecmwf_ifs&forecast_days=7&wind_speed_unit=kn&timeformat=unixtime&cell_selection=sea'));
        // Waves are optional: the wind still works if they fail. "nearest", so a point on land stays empty.
        let marine = [];
        try {
          for (const c of chunks) marine.push(await get(`https://marine-api.open-meteo.com/v1/marine?${ll(c)}&hourly=${C.WAVE_KEYS.join(',')}` +
            '&models=meteofrance_wave&forecast_days=7&timeformat=unixtime&cell_selection=nearest'));
        } catch (e) { marine = null; }
        wind.forecast = C.parseWindGrid(g, winds, marine, Date.now());
        wind.error = null;
        idb.put('wind', wind.forecast).catch(() => {});
      } catch (e) {
        wind.error = `Couldn't get the wind forecast (${e.message})`;
      }
      wind.busy = false;
      windChanged();
    })();
  }
  map.on('moveend', () => fetchWindForView());
  addEventListener('online', () => fetchWindForView());

  /** Start or stop the streaks and shading, and say where the forecast is from. */
  function windChanged() {
    const wOn = windOn(), vOn = wavesOn(), on = wOn || vOn, f = wind.forecast, t = shownTime();
    $('wind').style.opacity = layerState('wind', false).pct / 100;
    streaks.run(wOn && !!f);
    drawWaves();
    const c = $('credit');
    c.hidden = !on;
    if (on) {
      const what = wOn && vOn ? 'Wind & waves' : wOn ? 'Wind' : 'Waves';
      const by = [wOn ? 'ECMWF' : null, vOn ? 'Copernicus Marine' : null].filter(Boolean).join(', ');
      c.textContent = !f && wind.busy ? `${what}: getting the forecast…` : !f ? `${what}: no forecast yet (needs internet) · tap`
        : t > C.windEnd(f) ? `${what}: forecast ran out · go online` : vOn && !f.waves ? 'Waves: not in this forecast yet · go online'
          : `${what} ${windTime === null ? 'now' : windLabel(t)} · ${by} via Open-Meteo.com`;
    }
    updateFields();
    refreshWindPanel();
  }
  $('credit').onclick = () => showWindTidePanel();

  // ---- Wind streaks drifting across the map, coloured by speed (WindView) -------------------------------------------------------
  const WIND_STOPS = [0, 5, 10, 15, 20, 25, 30, 40], WIND_COLOURS = ['#E3F2FD', '#B3E5FC', '#80DEEA', '#A5D6A7', '#FFF59D', '#FFCC80', '#EF9A9A', '#CE93D8'];
  const WAVE_STOPS = [0, 0.5, 1, 1.5, 2, 3, 4, 6], WAVE_COLOURS = ['#B3E5FC', '#4FC3F7', '#26A69A', '#9CCC65', '#FFEE58', '#FFA726', '#EF5350', '#AB47BC'];
  const mercY = (lat) => (Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360)) * 180) / Math.PI;
  const latOf = (my) => ((2 * Math.atan(Math.exp((my * Math.PI) / 180)) - Math.PI / 2) * 180) / Math.PI;
  const streaks = {
    on: false, timer: 0, px: [], py: [], age: [], life: [], aff: null, grid: null, gridFor: null, gridT: 0,
    run(on) {
      if (on === this.on) return;
      this.on = on;
      const cv = $('wind');
      if (!on) { clearTimeout(this.timer); cv.getContext('2d').clearRect(0, 0, cv.width, cv.height); this.aff = null; return; }
      this.frame();
    },
    resize() {
      const cv = $('wind'), dpr = devicePixelRatio || 1, w = innerWidth, h = innerHeight;
      cv.width = w * dpr; cv.height = h * dpr; cv.style.width = `${w}px`; cv.style.height = `${h}px`;
      const n = Math.max(50, Math.min(4000, Math.round(((w * h) / 10000) * 12)));
      this.px = new Float32Array(n); this.py = new Float32Array(n); this.age = new Int32Array(n); this.life = new Int32Array(n);
      for (let i = 0; i < n; i++) this.spawn(i, w, h, Math.floor(Math.random() * 60));
      this.aff = null;
    },
    spawn(i, w, h, age = 0) { this.px[i] = Math.random() * w; this.py[i] = Math.random() * h; this.age[i] = age; this.life[i] = 50 + Math.floor(Math.random() * 70); },
    /** Screen -> Mercator degrees as an affine map, from three corners of the view. */
    affine() {
      const w = innerWidth, h = innerHeight, p0 = map.unproject([0, 0]), p1 = map.unproject([w, 0]), p2 = map.unproject([0, h]);
      const my0 = mercY(p0.lat);
      return [(p1.lng - p0.lng) / w, (p2.lng - p0.lng) / h, p0.lng, (mercY(p1.lat) - my0) / w, (mercY(p2.lat) - my0) / h, my0];
    },
    frame() {
      if (!this.on) return;
      const t0 = performance.now(), fc = wind.forecast, cv = $('wind'), c = cv.getContext('2d'), w = innerWidth, h = innerHeight;
      if (!fc) { this.run(false); return; }
      if (!this.px.length || cv.width !== Math.round(w * (devicePixelRatio || 1))) this.resize();
      const t = shownTime();
      if (fc !== this.gridFor || Math.abs(t - this.gridT) > 60000) { this.grid = C.windFrame(fc, t); this.gridFor = fc; this.gridT = t; }
      const aff = this.affine(), [a, b, cc, d, e, f] = aff;
      const dpr = devicePixelRatio || 1;
      c.setTransform(dpr, 0, 0, dpr, 0, 0);
      // The map moved: old trails would smear, so start again.
      const moved = !this.aff || aff.some((v, i) => Math.abs(v - this.aff[i]) > Math.abs(this.aff[i]) * 1e-6 + 1e-12);
      this.aff = aff;
      if (moved) c.clearRect(0, 0, w, h);
      else { c.globalCompositeOperation = 'destination-in'; c.fillStyle = 'rgba(0,0,0,0.91)'; c.fillRect(0, 0, w, h); c.globalCompositeOperation = 'source-over'; }
      const det = a * e - b * d, ia = e / det, ib = -b / det, id = -d / det, ie = a / det;
      const { u: gu, v: gv } = this.grid, nx = fc.nx, ny = fc.ny;
      const paths = WIND_COLOURS.map(() => new Path2D()), used = new Uint8Array(WIND_COLOURS.length);
      for (let i = 0; i < this.px.length; i++) {
        if (++this.age[i] > this.life[i]) { this.spawn(i, w, h); continue; }
        const x = this.px[i], y = this.py[i], lon = a * x + b * y + cc, lat = latOf(d * x + e * y + f);
        if (lat < fc.s || lat > fc.n || lon < fc.w || lon > fc.e) { this.spawn(i, w, h); continue; }
        const fx = ((lon - fc.w) / (fc.e - fc.w)) * (nx - 1), fy = ((lat - fc.s) / (fc.n - fc.s)) * (ny - 1);
        const x0 = Math.max(0, Math.min(nx - 2, Math.floor(fx))), y0 = Math.max(0, Math.min(ny - 2, Math.floor(fy))), dx = fx - x0, dy = fy - y0, k = y0 * nx + x0;
        const u = (gu[k] * (1 - dx) + gu[k + 1] * dx) * (1 - dy) + (gu[k + nx] * (1 - dx) + gu[k + nx + 1] * dx) * dy;
        const v = (gv[k] * (1 - dx) + gv[k + 1] * dx) * (1 - dy) + (gv[k + nx] * (1 - dx) + gv[k + nx + 1] * dx) * dy;
        if (!isFinite(u) || !isFinite(v)) { this.spawn(i, w, h); continue; }
        const kn = Math.hypot(u, v);
        if (kn < 0.3) continue;
        let sx = ia * u + ib * v, sy = id * u + ie * v;
        const len = Math.hypot(sx, sy);
        if (!len) continue;
        sx = (sx / len) * kn * 0.045; sy = (sy / len) * kn * 0.045;
        const nx2 = x + sx, ny2 = y + sy;
        if (nx2 < 0 || ny2 < 0 || nx2 > w || ny2 > h) { this.spawn(i, w, h); continue; }
        let band = 0; while (band < 7 && kn >= WIND_STOPS[band + 1]) band++;
        paths[band].moveTo(x, y); paths[band].lineTo(nx2, ny2); used[band] = 1;
        this.px[i] = nx2; this.py[i] = ny2;
      }
      c.lineWidth = 1.6; c.lineCap = 'round';
      paths.forEach((p, i) => { if (used[i]) { c.strokeStyle = WIND_COLOURS[i]; c.stroke(p); } });
      // About 30 frames a second is plenty, and kinder to the battery.
      this.timer = setTimeout(() => requestAnimationFrame(() => this.frame()), Math.max(0, 33 - (performance.now() - t0)));
    },
  };
  addEventListener('resize', () => { if (streaks.on) streaks.resize(); drawWaves(); });

  // ---- Sea and swell: wave height shading and swell arrows (WaveView) -----------------------------------------------------------
  function blend(stops, colours, v) {
    if (!(v > stops[0])) return colours[0];
    for (let i = 1; i < stops.length; i++) if (v < stops[i]) {
      const k = (v - stops[i - 1]) / (stops[i] - stops[i - 1]);
      const a = colours[i - 1].match(/\w\w/g).map((x) => parseInt(x, 16)), b = colours[i].match(/\w\w/g).map((x) => parseInt(x, 16));
      return `rgb(${a.map((x, j) => Math.round(x + (b[j] - x) * k)).join(',')})`;
    }
    return colours[colours.length - 1];
  }
  const rgbOf = (s) => (s.startsWith('#') ? s.match(/\w\w/g).map((x) => parseInt(x, 16)) : s.match(/\d+/g).map(Number));
  const waveImg = { for: null, hour: null, canvas: null, cols: 0, rows: 0 };
  /** Combined wave height for one hour as an image, evenly spaced in Mercator so it lines up with the map. */
  function buildWaves(fc, t) {
    const grid = C.waveFrame(fc, t, 'wave_height');
    if (!grid) { waveImg.canvas = null; return; }
    const k = 8, cols = (fc.nx - 1) * k + 1, mN = mercY(fc.n), mS = mercY(fc.s);
    const rows = Math.max(2, Math.round(((mN - mS) * (cols - 1)) / (fc.e - fc.w))) + 1;
    const cv = Object.assign(document.createElement('canvas'), { width: cols, height: rows }), c = cv.getContext('2d'), img = c.createImageData(cols, rows);
    for (let r = 0; r < rows; r++) {
      const lat = latOf(mN - ((mN - mS) * r) / (rows - 1)), fy = ((lat - fc.s) / (fc.n - fc.s)) * (fc.ny - 1);
      const y0 = Math.max(0, Math.min(fc.ny - 2, Math.floor(fy))), dy = fy - y0;
      for (let col = 0; col < cols; col++) {
        const fx = col / k, x0 = Math.max(0, Math.min(fc.nx - 2, Math.floor(fx))), dx = fx - x0;
        let sum = 0, ws = 0;
        for (const [ox, oy] of [[0, 0], [1, 0], [0, 1], [1, 1]]) {
          const v = grid[(y0 + oy) * fc.nx + x0 + ox];
          if (!isFinite(v)) continue;
          const wt = (ox ? dx : 1 - dx) * (oy ? dy : 1 - dy);
          sum += v * wt; ws += wt;
        }
        if (ws < 0.05) continue;   // fades out towards the coast instead of a hard step
        const o = (r * cols + col) * 4, [R, G, B] = rgbOf(blend(WAVE_STOPS, WAVE_COLOURS, sum / ws));
        img.data[o] = R; img.data[o + 1] = G; img.data[o + 2] = B; img.data[o + 3] = Math.round(255 * Math.min(1, ws));
      }
    }
    c.putImageData(img, 0, 0);
    Object.assign(waveImg, { for: fc, hour: Math.floor((t - fc.t0 + 1800000) / 3600000), canvas: cv, cols, rows });
  }
  function drawWaves() {
    const cv = $('waves'), dpr = devicePixelRatio || 1, W = innerWidth, H = innerHeight;
    if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) { cv.width = W * dpr; cv.height = H * dpr; cv.style.width = `${W}px`; cv.style.height = `${H}px`; }
    const c = cv.getContext('2d');
    c.setTransform(1, 0, 0, 1, 0, 0); c.clearRect(0, 0, cv.width, cv.height);
    const fc = wind.forecast;
    if (!wavesOn() || !fc || !fc.waves) return;
    const t = shownTime(), hour = Math.floor((t - fc.t0 + 1800000) / 3600000);
    if (fc !== waveImg.for || hour !== waveImg.hour) buildWaves(fc, t);
    if (!waveImg.canvas) return;
    const op = layerState('waves', false).pct / 100;
    // Three corners of the image on screen: Mercator is affine on screen.
    const nw = map.project([fc.w, fc.n]), ne = map.project([fc.e, fc.n]), sw = map.project([fc.w, fc.s]);
    const Wc = waveImg.cols - 1, Hr = waveImg.rows - 1;
    c.setTransform(((ne.x - nw.x) / Wc) * dpr, ((ne.y - nw.y) / Wc) * dpr, ((sw.x - nw.x) / Hr) * dpr, ((sw.y - nw.y) / Hr) * dpr, nw.x * dpr, nw.y * dpr);
    c.globalAlpha = op * 0.55; c.imageSmoothingEnabled = true;
    c.drawImage(waveImg.canvas, -0.5, -0.5);
    c.globalAlpha = op;
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    // An arrow for the way the swell runs, about every 80 px, with its height.
    const hgt = C.waveFrame(fc, t, 'swell_wave_height'), dir = C.waveFrame(fc, t, 'swell_wave_direction');
    const spacing = Math.hypot((ne.x - nw.x) / (fc.nx - 1), (ne.y - nw.y) / (fc.nx - 1));
    if (!(spacing > 0)) return;
    const stride = Math.max(1, Math.ceil(80 / spacing)), len = 22;
    c.font = '10px system-ui, sans-serif'; c.textAlign = 'center'; c.lineJoin = 'round';
    for (let row = 0; row < fc.ny; row += stride) for (let col = 0; col < fc.nx; col += stride) {
      const i = row * fc.nx + col, mh = hgt[i], from = dir[i];
      if (!isFinite(mh) || !isFinite(from) || mh < 0.05) continue;
      const lat = fc.s + ((fc.n - fc.s) * row) / (fc.ny - 1), lon = fc.w + ((fc.e - fc.w) * col) / (fc.nx - 1);
      const pt = map.project([lon, lat]);
      if (pt.x < -len || pt.y < -len || pt.x > W + len || pt.y > H + len) continue;
      // Runs towards from + 180°.
      const toward = ((from + 180) * Math.PI) / 180;
      const ahead = map.project([lon + (Math.sin(toward) * 0.01) / Math.cos((lat * Math.PI) / 180), lat + Math.cos(toward) * 0.01]);
      const vl = Math.hypot(ahead.x - pt.x, ahead.y - pt.y);
      if (!vl) continue;
      arrow(c, pt.x, pt.y, (ahead.x - pt.x) / vl, (ahead.y - pt.y) / vl, len);
      c.fillStyle = '#ffffff'; c.shadowColor = '#101C2B'; c.shadowBlur = 3;
      c.fillText(mh.toFixed(1), pt.x, pt.y + len * 0.5 + 12);
      c.shadowBlur = 0;
    }
  }
  function arrow(c, x, y, ux, uy, len) {
    const px = -uy, py = ux, tipX = x + (ux * len) / 2, tipY = y + (uy * len) / 2, tailX = x - (ux * len) / 2, tailY = y - (uy * len) / 2;
    const head = len * 0.42, half = len * 0.22, shaft = len * 0.07;
    c.beginPath(); c.moveTo(tipX, tipY);
    c.lineTo(tipX - ux * head + px * half, tipY - uy * head + py * half); c.lineTo(tipX - ux * head + px * shaft, tipY - uy * head + py * shaft);
    c.lineTo(tailX + px * shaft, tailY + py * shaft); c.lineTo(tailX - px * shaft, tailY - py * shaft);
    c.lineTo(tipX - ux * head - px * shaft, tipY - uy * head - py * shaft); c.lineTo(tipX - ux * head - px * half, tipY - uy * head - py * half);
    c.closePath(); c.fillStyle = '#ffffff'; c.fill(); c.strokeStyle = '#101C2B'; c.lineWidth = 1.5; c.stroke();
  }
  map.on('move', drawWaves);

  // ---- Week charts (ForecastChartView) and the tide chart (TideChartView) -------------------------------------------------------------
  /** The main value filled with its colour, a dashed second line, direction arrows, days shaded in turn. Tap or drag to pick a time. */
  function forecastChart(pts, { stops, colours, unit, minTop, step, decimals }, selected, onPick, height = 170) {
    const cv = el('canvas.chart', { height });
    const draw = () => {
      const dpr = devicePixelRatio || 1, W = cv.clientWidth || 300, H = height;
      cv.width = W * dpr; cv.height = H * dpr;
      const c = cv.getContext('2d'); c.scale(dpr, dpr); c.clearRect(0, 0, W, H);
      if (pts.length < 2) return;
      const left = 28, right = W - 4, top = 34, bottom = H - 18, from = pts[0].t, to = pts[pts.length - 1].t;
      const x = (t) => left + ((right - left) * (t - from)) / (to - from);
      const peak = Math.max(...pts.flatMap((p) => [p.main, p.second]).filter((v) => v !== null && isFinite(v)), 0);
      const yMax = Math.max(minTop, Math.ceil((peak * 1.1) / step) * step);
      const y = (v) => bottom - ((bottom - top) * v) / yMax;
      c.font = '10px system-ui, sans-serif';
      const d = new Date(from); d.setHours(0, 0, 0, 0);
      let odd = false;
      while (d.getTime() < to) {
        const a = Math.max(d.getTime(), from); d.setDate(d.getDate() + 1); const b = Math.min(d.getTime(), to);
        if (odd) { c.fillStyle = '#ffffff10'; c.fillRect(x(a), 0, x(b) - x(a), bottom); }
        if (x(b) - x(a) > 26) { c.fillStyle = '#E6EEF6'; c.textAlign = 'center'; c.fillText(new Date(a).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric' }), (x(a) + x(b)) / 2, H - 4); }
        odd = !odd;
      }
      c.textAlign = 'left';
      for (let g = 0; g <= yMax + 1e-6; g += step) {
        c.strokeStyle = '#9FB3C833'; c.beginPath(); c.moveTo(left, y(g)); c.lineTo(right, y(g)); c.stroke();
        c.fillStyle = '#9FB3C8'; c.fillText(g.toFixed(decimals), 2, y(g) + 3.5);
      }
      c.fillText(unit, 2, top - 6);
      const grad = c.createLinearGradient(0, bottom, 0, top);
      stops.forEach((s, i) => grad.addColorStop(Math.min(1, s / yMax), colours[i]));
      const ok = pts.filter((p) => p.main !== null);
      if (ok.length > 1) {
        c.beginPath(); c.moveTo(x(ok[0].t), bottom);
        ok.forEach((p) => c.lineTo(x(p.t), y(p.main)));
        c.lineTo(x(ok[ok.length - 1].t), bottom); c.closePath();
        c.globalAlpha = 0.9; c.fillStyle = grad; c.fill(); c.globalAlpha = 1;
        c.beginPath(); ok.forEach((p, i) => (i ? c.lineTo(x(p.t), y(p.main)) : c.moveTo(x(p.t), y(p.main))));
        c.strokeStyle = '#ffffff99'; c.lineWidth = 1.5; c.stroke();
      }
      c.setLineDash([4, 3]); c.beginPath();
      let on = false;
      for (const p of pts) { if (p.second === null) { on = false; continue; } if (on) c.lineTo(x(p.t), y(p.second)); else c.moveTo(x(p.t), y(p.second)); on = true; }
      c.strokeStyle = '#ffffff'; c.lineWidth = 1.5; c.stroke(); c.setLineDash([]);
      const hours = (to - from) / 3600000, every = [1, 2, 3, 6, 12].find((n) => (right - left) / (hours / n) >= 18) || 12;
      pts.forEach((p, i) => {
        if (i % every || p.dir === null) return;
        const r = ((p.dir + 180) * Math.PI) / 180, ux = Math.sin(r), uy = -Math.cos(r), cx = x(p.t), cy = 17, L = 11;
        c.beginPath(); c.moveTo(cx + (ux * L) / 2, cy + (uy * L) / 2);
        c.lineTo(cx - (ux * L) / 2 - uy * L * 0.32, cy - (uy * L) / 2 + ux * L * 0.32); c.lineTo(cx - ux * L * 0.15, cy - uy * L * 0.15);
        c.lineTo(cx - (ux * L) / 2 + uy * L * 0.32, cy - (uy * L) / 2 - ux * L * 0.32); c.closePath();
        c.fillStyle = blend(stops, colours, p.main ?? 0); c.fill();
      });
      const now = Date.now();
      if (now >= from && now <= to) { c.fillStyle = '#9FB3C8'; c.fillRect(x(now), top, 1, bottom - top); }
      const t = Math.max(from, Math.min(to, selected()));
      c.fillStyle = '#8EC1E6'; c.fillRect(x(t) - 1, top - 2, 2, bottom - top + 2);
      const near = pts.reduce((a, b) => (Math.abs(b.t - t) < Math.abs(a.t - t) ? b : a));
      if (near.main !== null) {
        c.beginPath(); c.arc(x(t), y(near.main), 3.5, 0, 7); c.fill();
        const label = near.main.toFixed(decimals) + (near.second !== null ? ` / ${near.second.toFixed(decimals)}` : '');
        c.font = 'bold 11px system-ui, sans-serif';
        const w = c.measureText(label).width;
        c.fillText(label, x(t) + 5 + w > right ? x(t) - 5 - w : x(t) + 5, top + 10);
      }
    };
    const pick = (e) => {
      const b = cv.getBoundingClientRect(), from = pts[0].t, to = pts[pts.length - 1].t;
      const k = Math.max(0, Math.min(1, (e.clientX - b.left - 28) / (b.width - 32)));
      onPick(from + Math.round(((to - from) * k) / 3600000) * 3600000);
    };
    let down = false;
    cv.addEventListener('pointerdown', (e) => { down = true; cv.setPointerCapture(e.pointerId); pick(e); });
    cv.addEventListener('pointermove', (e) => { if (down) pick(e); });
    cv.addEventListener('pointerup', () => { down = false; });
    cv.style.touchAction = 'pan-y';
    cv.redraw = draw;
    requestAnimationFrame(draw);
    return cv;
  }

  function tideChart(st, from, to, now) {
    const cv = el('canvas.chart', { height: 150 });
    cv.redraw = () => {
      const dpr = devicePixelRatio || 1, W = cv.clientWidth || 300, H = 150;
      cv.width = W * dpr; cv.height = H * dpr;
      const c = cv.getContext('2d'); c.scale(dpr, dpr);
      const n = 144, hs = Array.from({ length: n + 1 }, (_, i) => tides.height(st, from + ((to - from) * i) / n));
      const span = Math.max(0.2, Math.max(...hs.map(Math.abs)) * 1.15);
      const left = 30, right = W - 6, top = 16, bottom = H - 16;
      const x = (t) => left + ((right - left) * (t - from)) / (to - from), y = (h) => top + (bottom - top) * (0.5 - h / (2 * span));
      c.font = '10px system-ui, sans-serif'; c.fillStyle = '#9FB3C8'; c.strokeStyle = '#9FB3C83c';
      c.beginPath(); c.moveTo(left, y(0)); c.lineTo(right, y(0)); c.stroke();
      c.fillText('MSL', 2, y(0) + 3); c.fillText(`+${span.toFixed(1)}`, 2, top + 4); c.fillText(`-${span.toFixed(1)}`, 2, bottom);
      const d = new Date(from); d.setMinutes(0, 0, 0);
      for (; d.getTime() < to; d.setHours(d.getHours() + 1)) {
        const hr = d.getHours();
        if (d.getTime() < from || hr % 6) continue;
        c.beginPath(); c.moveTo(x(d.getTime()), top); c.lineTo(x(d.getTime()), bottom); c.stroke();
        c.fillText(hr === 0 ? d.toLocaleDateString(undefined, { weekday: 'short' }) : `${hr}:00`, x(d.getTime()) + 2, H - 3);
      }
      c.beginPath(); hs.forEach((h, i) => { const px = left + ((right - left) * i) / n; if (i) c.lineTo(px, y(h)); else c.moveTo(px, y(h)); });
      c.strokeStyle = '#8EC1E6'; c.lineWidth = 2; c.stroke();
      c.textAlign = 'center'; c.fillStyle = '#ffffff';
      for (const tt of tides.turns(st, from, to)) {
        c.beginPath(); c.arc(x(tt.time), y(tt.metres), 2.5, 0, 7); c.fill();
        c.fillText(new Date(tt.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), x(tt.time), tt.high ? y(tt.metres) - 5 : y(tt.metres) + 12);
      }
      c.fillRect(x(now), top, 1.2, bottom - top);
    };
    requestAnimationFrame(cv.redraw);
    return cv;
  }
  const legend = (colours, labels, unit) => el('div.legend', {}, colours.map((col, i) => el('span', { style: `background:${col}` }, labels[i])), el('em', {}, unit));

  // ---- The Wind, waves & tide panel ---------------------------------------------------------------------------------------------------
  const WIND_PANEL = 'Wind, waves & tide';
  let panelBusy = false;   // a change made on the panel itself redraws in place, not by rebuilding it
  const refreshWindPanel = () => { if (panel.current === WIND_PANEL && !panelBusy) showWindTidePanel(); };
  function showWindTidePanel() {
    const hereP = lastPos() || mapCentre(), now = Date.now();
    const f = wind.forecast;
    if ((!f || !C.windAt(f, hereP.lat, hereP.lon, now)) && !wind.busy && navigator.onLine !== false && now - wind.lastTry > 120000) { panelBusy = true; fetchWindForView(true); panelBusy = false; }
    panel.show(WIND_PANEL, (rows) => {
      rows.section('Wind, sea & swell forecast');
      for (const [key, name] of [['wind', 'Wind on the map'], ['waves', 'Sea & swell on the map']]) {
        const s = layerState(key, false);
        rows.sliderRow({ label: name, pct: s.pct, checked: s.on,
          onChecked: (on) => { state.layers[key] = { ...layerState(key, false), on }; save(); panelBusy = true; windChanged(); fetchWindForView(); panelBusy = false; },
          onPct: (p) => { state.layers[key] = { ...layerState(key, false), pct: p }; save(); panelBusy = true; windChanged(); panelBusy = false; } });
      }
      if (!f) rows.note(wind.busy ? 'Getting the forecast…' : 'No forecast yet. With internet, it downloads for the area on screen.');
      else {
        const hoursAhead = Math.floor((C.windEnd(f) - now) / 3600000);
        const label = rows.text('');
        const describe = (t) => {
          const at = t ?? now, w = C.windAt(f, hereP.lat, hereP.lon, at), wv = C.wavesAt(f, hereP.lat, hereP.lon, at);
          label.textContent = (t === null ? 'Now' : windLabel(at)) + (lastPos() ? ' at the boat' : ' at the map centre') +
            (w ? `\nWind ${C.windText(w)}` : '') + (wv ? `\n${C.wavesText(wv)}` : '');
        };
        describe(windTime);
        const charts = [];
        let slider = null;
        // One time for the slider, both charts, the wind streaks and the sea and swell on the map.
        const pickTime = (t, fromSlider) => {
          windTime = t;
          describe(t);
          charts.forEach((c) => c.redraw());
          if (!fromSlider && slider) { slider.value = Math.max(0, Math.min(hoursAhead, Math.round(((t ?? now) - now) / 3600000))); paintSlider(slider); }
          panelBusy = true; windChanged(); panelBusy = false;
        };
        if (hoursAhead > 0) {
          rows.note('Forecast time: moves the wind, sea and swell on the map and on the charts below.');
          slider = el('input.slider.wide', { type: 'range', min: 0, max: hoursAhead, step: 1, value: Math.max(0, Math.min(hoursAhead, Math.round(((windTime ?? now) - now) / 3600000))), ariaLabel: 'Forecast time' });
          paintSlider(slider);
          slider.addEventListener('input', () => pickTime(Number(slider.value) === 0 ? null : now + Number(slider.value) * 3600000, true));
          rows.view(slider);
        }
        if (windTime !== null) rows.button('Back to now', () => { pickTime(null, false); showWindTidePanel(); });
        if (hoursAhead > 0) {
          const hrs = Array.from({ length: hoursAhead + 1 }, (_, i) => now + i * 3600000);
          const sel = () => windTime ?? now, onPick = (t) => pickTime(t <= now ? null : t, false);
          const windPts = hrs.map((t) => { const w = C.windAt(f, hereP.lat, hereP.lon, t); return { t, main: w ? w.kn : null, second: w ? w.gust : null, dir: w ? w.from : null }; });
          if (windPts.some((p) => p.main !== null)) {
            rows.note('Wind (kn): colour is the mean speed, the dashed line gusts, arrows the way it blows. Tap or drag to pick a time.');
            const ch = forecastChart(windPts, { stops: WIND_STOPS, colours: WIND_COLOURS, unit: 'kn', minTop: 20, step: 10, decimals: 0 }, sel, onPick, 170);
            charts.push(ch); rows.view(ch);
          } else rows.note("The wind forecast doesn't reach here: download it for this area.");
          const wavePts = hrs.map((t) => { const w = C.wavesAt(f, hereP.lat, hereP.lon, t); return { t, main: w ? w.wave : null, second: w ? w.swell : null, dir: w ? w.swellFrom : null }; });
          if (wavePts.some((p) => p.main !== null)) {
            rows.note('Sea & swell (m): colour is the combined height, the dashed line the swell, arrows the way the swell runs.');
            const ch = forecastChart(wavePts, { stops: WAVE_STOPS, colours: WAVE_COLOURS, unit: 'm', minTop: 2, step: 1, decimals: 1 }, sel, onPick, 150);
            charts.push(ch); rows.view(ch);
          } else if (f.waves) rows.note('No sea & swell forecast here (land, or too close to the coast for the model).');
        }
        const ago = Math.round((now - f.fetched) / 60000);
        rows.note(`ECMWF IFS 9 km forecast via Open-Meteo.com (CC BY 4.0), downloaded ${ago < 60 ? `${ago} min ago` : `${Math.round(ago / 60)} h ago`} ` +
          `for ${(-f.n).toFixed(1)}–${(-f.s).toFixed(1)}° S, ${f.w.toFixed(1)}–${f.e.toFixed(1)}° E. It's kept for when you have no signal: open the map over where you're going while online. ` +
          "It updates by itself when you're online, it's over 3 hours old, or you move outside it. Only the points of a grid over the map are sent, never your position.");
      }
      if (wind.error) rows.note(wind.error);
      rows.button(wind.busy ? 'Downloading…' : 'Download for this area now', () => { panelBusy = true; fetchWindForView(true); panelBusy = false; showWindTidePanel(); });
      rows.view(legend(WIND_COLOURS, ['0', '5', '10', '15', '20', '25', '30', '40+'], ' kn'));
      rows.view(legend(WAVE_COLOURS, ['0', '0.5', '1', '1.5', '2', '3', '4', '6+'], ' m'));
      rows.note('Waves: colour is the combined height of sea and swell; arrows show which way the swell runs, with its height in metres. MFWAM 8 km model, ' +
        "generated using E.U. Copernicus Marine Service Information, via Open-Meteo.com. Close to the coast and inside reefs it's only a guide: the model's cells are 8 km.");

      rows.section('Tide');
      const t = tideNow(hereP, now);
      if (!t) rows.note('No tide prediction here: it covers the WA coast, within about 60 km of a model cell.');
      else {
        const km = C.distanceNm(hereP, t.st) * 1.852;
        rows.text(`Now ${t.h >= 0 ? '+' : ''}${t.h.toFixed(2)} m, ${t.rising ? 'rising' : 'falling'} · near ${t.st.near}${km >= 1 ? ` (model point ${km.toFixed(0)} km away)` : ''}`);
        rows.view(tideChart(t.st, now - 6 * 3600000, now + 42 * 3600000, now));
        rows.view(el('div.turns', {}, tides.turns(t.st, now, now + 48 * 3600000).map((x) => el('div', {}, el('span', {}, x.high ? 'High' : 'Low'),
          el('span', {}, new Date(x.time).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false })), el('span', {}, `${x.metres >= 0 ? '+' : ''}${x.metres.toFixed(2)} m`)))));
        rows.note("Heights are above mean sea level, the same level as the chart's depths, so the water is about chart depth + tide. A global model (EOT20, CC BY 4.0) " +
          'worked out in this browser: roughly ±0.1–0.2 m and a few minutes on the open coast. Not right inside rivers and estuaries (Swan, Peel–Harvey), and weather ' +
          '(wind, pressure) can move the sea by more than the tide here.');
      }
    });
  }
  addEventListener('resize', () => { document.querySelectorAll('#panel canvas.chart').forEach((c) => c.redraw && c.redraw()); });
