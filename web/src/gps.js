  // ---- GPS: your boat, the data bar, track, go to, anchor and zone alarms -----------------------------------
  // A web page only gets positions while it's open and the screen is on: iOS stops them when the phone locks.
  const gps = { watch: null, fix: null, hist: [], sog: null, cog: null, follow: false, zone: '', depth: null, checkAt: 0, anchorOut: 0 };
  const nav = { target: null, route: null, index: 0, from: null };
  const KN = 1.943844;   // knots per m/s
  const disp = (k, d) => (state.display[k] === undefined ? d : state.display[k]);
  const FIELDS = [['sog', 'SOG'], ['cog', 'COG'], ['depth', 'Depth'], ['depthNow', 'Depth now'], ['tide', 'Tide'], ['wind', 'Wind'], ['waves', 'Sea / swell'], ['pos', 'Position']];
  const fieldOn = (k) => disp(`f_${k}`, ['sog', 'cog', 'depth', 'tide', 'wind'].includes(k));

  /** Your boat: a pointed hull when moving, a dot when still. 24 units at 2x, drawn pointing up (north). */
  function boatIcon(moving) {
    const n = 48, cv = Object.assign(document.createElement('canvas'), { width: n, height: n }), c = cv.getContext('2d');
    c.scale(2, 2); c.lineJoin = 'round';
    if (moving) {
      c.beginPath(); c.moveTo(12, 1.5); c.bezierCurveTo(18, 8, 18.5, 15, 17, 22); c.lineTo(7, 22); c.bezierCurveTo(5.5, 15, 6, 8, 12, 1.5); c.closePath();
    } else { c.beginPath(); c.arc(12, 12, 7, 0, 7); }
    c.fillStyle = '#1565C0'; c.fill(); c.strokeStyle = '#ffffff'; c.lineWidth = 2.2; c.stroke();
    return c.getImageData(0, 0, n, n);
  }

  function drawGps() {
    const f = gps.fix;
    const moving = f && gps.cog !== null && (gps.sog || 0) >= 0.5;
    setData('boat', f ? [point(f, { icon: moving ? 'boat-arrow' : 'boat-dot', rot: moving ? gps.cog : 0, size: disp('boatSize', 100) / 100 })] : []);
    setData('boat-acc', f && f.acc > 15 ? [{ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [C.circle(f, Math.min(f.acc, 2000))] } }] : []);
    setData('goto-line', f && nav.target ? [line([f, nav.target])] : nav.target && nav.from ? [line([nav.from, nav.target])] : []);
    const a = state.anchor;
    setData('anchor', a ? [{ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [C.circle(a, a.r)] } }] : []);
  }

  /** Where things happen: the boat if we have a recent fix, otherwise the middle of the map. */
  function here() {
    if (gps.fix && Date.now() - gps.fix.t < 120000) return { p: { lat: gps.fix.lat, lon: gps.fix.lon }, at: 'boat' };
    const c = map.getCenter();
    return { p: { lat: c.lat, lon: c.lng }, at: 'centre' };
  }

  // ---- Starting and stopping -------------------------------------------------------------------------------
  function startGps(follow = true) {
    if (!('geolocation' in navigator)) { dialog('No GPS', 'This browser has no location service.'); return; }
    if (!isSecureContext) {
      dialog('GPS needs the website', ['Browsers only share your position with pages loaded over https, not with a file opened from disk or the Files app.',
        'Open the planner from its website instead (Data tab → About), and on an iPhone tap Share → Add to Home Screen. It then works offline too.']);
      return;
    }
    gps.follow = follow;
    if (gps.watch === null) {
      gps.watch = navigator.geolocation.watchPosition(onFix, onGpsError, { enableHighAccuracy: true, maximumAge: 2000, timeout: 30000 });
      toast('Finding your position…');
    } else if (gps.fix && follow) map.easeTo({ center: [gps.fix.lon, gps.fix.lat], zoom: Math.max(map.getZoom(), 12) });
    state.display.gps = true; save();
    keepAwake(true); renderGpsUi();
  }
  function stopGps() {
    if (gps.watch !== null) navigator.geolocation.clearWatch(gps.watch);
    Object.assign(gps, { watch: null, fix: null, hist: [], sog: null, cog: null, follow: false, zone: '' });
    state.display.gps = false; save();
    keepAwake(false); drawGps(); renderGpsUi();
  }
  /** After a reload, carry on where the GPS was left. */
  function resumeGps() {
    if (state.display.gps && isSecureContext && 'geolocation' in navigator) startGps(false);
    drawGps(); renderGpsUi();
  }
  function onGpsError(e) {
    if (e.code === 1) {
      stopGps();
      dialog('Location is off for this page', IOS ? ['Allow it in Settings → Privacy & Security → Location Services → Safari Websites (While Using), then try again.',
        'If you added the planner to your Home Screen, also check Settings → Apps → Safari → Location.'] : 'Allow location for this page in the browser\'s site settings, then try again.');
    } else if (!gps.fix) toast(e.code === 3 ? 'Still looking for a GPS fix…' : 'No position yet. Out in the open works best.');
  }

  function onFix(pos) {
    const c = pos.coords, t = Date.now();
    const f = { lat: c.latitude, lon: c.longitude, t, acc: c.accuracy || 0 };
    const first = !gps.fix;
    gps.fix = f;
    // Speed and course: from the phone if it gives them, otherwise from the last 10–60 s of fixes.
    gps.hist.push(f); while (gps.hist.length && t - gps.hist[0].t > 60000) gps.hist.shift();
    let sog = c.speed !== null && c.speed >= 0 ? c.speed * KN : null;
    let cog = c.heading !== null && isFinite(c.heading) && c.heading >= 0 ? c.heading : null;
    if (sog === null || cog === null) {
      const old = gps.hist.find((h) => t - h.t >= 10000);
      const sc = old && C.sogCog(old, f);
      if (sc) { if (sog === null) sog = sc.sog; if (cog === null) cog = sc.cog; }
    }
    gps.sog = sog; gps.cog = sog !== null && sog < 0.5 ? gps.cog : cog;
    if (gps.follow || first) map.easeTo({ center: [f.lon, f.lat], zoom: first ? Math.max(map.getZoom(), 12) : map.getZoom(), duration: first ? 800 : 500 });
    recordFix(f);
    navStep(f);
    anchorStep(f);
    if (t - gps.checkAt > 3000) { gps.checkAt = t; chartStep(f); }
    drawGps(); renderBar();
  }

  // ---- Screen on, sounds -----------------------------------------------------------------------------------
  let wake = null;
  async function keepAwake(on) {
    if (!on || !disp('awake', true)) { if (wake) { wake.release().catch(() => {}); wake = null; } return; }
    if (wake || !('wakeLock' in navigator) || document.visibilityState !== 'visible') return;
    try { wake = await navigator.wakeLock.request('screen'); wake.addEventListener('release', () => { wake = null; }); } catch (e) { /* not allowed */ }
  }
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && gps.watch !== null) keepAwake(true);
    if (document.visibilityState === 'hidden') saveNow();
  });

  let audio = null;
  // Browsers only allow sound after a tap, so wake the audio up on the first one.
  addEventListener('pointerdown', () => {
    try { audio = audio || new (window.AudioContext || window.webkitAudioContext)(); if (audio.state === 'suspended') audio.resume(); } catch (e) { /* no sound */ }
  }, true);
  function beep(n = 3, hz = 880) {
    if (!audio || !disp('sound', true)) return;
    for (let i = 0; i < n; i++) {
      const o = audio.createOscillator(), g = audio.createGain(), t0 = audio.currentTime + i * 0.45;
      o.frequency.value = hz; o.connect(g); g.connect(audio.destination);
      g.gain.setValueAtTime(0.0001, t0); g.gain.exponentialRampToValueAtTime(0.5, t0 + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.3);
      o.start(t0); o.stop(t0 + 0.32);
    }
  }
  let alarmTimer = 0;
  /** A red banner over the map. [repeat] keeps beeping until it's dismissed. */
  function alarm(title, text, repeat = false) {
    const box = $('alarm');
    clearInterval(alarmTimer);
    box.replaceChildren(el('strong', {}, title), el('span', {}, text), el('button.btn', { onclick: () => { box.hidden = true; clearInterval(alarmTimer); } }, 'OK'));
    box.hidden = false;
    if (navigator.vibrate) navigator.vibrate([400, 200, 400]);
    beep();
    if (repeat) alarmTimer = setInterval(() => { if (box.hidden) clearInterval(alarmTimer); else { beep(); if (navigator.vibrate) navigator.vibrate(400); } }, 5000);
  }

  // ---- Track ---------------------------------------------------------------------------------------------------
  let trackSaveAt = 0;
  function recordFix(f) {
    if (!state.recording || f.acc > 50) return;
    const last = state.track[state.track.length - 1];
    const m = last ? C.distanceNm(last, f) * 1852 : Infinity;
    if (m < 10 && !(last && f.t - (last.t || 0) > 60000 && m >= 3)) return;
    state.track.push({ lat: f.lat, lon: f.lon, t: f.t });
    if (f.t - trackSaveAt > 15000) { trackSaveAt = f.t; save(); }
    drawData();
  }
  function toggleRecording() {
    if (state.recording) { state.recording = false; save(); renderGpsUi(); toast(`Track stopped: ${state.track.length} points. Data tab → Export GPX to keep it.`); return; }
    state.recording = true; save();
    if (gps.watch === null) startGps(true);
    renderGpsUi();
    toast(IOS ? 'Recording. Keep the planner open and the screen on: iOS stops the GPS when the phone locks.' : 'Recording. Keep this page open with the screen on.');
  }

  // ---- Go to a point, or follow a route ---------------------------------------------------------------------
  function goTo(target) {
    nav.route = null; nav.index = 0; nav.target = { name: target.name, lat: target.lat, lon: target.lon };
    nav.from = gps.fix ? { lat: gps.fix.lat, lon: gps.fix.lon } : null;
    if (gps.watch === null) startGps(true);
    drawGps(); renderBar(); renderRoutes();
    toast(`Going to ${target.name}`);
  }
  function followRoute(wps) {
    if (wps.length < 2) return;
    nav.route = wps.map((w, i) => ({ name: w.name || `WP${i + 1}`, lat: w.lat, lon: w.lon }));
    // Start at the leg we're nearest to, heading for its far end.
    let best = 1;
    if (gps.fix) {
      let bd = Infinity;
      for (let i = 1; i < nav.route.length; i++) { const d = C.segDistM(gps.fix, nav.route[i - 1], nav.route[i]); if (d < bd) { bd = d; best = i; } }
    }
    setLeg(best);
    if (gps.watch === null) startGps(true);
    renderRoutes(); toast(`Following ${state.plan.name || 'the route'}: heading for ${nav.target.name}`);
  }
  function setLeg(i) {
    nav.index = i; nav.target = nav.route[i]; nav.from = nav.route[i - 1] || null;
    drawGps(); renderBar();
  }
  function stopNav() { Object.assign(nav, { target: null, route: null, index: 0, from: null }); drawGps(); renderBar(); renderRoutes(); }
  function navStep(f) {
    if (!nav.target) return;
    if (C.distanceNm(f, nav.target) * 1852 > disp('arrive', 50)) return;
    beep(2, 1200);
    if (nav.route && nav.index + 1 < nav.route.length) {
      toast(`Arrived at ${nav.target.name}. Next: ${nav.route[nav.index + 1].name}`);
      setLeg(nav.index + 1);
    } else { toast(`Arrived at ${nav.target.name}`); stopNav(); }
  }

  // ---- Anchor alarm --------------------------------------------------------------------------------------------
  async function anchorButton() {
    if (state.anchor) {
      const a = await dialog('Anchor alarm', `Watching a ${state.anchor.r} m circle. ${gps.fix ? `You're ${Math.round(C.distanceNm(gps.fix, state.anchor) * 1852)} m from the anchor.` : 'No GPS fix at the moment.'}`,
        [['Cancel', null], ['Stop the alarm', 'off', 'btn danger']]);
      if (a === 'off') { state.anchor = null; save(); drawGps(); renderGpsUi(); }
      return;
    }
    if (!gps.fix) { startGps(true); toast('Waiting for a GPS fix: press ⚓ again once your boat shows.'); return; }
    const sel = el('select', { ariaLabel: 'Radius' }, [20, 30, 40, 50, 75, 100, 150, 200].map((m) => el('option', { value: m, selected: m === disp('anchorR', 50) }, `${m} m`)));
    const ok = await dialog('Drop anchor here', ['Alarm when the boat drifts further than this from where it is now. The planner must stay open with the screen on.',
      el('div.row', {}, el('span.grow', {}, 'Radius'), sel)], [['Cancel', null], ['Set alarm', true, 'primary']]);
    if (!ok || !gps.fix) return;
    state.display.anchorR = Number(sel.value);
    state.anchor = { lat: gps.fix.lat, lon: gps.fix.lon, r: Number(sel.value) };
    gps.anchorOut = 0; save(); keepAwake(true); drawGps(); renderGpsUi();
  }
  function anchorStep(f) {
    const a = state.anchor;
    if (!a) return;
    const m = C.distanceNm(f, a) * 1852;
    // Two fixes outside in a row, so one bad fix doesn't set it off.
    if (m > a.r) { if (++gps.anchorOut === 2) alarm('Anchor dragging', `${Math.round(m)} m from the anchor (alarm at ${a.r} m).`, true); }
    else { if (gps.anchorOut >= 2) toast('Back inside the anchor circle'); gps.anchorOut = 0; }
  }

  // ---- Zones and depth at the boat --------------------------------------------------------------------------------
  async function chartStep(f) {
    if (!reader) return;
    let r;
    try { r = await reader.at(f); } catch (e) { return; }
    gps.depth = r;
    const z = r.zones.find((x) => x.zone_class === 'notake');
    const name = z ? z.name || 'No fishing zone' : '';
    if (name && name !== gps.zone && disp('zoneAlarm', true)) alarm('No fishing zone', `You're in ${name}. Transit is fine; no fishing.`);
    else if (!name && gps.zone) toast(`Left ${gps.zone}`);
    gps.zone = name;
    renderBar();
  }

  // ---- Data bar and buttons ---------------------------------------------------------------------------------------
  function fieldValue(k) {
    const f = gps.fix, r = gps.depth;
    switch (k) {
      case 'sog': return [gps.sog === null ? '--' : gps.sog.toFixed(1), 'kn'];
      case 'cog': return [gps.cog === null || (gps.sog || 0) < 0.5 ? '--' : C.fmtBearing(gps.cog), 'T'];
      case 'depth': return [r ? C.depthText(r).replace(' m', '') : '--', 'm'];
      case 'depthNow': return [f && r ? depthNow(f, r) : '--', 'm'];
      case 'tide': { const t = f && tideNow(f); return [t ? `${t.h >= 0 ? '+' : ''}${t.h.toFixed(2)}${t.rising ? '↑' : '↓'}` : '--', 'm']; }
      case 'wind': { const h = f && forecastHere(f, Date.now()); return [h && h.kn !== null ? `${Math.round(h.kn)}${h.gust !== null ? ` G${Math.round(h.gust)}` : ''} ${h.from !== null ? C.compass(h.from) : ''}` : '--', 'kn']; }
      case 'waves': { const h = f && forecastHere(f, Date.now()); return [h && h.wave !== null ? `${(h.sea ?? 0).toFixed(1)} / ${(h.swell ?? 0).toFixed(1)}` : '--', 'm']; }
      case 'pos': return [f ? C.fmtPos(f).replace('  ', '\n') : '--', ''];
      default: return ['--', ''];
    }
  }
  function renderBar() {
    const bar = $('bar');
    const on = (gps.watch !== null || nav.target) && disp('bar', true);
    bar.hidden = !on;
    if (!on) return;
    bar.style.setProperty('--bar-scale', disp('barSize', 100) / 100);
    bar.style.opacity = disp('barAlpha', 100) / 100;
    const cell = (k, label, [v, u], cls = '') => el(`div.f${cls}`, {}, el('span.k', {}, label), el('span.v', {}, v), u ? el('span.u', {}, u) : null);
    const cells = FIELDS.filter(([k]) => fieldOn(k)).map(([k, label]) => cell(k, label, fieldValue(k)));
    if (gps.zone) cells.unshift(el('div.f.zone', {}, el('span.k', {}, 'NO FISHING'), el('span.v.small', {}, gps.zone)));
    if (gps.watch !== null && !gps.fix) cells.unshift(el('div.f', {}, el('span.k', {}, 'GPS'), el('span.v.small', {}, 'Searching…')));
    else if (gps.fix && gps.fix.acc > 50) cells.unshift(el('div.f.warn', {}, el('span.k', {}, 'GPS'), el('span.v.small', {}, `±${Math.round(gps.fix.acc)} m`)));
    if (nav.target) {
      const f = gps.fix, t = nav.target;
      const d = f ? C.distanceNm(f, t) : null, brg = f ? C.bearing(f, t) : null;
      const eta = d !== null && gps.sog > 0.5 ? C.fmtDuration(d / gps.sog) : '--';
      const xte = f && nav.from ? C.xteNm(f, nav.from, t) : null;
      cells.push(el('div.f.nav', {}, el('span.k', {}, `To ${t.name}${nav.route ? ` (${nav.index + 1}/${nav.route.length})` : ''}`),
        el('span.v', {}, d === null ? '--' : `${C.fmtNm(d)} · ${C.fmtBearing(brg)}`),
        el('span.u', {}, `ETA ${eta}${xte !== null && Math.abs(xte) * 1852 >= 10 ? ` · XTE ${Math.round(Math.abs(xte) * 1852)} m ${xte > 0 ? 'R' : 'L'}` : ''}`),
        nav.route ? el('button.icon', { title: 'Skip to the next waypoint', ariaLabel: 'Next waypoint', disabled: nav.index + 1 >= nav.route.length, onclick: () => setLeg(nav.index + 1) }, '⏭') : null,
        el('button.icon', { title: 'Stop', ariaLabel: 'Stop going to', onclick: stopNav }, '✕')));
    }
    bar.replaceChildren(...cells);
  }
  function renderGpsUi() {
    const b = $('mapbtns');
    b.style.setProperty('--btn-scale', disp('btnSize', 100) / 100);
    b.style.opacity = disp('btnAlpha', 100) / 100;
    b.hidden = !disp('btns', true);
    const loc = $('b-gps');
    loc.classList.toggle('on', gps.watch !== null);
    loc.classList.toggle('follow', gps.follow);
    loc.title = gps.watch === null ? 'Show my position (GPS)' : gps.follow ? 'Following the boat. Tap to turn the GPS off' : 'Centre on the boat';
    $('b-rec').classList.toggle('rec', !!state.recording);
    $('b-rec').title = state.recording ? 'Recording the track. Tap to stop' : 'Record the track';
    $('b-anchor').classList.toggle('on', !!state.anchor);
    renderBar();
  }
  $('b-gps').onclick = () => {
    if (gps.watch === null) startGps(true);
    else if (!gps.follow) startGps(true);
    else { stopGps(); toast('GPS off'); }
  };
  $('b-mark').onclick = () => { const h = here(); addMark(h.p); };
  $('b-catch').onclick = () => { const h = here(); logCatch(h.p, h.at); };
  $('b-rec').onclick = toggleRecording;
  $('b-anchor').onclick = anchorButton;
  map.on('dragstart', (e) => { if (e.originalEvent && gps.follow) { gps.follow = false; renderGpsUi(); } });

  // ---- Display settings (in the Layers tab) -------------------------------------------------------------------------
  function displaySettings() {
    const set = (k, v) => { state.display[k] = v; save(); drawGps(); renderGpsUi(); };
    const check = (k, d, label) => el('div.row', {}, el('input', { type: 'checkbox', id: `d-${k}`, checked: disp(k, d), onchange: (e) => set(k, e.target.checked) }), el('label.grow', { htmlFor: `d-${k}` }, label));
    const slider = (k, label, from, to) => {
      const pct = el('span.pct', {}, `${disp(k, 100)}%`);
      return el('div.layer', {}, el('span', {}), el('label', {}, label),
        el('input', { type: 'range', min: from, max: to, step: 5, value: disp(k, 100), ariaLabel: label, oninput: (e) => { pct.textContent = `${e.target.value}%`; set(k, Number(e.target.value)); } }), pct);
    };
    const arrive = el('select', { ariaLabel: 'Arrival radius', onchange: (e) => set('arrive', Number(e.target.value)) },
      [20, 50, 100, 200, 500].map((m) => el('option', { value: m, selected: m === disp('arrive', 50) }, `${m} m`)));
    return [el('h3', {}, 'Display'),
      check('bar', true, 'Data bar (while the GPS is on)'), slider('barSize', 'Data bar size', 60, 160), slider('barAlpha', 'Data bar opacity', 30, 100),
      el('div.fields', {}, FIELDS.map(([k, label]) => el('label', {}, el('input', { type: 'checkbox', checked: fieldOn(k), onchange: (e) => { state.display[`f_${k}`] = e.target.checked; save(); renderBar(); } }), label))),
      check('btns', true, 'Map buttons (GPS, mark, catch, track, anchor)'), slider('btnSize', 'Button size', 60, 160), slider('btnAlpha', 'Button opacity', 30, 100),
      slider('boatSize', 'Boat size', 50, 200),
      el('h3', {}, 'GPS'),
      check('awake', true, 'Keep the screen on while the GPS is on'),
      check('zoneAlarm', true, 'Alarm on entering a no-fishing zone'),
      check('sound', true, 'Sound with alarms'),
      el('div.row', {}, el('span.grow', {}, 'Arrived within'), arrive),
      el('p.empty', {}, IOS ? 'iOS stops the GPS when the phone locks or you switch apps, so the track, alarms and go-to only work while the planner is open.' :
        'The GPS, track and alarms only work while this page is open.')];
  }
