  // ---- Weather tab: tides (worked out here, offline) and the wind and sea forecast (Open-Meteo, kept for offline) ----
  const tides = (() => { try { return new C.Tides(DATA.tides); } catch (e) { return null; } })();
  const FKEY = 'chompchamp.planner.forecast';
  let forecast = (() => { try { return JSON.parse(localStorage.getItem(FKEY) || 'null'); } catch (e) { return null; } })();
  let fetching = false, fetchError = '', pickT = null;   // pickT: the forecast time picked, or null for now

  function tideNow(p, t = Date.now()) {
    const st = tides && tides.nearest(p.lat, p.lon);
    if (!st) return null;
    const h = tides.height(st, t);
    return { h, rising: tides.height(st, t + 600000) > h, st };
  }
  /** The chart's depth band with the tide added: about how much water there is now. */
  function depthNow(p, r) {
    if (r.land) return 'land';
    if (!r.depth || r.depth[1] >= 2000) return C.depthText(r).replace(' m', '');
    const t = tideNow(p);
    if (!t) return '--';
    return `${Math.max(0, r.depth[0] + t.h).toFixed(1)}–${Math.max(0, r.depth[1] + t.h).toFixed(1)}`;
  }
  function tideText(p, r) {
    const t = tideNow(p);
    if (!t) return '';
    return `Tide now (est.): ${t.h >= 0 ? '+' : ''}${t.h.toFixed(2)} m, ${t.rising ? 'rising' : 'falling'}` + (r && r.depth ? `\nDepth now (est.): ${depthNow(p, r)} m` : '');
  }
  /** The kept forecast at [p], if it's for somewhere within about 25 km. */
  function forecastHere(p, t) {
    if (!forecast || C.distanceNm(p, forecast) > 13.5) return null;
    return C.forecastAt(forecast, t);
  }

  async function fetchForecast(p) {
    if (fetching) return;
    fetching = true; fetchError = ''; renderWeather();
    // Rounded to about 1 km: Open-Meteo doesn't need your exact spot.
    const lat = Math.round(p.lat * 100) / 100, lon = Math.round(p.lon * 100) / 100;
    const get = async (url) => {
      const r = await fetch(url, { referrerPolicy: 'no-referrer' });
      const body = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(body.reason || `HTTP ${r.status}`);
      return body;
    };
    try {
      const wind = await get(`https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m` +
        '&models=ecmwf_ifs&forecast_days=7&wind_speed_unit=kn&timeformat=unixtime&cell_selection=sea');
      // Waves are optional: the wind still shows if this fails.
      const marine = await get(`https://marine-api.open-meteo.com/v1/marine?latitude=${lat}&longitude=${lon}&hourly=wave_height,wind_wave_height,` +
        'wind_wave_direction,wind_wave_period,swell_wave_height,swell_wave_direction,swell_wave_period&models=meteofrance_wave&forecast_days=7' +
        '&timeformat=unixtime&cell_selection=nearest').catch(() => null);
      forecast = C.parseForecast(wind, marine, lat, lon, Date.now());
      try { localStorage.setItem(FKEY, JSON.stringify(forecast)); } catch (e) { /* kept for this visit only */ }
    } catch (e) {
      fetchError = navigator.onLine === false ? 'No internet: showing the last forecast kept.' : `Couldn't get the forecast (${e.message}).`;
    }
    fetching = false; renderWeather(); renderBar();
  }
  /** On opening the tab: refresh if online and the kept forecast is old or for somewhere else. */
  function weatherShown() {
    const p = here().p;
    const stale = !forecast || Date.now() - forecast.fetched > 3 * 3600000 || C.distanceNm(p, forecast) > 13.5;
    if (stale && navigator.onLine !== false) fetchForecast(p); else renderWeather();
  }

  const WIND_STOPS = [0, 5, 10, 15, 20, 25, 30, 40], WIND_COLOURS = ['#E3F2FD', '#B3E5FC', '#80DEEA', '#A5D6A7', '#FFF59D', '#FFCC80', '#EF9A9A', '#CE93D8'];
  const WAVE_STOPS = [0, 0.5, 1, 1.5, 2, 3, 4, 6], WAVE_COLOURS = ['#B3E5FC', '#4FC3F7', '#26A69A', '#9CCC65', '#FFEE58', '#FFA726', '#EF5350', '#AB47BC'];
  function blend(stops, colours, v) {
    if (!(v > stops[0])) return colours[0];
    for (let i = 1; i < stops.length; i++) if (v < stops[i]) {
      const k = (v - stops[i - 1]) / (stops[i] - stops[i - 1]);
      const a = colours[i - 1].match(/\w\w/g).map((x) => parseInt(x, 16)), b = colours[i].match(/\w\w/g).map((x) => parseInt(x, 16));
      return `rgb(${a.map((x, j) => Math.round(x + (b[j] - x) * k)).join(',')})`;
    }
    return colours[colours.length - 1];
  }

  /**
   * A week at one place (like ForecastChartView in the app): the main value filled with its colour, a dashed second
   * line, direction arrows along the top, days shaded in turn. Tap or drag to pick a time.
   */
  function forecastChart(pts, { stops, colours, unit, minTop, step, decimals }, selected, onPick) {
    const cv = el('canvas.chart', { height: 170 });
    const draw = () => {
      const dpr = devicePixelRatio || 1, W = cv.clientWidth || 300, H = 170;
      cv.width = W * dpr; cv.height = H * dpr;
      const c = cv.getContext('2d'); c.scale(dpr, dpr); c.clearRect(0, 0, W, H);
      if (pts.length < 2) return;
      const left = 28, right = W - 4, top = 34, bottom = H - 18, from = pts[0].t, to = pts[pts.length - 1].t;
      const x = (t) => left + (right - left) * (t - from) / (to - from);
      const peak = Math.max(...pts.flatMap((p) => [p.main, p.second]).filter((v) => v !== null && isFinite(v)), 0);
      const yMax = Math.max(minTop, Math.ceil((peak * 1.1) / step) * step);
      const y = (v) => bottom - (bottom - top) * (v / yMax);
      c.font = '10px system-ui, sans-serif';
      // Days.
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
      // Main value.
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
      // Arrows: the way it's going.
      const hours = (to - from) / 3600000, every = [1, 2, 3, 6, 12].find((n) => (right - left) / (hours / n) >= 18) || 12;
      pts.forEach((p, i) => {
        if (i % every || p.dir === null) return;
        const r = (p.dir + 180) * Math.PI / 180, ux = Math.sin(r), uy = -Math.cos(r), cx = x(p.t), cy = 17, L = 11;
        c.beginPath(); c.moveTo(cx + ux * L / 2, cy + uy * L / 2);
        c.lineTo(cx - ux * L / 2 - uy * L * 0.32, cy - uy * L / 2 + ux * L * 0.32); c.lineTo(cx - ux * L * 0.15, cy - uy * L * 0.15);
        c.lineTo(cx - ux * L / 2 + uy * L * 0.32, cy - uy * L / 2 - ux * L * 0.32); c.closePath();
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
    requestAnimationFrame(() => {
      const dpr = devicePixelRatio || 1, W = cv.clientWidth || 300, H = 150;
      cv.width = W * dpr; cv.height = H * dpr;
      const c = cv.getContext('2d'); c.scale(dpr, dpr);
      const n = 144, hs = Array.from({ length: n + 1 }, (_, i) => tides.height(st, from + (to - from) * i / n));
      const span = Math.max(0.2, Math.max(...hs.map(Math.abs)) * 1.15);
      const left = 30, right = W - 6, top = 16, bottom = H - 16;
      const x = (t) => left + (right - left) * (t - from) / (to - from), y = (h) => top + (bottom - top) * (0.5 - h / (2 * span));
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
      c.beginPath(); hs.forEach((h, i) => { const px = left + (right - left) * i / n; if (i) c.lineTo(px, y(h)); else c.moveTo(px, y(h)); });
      c.strokeStyle = '#8EC1E6'; c.lineWidth = 2; c.stroke();
      c.textAlign = 'center'; c.fillStyle = '#ffffff';
      for (const tt of tides.turns(st, from, to)) {
        c.beginPath(); c.arc(x(tt.time), y(tt.metres), 2.5, 0, 7); c.fill();
        c.fillText(new Date(tt.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), x(tt.time), tt.high ? y(tt.metres) - 5 : y(tt.metres) + 12);
      }
      c.fillRect(x(now), top, 1.2, bottom - top);
    });
    return cv;
  }

  function renderWeather() {
    const pane = $('pane-weather');
    if (!pane) return;
    const { p, at } = here(), now = Date.now(), out = [];
    out.push(el('p.muted', {}, `For ${at === 'boat' ? 'your boat' : 'the middle of the map'}: ${C.fmtPos(p)}`),
      el('div.btns', {}, el('button.btn', { onclick: () => renderWeather() }, at === 'boat' ? 'Refresh' : 'Use the map centre now')));

    // Tide.
    out.push(el('h3', {}, 'Tide'));
    const t = tideNow(p, now);
    if (!t) out.push(el('p.empty', {}, 'No tide prediction here: it covers the WA coast, within about 60 km of a model cell.'));
    else {
      const km = C.distanceNm(p, t.st) * 1.852;
      out.push(el('p', {}, `Now ${t.h >= 0 ? '+' : ''}${t.h.toFixed(2)} m, ${t.rising ? 'rising' : 'falling'} · near ${t.st.near}${km >= 1 ? ` (model point ${km.toFixed(0)} km away)` : ''}`),
        tideChart(t.st, now - 6 * 3600000, now + 42 * 3600000, now),
        el('div.turns', {}, tides.turns(t.st, now, now + 72 * 3600000).map((x) => el('div', {}, el('span', {}, x.high ? 'High' : 'Low'),
          el('span', {}, new Date(x.time).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' })), el('span', {}, `${x.metres >= 0 ? '+' : ''}${x.metres.toFixed(2)} m`)))),
        el('p.muted', {}, 'Heights are above mean sea level, the same level as the chart\'s depths, so the water is about chart depth + tide. ' +
          'From the EOT20 global tide model (DGFI-TUM, CC BY 4.0), worked out on this device: roughly ±0.1–0.2 m and a few minutes on the open coast. ' +
          'Not right inside rivers and estuaries (Swan, Peel–Harvey), and wind and pressure can move the sea by more than the tide.'));
    }

    // Wind and sea.
    out.push(el('h3', {}, 'Wind, sea & swell forecast'));
    const f = forecast && C.distanceNm(p, forecast) <= 13.5 ? forecast : null;
    if (fetching) out.push(el('p.empty', {}, 'Getting the forecast…'));
    if (fetchError) out.push(el('p.empty', {}, fetchError));
    if (!f) {
      out.push(el('p.empty', {}, forecast ? `The forecast kept is for ${C.fmtPos(forecast)}, too far from here.` : 'No forecast kept yet.'));
    }
    out.push(el('div.btns', {}, el('button.btn', { disabled: fetching, onclick: () => fetchForecast(p) }, fetching ? 'Downloading…' : 'Download for here')));
    if (f) {
      const hrs = f.hours.filter((h) => h.t >= now - 3600000);
      if (pickT !== null && (pickT < now || pickT > f.hours[f.hours.length - 1].t)) pickT = null;
      const sel = () => pickT ?? now;
      const read = el('p', {});
      const charts = [];
      const describe = () => {
        const h = C.forecastAt(f, sel());
        read.textContent = `${pickT === null ? 'Now' : new Date(pickT).toLocaleString([], { weekday: 'short', hour: 'numeric' })}: ` +
          [h && h.kn !== null ? `Wind ${C.windText(h)}` : null, C.wavesText(h) || null].filter(Boolean).join('. ');
      };
      const slider = el('input', { type: 'range', min: 0, max: Math.max(0, Math.round((f.hours[f.hours.length - 1].t - now) / 3600000)), step: 1, ariaLabel: 'Forecast time',
        value: pickT === null ? 0 : Math.round((pickT - now) / 3600000) });
      const setPick = (tt, fromSlider) => {
        pickT = tt <= now ? null : tt;
        if (!fromSlider) slider.value = pickT === null ? 0 : Math.round((pickT - now) / 3600000);
        describe(); charts.forEach((c) => c.redraw());
      };
      slider.addEventListener('input', () => setPick(now + Number(slider.value) * 3600000, true));
      describe();
      out.push(read, el('div.row', {}, el('span.muted', {}, 'Now'), slider, el('span.muted', {}, '+7 days')));
      if (hrs.some((h) => h.kn !== null)) {
        const pts = hrs.map((h) => ({ t: h.t, main: h.kn, second: h.gust, dir: h.from }));
        out.push(el('p.muted', {}, 'Wind (kn): colour is the mean speed, the dashed line gusts, arrows the way it blows. Tap or drag to pick a time.'));
        const ch = forecastChart(pts, { stops: WIND_STOPS, colours: WIND_COLOURS, unit: 'kn', minTop: 20, step: 10, decimals: 0 }, sel, (tt) => setPick(tt, false));
        charts.push(ch); out.push(ch);
      }
      if (hrs.some((h) => h.wave !== null)) {
        const pts = hrs.map((h) => ({ t: h.t, main: h.wave, second: h.swell, dir: h.swellFrom }));
        out.push(el('p.muted', {}, 'Sea & swell (m): colour is the combined height, the dashed line the swell, arrows the way the swell runs.'));
        const ch = forecastChart(pts, { stops: WAVE_STOPS, colours: WAVE_COLOURS, unit: 'm', minTop: 2, step: 1, decimals: 1 }, sel, (tt) => setPick(tt, false));
        charts.push(ch); out.push(ch);
      } else out.push(el('p.empty', {}, 'No sea & swell forecast here (land, or too close to the coast for the wave model).'));
      const ago = Math.round((now - f.fetched) / 60000);
      out.push(el('p.muted', {}, `Wind: ECMWF IFS 9 km via Open-Meteo.com (CC BY 4.0). Waves: MFWAM 8 km, generated using E.U. Copernicus Marine Service Information, via Open-Meteo.com. ` +
        `Downloaded ${ago < 60 ? `${ago} min` : `${Math.round(ago / 60)} h`} ago and kept for when you're offline: open this tab while online before you go. ` +
        'Only the position, rounded to about 1 km, is sent. Close to the coast and inside reefs the waves are only a guide.'));
    }
    pane.replaceChildren(...out);
  }
  addEventListener('resize', () => { if (state.tab === 'weather') renderWeather(); });
