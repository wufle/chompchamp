  // ---- GPS and the boat: modes, follow, data fields, navigation, drift, trips, anchor and zone alerts -------------------
  // A web page only gets positions while it's open with the screen on: iOS stops them when the phone locks.
  const gps = { watch: null, fix: null, hist: [], sog: null, cog: null, heading: null, follow: false, zone: null, depth: null, busy: false };
  const nav = new C.Navigator(state.nav);
  const drift = new C.DriftTracker();
  let lastNav = null, modeAfterNav = state.modeAfterNav || 'chart', movingWaypoint = null, lastAnchorBuzz = 0;
  const KN = 1.943844;   // knots per m/s

  /** The boat, if we have a recent fix. */
  const lastPos = () => (gps.fix && Date.now() - gps.fix.t < 120000 ? { lat: gps.fix.lat, lon: gps.fix.lon } : null);
  const mapCentre = () => { const c = map.getCenter(); return { lat: c.lat, lon: c.lng }; };
  /** Where things happen: the boat, otherwise the middle of the map. */
  function here() { const b = lastPos(); return b ? { p: b, at: 'boat' } : { p: mapCentre(), at: 'centre' }; }

  // ---- Starting the GPS -----------------------------------------------------------------------------------------------
  function startGps() {
    if (!('geolocation' in navigator)) { dialog('No GPS', 'This browser has no location service.'); return false; }
    if (!isSecureContext) {
      dialog('GPS needs the website', ['Browsers only share your position with pages loaded over https, not with a file opened from disk or the Files app.',
        'Open the planner from its website instead (☰ → About), and on an iPhone tap Share → Add to Home Screen. It then works offline too.']);
      return false;
    }
    if (gps.watch === null) {
      gps.watch = navigator.geolocation.watchPosition(onFix, onGpsError, { enableHighAccuracy: true, maximumAge: 2000, timeout: 30000 });
      snack('Finding your position…');
    }
    if (!state.gps) { state.gps = true; save(); }
    keepAwake(true);
    return true;
  }
  function stopGps() {
    if (gps.watch !== null) navigator.geolocation.clearWatch(gps.watch);
    Object.assign(gps, { watch: null, fix: null, hist: [], sog: null, cog: null, follow: false, zone: null, depth: null });
    state.gps = false; save();
    keepAwake(false); drawBoat(); updateFields(); updateBanner(); styleLocate();
  }
  function onGpsError(e) {
    if (e.code === 1) {
      stopGps();
      dialog('Location is off for this page', IOS ? ['Allow it in Settings → Privacy & Security → Location Services → Safari Websites (While Using), then try again.',
        'If you added the planner to your Home Screen, also check Settings → Apps → Safari → Location.'] : "Allow location for this page in the browser's site settings, then try again.");
    } else if (!gps.fix) snack(e.code === 3 ? 'Still looking for a GPS fix…' : 'No position yet. Out in the open works best.');
  }

  /** Follow the boat (the button under the map's bottom right). Starts the GPS if it's off. */
  function followBoat(zoom = null) {
    if (!startGps()) return;
    gps.follow = true; styleLocate();
    if (gps.fix) moveToBoat(zoom ?? Math.max(map.getZoom(), 12), 750);
    else gps.followZoom = zoom;
  }
  function moveToBoat(zoom = map.getZoom(), duration = 500) {
    const f = gps.fix;
    const courseUp = setting('course_up') > 0.5 && (state.mode === 'nav' || state.mode === 'fish');
    map.easeTo({ center: [f.lon, f.lat], zoom, bearing: courseUp && gps.cog !== null ? gps.cog : 0, duration });
  }
  const unfollow = () => { if (gps.follow) { gps.follow = false; styleLocate(); } };
  map.on('dragstart', (e) => { if (e.originalEvent) unfollow(); });
  map.on('zoomstart', (e) => { if (e.originalEvent && e.originalEvent.type === 'touchmove') { /* pinch: keep following, at the new zoom */ } });
  function styleLocate() { $('btn-locate').classList.toggle('active', gps.follow && gps.watch !== null); }
  $('btn-locate').onclick = () => {
    if (gps.follow && gps.fix) { moveToBoat(); return; }
    followBoat(state.mode === 'fish' ? setting('fish_zoom') : null);
  };

  function onFix(pos) {
    const c = pos.coords, t = Date.now();
    const f = { lat: c.latitude, lon: c.longitude, t, acc: c.accuracy || 0 };
    const first = !gps.fix;
    gps.fix = f;
    // Speed and course from the phone if it gives them, otherwise from the last 10–60 s of fixes.
    gps.hist.push(f); while (gps.hist.length && t - gps.hist[0].t > 60000) gps.hist.shift();
    let sog = c.speed !== null && c.speed >= 0 ? c.speed * KN : null;
    let cog = c.heading !== null && isFinite(c.heading) && c.heading >= 0 ? c.heading : null;
    if (sog === null || cog === null) {
      const old = gps.hist.find((h) => t - h.t >= 10000);
      const sc = old && C.sogCog(old, f);
      if (sc) { if (sog === null) sog = sc.sog; if (cog === null) cog = sc.cog; }
    }
    if (sog !== null && sog > 80) sog = null;   // a jump in position, not a speed
    gps.sog = sog;
    gps.cog = sog !== null && sog > 0.5 ? cog : null;
    if (gps.cog !== null) gps.heading = gps.cog;
    const p = { lat: f.lat, lon: f.lon };

    if (recordTrack(f, sog)) drawTrack();
    drift.add(t, p, setting('drift_window'));
    const nd = nav.update(p, sog, gps.cog, setting('arrival_nm'));
    if (nd) {
      if (nd.arrived) { buzz(); snack(nd.finished ? `Arrived: ${nd.arrived}` : `Reached ${nd.arrived} → ${nd.target.name}`); }
      if (nd.finished) stopNavigation();
      else { lastNav = nd; saveNav(); }
    }
    if (gps.follow || first) moveToBoat(first ? Math.max(map.getZoom(), gps.followZoom || 12) : map.getZoom(), first ? 800 : 500);
    if (first) gps.followZoom = null;
    lookUpHere(p);
    drawBoat(); drawNav(); updateFields(); updateBanner();
    if (state.mode === 'nav' || state.mode === 'fish') refreshModePanel();
    bagMoved();
  }

  // ---- Screen on, buzz and beep -------------------------------------------------------------------------------------------
  let wake = null;
  async function keepAwake(on) {
    if (!on || setting('awake') < 0.5) { if (wake) { wake.release().catch(() => {}); wake = null; } return; }
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
  /** A vibration where there is one, and a beep (the phone app buzzes). */
  function buzz(short = false) {
    if (navigator.vibrate) navigator.vibrate(short ? 40 : [300, 150, 300]);
    if (!audio || setting('sound') < 0.5) return;
    for (let i = 0; i < (short ? 1 : 2); i++) {
      const o = audio.createOscillator(), g = audio.createGain(), t0 = audio.currentTime + i * 0.4;
      o.frequency.value = 880; o.connect(g); g.connect(audio.destination);
      g.gain.setValueAtTime(0.0001, t0); g.gain.exponentialRampToValueAtTime(0.4, t0 + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, t0 + (short ? 0.12 : 0.3));
      o.start(t0); o.stop(t0 + 0.32);
    }
  }

  // ---- The boat on the map ---------------------------------------------------------------------------------------------------
  function drawBoat() {
    const f = gps.fix;
    setData('boat', f ? [point(f, { rot: gps.heading ?? 0 })] : []);
    setData('boat-acc', f && f.acc > 15 ? [{ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [C.circle(f, Math.min(f.acc, 2000))] } }] : []);
  }
  /** Nav line to the waypoint, the route with the one being steered to, drift line and anchor circle. */
  function drawNav() {
    const p = lastPos(), t = nav.target;
    setData('nav-line', p && t ? [line([p, t])] : []);
    const d = drift.drift();
    setData('drift', state.mode === 'fish' && p && d && d.kn > 0.05 ? [line([p, C.destination(p, d.dir, d.kn * setting('drift_project') / 60)])] : []);
    setData('drift-end', state.mode === 'fish' && p && d && d.kn > 0.05 ? [point(C.destination(p, d.dir, d.kn * setting('drift_project') / 60), {})] : []);
    const a = state.anchor;
    setData('anchor', a ? [{ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [C.circle(a, setting('anchor_m'))] } }] : []);
    drawRoute();
  }

  // ---- Data fields ------------------------------------------------------------------------------------------------------------
  function updateFields() {
    const f = gps.fix, p = lastPos(), sog = gps.sog, cog = gps.cog, now = Date.now();
    const one = (v) => (v === null || v === undefined ? '--.-' : v.toFixed(1));
    fields.set('sog', one(sog)); fields.set('sog_kmh', sog === null ? '--.-' : (sog * 1.852).toFixed(1));
    fields.set('cog', cog === null ? '---°' : C.fmtBearing(cog));
    fields.set('pos', f ? `${C.fmtDdm(f.lat, true)}\n${C.fmtDdm(f.lon, false)}` : 'No GPS fix');
    fields.set('time', new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }));
    const s = trackStats();
    fields.set('trip', C.fieldNm(s.distanceNm));
    fields.set('trip_time', state.recording ? C.fmtDuration((now - recStart()) / 3600000) : '--');
    fields.set('trip_avg', s.avgMovingKn === null ? '--.-' : s.avgMovingKn.toFixed(1));
    fields.set('trip_max', s.maxKn > 0 ? s.maxKn.toFixed(1) : '--.-');
    const w = p && windAtNow(p), wv = p && wavesAtNow(p), td = p && tideNow(p);
    fields.set('wind', w ? C.windShort(w) : '--');
    fields.set('waves', wv ? C.wavesShort(wv) : '--');
    fields.set('tide', td ? `${td.h >= 0 ? '+' : ''}${td.h.toFixed(2)} ${td.rising ? '↑' : '↓'}` : '--');
    const n = lastNav;
    if (n && nav.active) {
      fields.set('wpt', `${n.target.name} (${n.index + 1}/${n.count})`); fields.set('brg', C.fmtBearing(n.bearing)); fields.set('dtw', C.fieldNm(n.dtw));
      fields.set('xte', C.fmtXte(n.xte)); fields.set('vmg', one(n.vmg)); fields.set('ttg', n.ttg === null ? '--' : C.fmtDuration(n.ttg));
      fields.set('eta_wpt', n.etaWpt ? C.fmtClock(n.etaWpt) : '--'); fields.set('eta_dest', n.etaDest ? C.fmtClock(n.etaDest) : '--'); fields.set('remain', C.fieldNm(n.remain));
    } else for (const k of ['wpt', 'brg', 'dtw', 'xte', 'vmg', 'ttg', 'eta_wpt', 'eta_dest', 'remain']) fields.set(k, placeholder(k));
    const d = drift.drift();
    fields.set('drift', d ? d.kn.toFixed(1) : '--.-'); fields.set('drift_dir', d && d.kn >= 0.05 ? C.fmtBearing(d.dir) : '---°');
    const marks = p ? visibleMarks() : [];
    const near = marks.reduce((a, m) => (!a || C.distanceNm(p, m) < C.distanceNm(p, a) ? m : a), null);
    fields.set('mark', near ? `${near.name}\n${C.distanceNm(p, near) * 1852 < 10 ? 'here' : `${C.fmtNm(C.distanceNm(p, near))} ${C.fmtBearing(C.bearing(p, near))}`}` : '--');
    fields.set('anchor', state.anchor && p ? `${Math.round(C.distanceNm(state.anchor, p) * 1852)}` : 'off');
    const r = gps.depth;
    fields.set('depth', r ? depthOf(r) : '--');
    fields.set('depth_now', r && p ? depthNow(p, r) : '--');
  }
  setInterval(() => { fields.set('time', new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })); if (state.recording) { updateRecord(); fields.set('trip_time', C.fmtDuration((Date.now() - recStart()) / 3600000)); } }, 1000);
  /** "20–25", ">2000", "land" or "--" (not charted). */
  const depthOf = (r) => (r.land ? 'land' : !r.depth ? '--' : r.depth[1] >= 2000 ? `>${r.depth[0]}` : `${r.depth[0]}–${r.depth[1]}`);

  // ---- Depth and zones at the boat, read from the chart file -----------------------------------------------------------------------
  async function lookUpHere(p) {
    if (!reader || gps.busy) return;
    gps.busy = true;
    let r = null;
    try { r = await reader.at(p); } catch (e) { /* off the chart */ }
    gps.busy = false;
    gps.depth = r && r.known ? r : null;
    const z = r && (r.zones.find((x) => x.zone_class === 'notake') || r.zones.find((x) => x.zone_class === 'restricted'));
    const zone = z ? { notake: z.zone_class === 'notake', name: z.name || '' } : null;
    if (zone && zone.notake && (!gps.zone || gps.zone.name !== zone.name)) buzz();
    gps.zone = zone;
    updateFields(); updateBanner();
  }

  // ---- The alert under the mode button: no fishing, restricted, anchor dragging ----------------------------------------------------
  function updateBanner() {
    const b = $('banner'), p = lastPos(), a = state.anchor;
    let alert = null;
    if (a && p) {
      const m = C.distanceNm(a, p) * 1852;
      if (m > setting('anchor_m')) {
        if (Date.now() - lastAnchorBuzz > 5000) { buzz(); lastAnchorBuzz = Date.now(); }
        alert = [`⚓ ANCHOR DRAGGING\n${Math.round(m)} m from anchor`, 'info'];
      }
    }
    if (!alert && gps.zone && p) alert = gps.zone.notake ? [`⚠ NO FISHING ZONE\n${gps.zone.name}`, 'danger'] : [`Restricted area\n${gps.zone.name}`, 'warn'];
    b.hidden = !alert;
    if (alert) { b.textContent = alert[0]; b.className = alert[1]; }
  }
  $('banner').onclick = () => { const p = lastPos(); if (p) showZones(p, true); };

  // ---- Modes: Chart, Route, Navigate, Fish ------------------------------------------------------------------------------------------
  function setMode(m) {
    if (m === 'nav' && !nav.active) { snack('Plan a route first (Route mode), or hold the map → Navigate here'); return; }
    if (fields.editing) fields.setEditing(false);
    state.mode = m; save();
    movingWaypoint = null;
    if (m === 'route') unfollow();
    if (m === 'nav') followBoat();
    if (m === 'fish') { drift.reset(); followBoat(setting('fish_zoom')); }
    if (m !== 'nav' && m !== 'fish' && map.getBearing() !== 0) map.easeTo({ bearing: 0 });
    updateModeLabel(); updateBag(); fields.arrange(); updateFields();
    setRouteTool(m === 'route');
    drawData(); refreshModePanel(); refreshDisplayPanel();
  }
  $('btn-mode').onclick = (e) => {
    menuGuard = Date.now() + 300;
    popup(e.currentTarget, [...MODES.map(([k, label]) => [label, () => setMode(k)]),
      look().rec_button ? null : [state.recording ? '■  Stop recording trip…' : '●  Record a trip', () => (state.recording ? askStopTrip() : startTrip(null))]]);
  };
  /** "CHART ▾", or "● REC 1:23   CHART ▾" while recording if the Record button is off. */
  function updateModeLabel() {
    const b = $('btn-mode'), label = `${modeLabel(state.mode)} ▾`;
    if (state.recording && !look().rec_button) b.replaceChildren(el('span.recdot', {}, '●'), ` REC ${recTime()}   ${label}`);
    else b.textContent = label;
    b.setAttribute('aria-label', `Mode: ${modeLabel(state.mode)}`);
    updateRecord();
  }

  /** A round action button in the bottom row, with one word under it. */
  const action = (ic, word, label, fn, active = false) => ({ ic, word, label, fn, active });
  function refreshModePanel() {
    const box = $('modepanel');
    let summary = '', acts = [];
    if (state.mode === 'chart' || fields.editing) { box.hidden = true; placeScaleBar(); return; }
    if (state.mode === 'route') {
      summary = routeSummary();
      acts = [action('undo', 'Undo', 'Undo last change', undoLast), action('delete', 'Clear', 'Clear route', async () => { if (await confirmBox(null, 'Clear the route?', 'Clear')) { snapshot(); state.plan.waypoints = []; planChanged(); } }),
        action('folder', 'Routes', 'Saved routes', (e) => showRoutesMenu(e.currentTarget)), action('check', 'Check', 'Check route', checkRoute),
        action('play', 'Go', 'Start navigating', () => startNavigation(state.plan))];
    } else if (state.mode === 'nav') {
      const n = lastNav, t = nav.target;
      summary = t ? `→ ${t.name || `WP ${nav.index + 1}`}  (${nav.index + 1} of ${nav.route.waypoints.length})` +
        (n ? `\n${C.fmtNm(n.dtw)} · ${C.fmtBearing(n.bearing)} · ${C.fmtNm(n.remain)} to go` : lastPos() ? '' : '\nWaiting for a GPS fix') : 'No active route';
      acts = [action('skip_prev', 'Back', 'Previous waypoint', () => { if (nav.previous(lastPos())) navChanged(); }),
        action('stop', 'Stop', 'Stop navigating', async () => { if (await confirmBox(null, 'Stop navigating?', 'Stop')) stopNavigation(); }),
        action('skip_next', 'Next', 'Skip to next waypoint', () => { if (nav.skip()) navChanged(); })];
    } else {
      const d = drift.drift();
      summary = (d ? (d.kn < 0.05 ? 'Not drifting' : `Drift ${d.kn.toFixed(1)} kn → ${C.fmtBearing(d.dir)}  (line shows ${setting('drift_project')} min)`) : lastPos() ? 'Measuring drift…' : 'Waiting for a GPS fix') +
        (state.anchor ? `\nAnchor alarm ON · ${setting('anchor_m')} m` : '');
      acts = [action('star', 'Mark', 'Mark a catch here', () => markCatch()),
        action('anchor', 'Anchor', state.anchor ? 'Turn anchor alarm off' : 'Set anchor alarm', toggleAnchor, !!state.anchor),
        action('refresh', 'Reset', 'Reset drift', () => { drift.reset(); refreshModePanel(); drawNav(); }),
        action('list', 'List', 'My marks', showMarksList)];
    }
    box.hidden = panel.isOpen;
    $('mode-summary').textContent = summary;
    const key = `${state.mode}/${acts.map((a) => a.ic + a.active).join()}`;
    const btns = $('mode-buttons');
    if (btns.dataset.key !== key) {
      btns.dataset.key = key;
      btns.replaceChildren(...acts.map((a) => el('div.cell', {},
        el(`button.circle${a.active ? '.active' : ''}`, { type: 'button', ariaLabel: a.label, title: a.label }, icon(a.ic)),
        el('span.blabel', { ariaHidden: 'true', onclick: (e) => e.currentTarget.previousSibling.click() }, a.word))));
    }
    // Set (not added) each time: the actions change with the state, the buttons only with the mode.
    acts.forEach((a, i) => { btns.children[i].firstChild.onclick = (e) => a.fn(e); });
    placeScaleBar();
  }
  /** A docked panel opened or closed: the mode buttons sit under it and would show through. */
  function panelChanged() { refreshModePanel(); }

  // ---- Navigation ----------------------------------------------------------------------------------------------------------------------
  function startNavigation(r) {
    if (!r.waypoints.length) { snack('Add waypoints first'); return; }
    nav.start({ name: r.name || 'Route', waypoints: r.waypoints.map((w, i) => ({ lat: w.lat, lon: w.lon, name: w.name || `WP${i + 1}` })) }, lastPos());
    lastNav = null;
    if (state.mode !== 'nav') modeAfterNav = state.mode === 'fish' ? 'fish' : 'chart';
    saveNav();
    setMode('nav');
    if (setting('trip_start') > 0.5 && !state.recording) startTrip('Recording this trip');
    if (gps.fix) onFix({ coords: { latitude: gps.fix.lat, longitude: gps.fix.lon, accuracy: gps.fix.acc, speed: null, heading: null } });
  }
  function stopNavigation() { nav.stop(); lastNav = null; saveNav(); setMode(modeAfterNav); }
  function navChanged() { lastNav = null; saveNav(); const p = lastPos(); if (p) { lastNav = nav.update(p, gps.sog, gps.cog, setting('arrival_nm')); } drawNav(); updateFields(); refreshModePanel(); }
  function saveNav() { state.nav = nav.toJSON(); state.modeAfterNav = modeAfterNav; save(); }
  const goTo = (p, name = 'Target') => startNavigation({ name: 'Go to', waypoints: [{ lat: p.lat, lon: p.lon, name: name.slice(0, 24) }] });

  // ---- Anchor alarm -------------------------------------------------------------------------------------------------------------------
  function toggleAnchor() {
    if (state.anchor) state.anchor = null;
    else {
      const p = lastPos();
      if (!p) { snack('Waiting for GPS fix'); startGps(); return; }
      state.anchor = p;
      keepAwake(true);
    }
    save(); drawNav(); refreshModePanel(); updateBanner(); updateFields();
    snack(state.anchor ? `Anchor alarm set: ${setting('anchor_m')} m` : 'Anchor alarm off');
  }

  // ---- Trips: recording the track ---------------------------------------------------------------------------------------------------------
  let statsCache = null;
  const trackStats = () => { if (!statsCache || statsCache.n !== state.track.length) statsCache = { n: state.track.length, s: C.TripStats.of(state.track) }; return statsCache.s; };
  /** When this trip started: the tap on Record, or its first point if that's earlier. */
  function recStart() {
    const now = Date.now(), first = state.track[0] && state.track[0].t;
    if (!state.recSince || state.recSince > now) state.recSince = first || now;
    return Math.min(state.recSince, first || state.recSince);
  }
  function recTime() {
    const sec = Math.max(0, Math.floor((Date.now() - recStart()) / 1000));
    return sec < 3600 ? `${Math.floor(sec / 60)}:${p2(sec % 60)}` : `${Math.floor(sec / 3600)}:${p2(Math.floor(sec / 60) % 60)}:${p2(sec % 60)}`;
  }
  let trackSaveAt = 0;
  function recordTrack(f, sog) {
    if (!state.recording || f.acc > 100) return false;
    if (finishStaleTrip(f.t)) return false;
    const last = state.track[state.track.length - 1];
    if (last && f.t <= last.t && last.t - f.t < 60000) return false;
    if (state.track.length >= C.TRACK_MAX) archiveCurrent(true);
    const added = C.addTrackPoint(state.track, f, f.t, sog, setting('track_spacing'));
    if (added && f.t - trackSaveAt > 30000) { trackSaveAt = f.t; save(); }
    return added;
  }
  /** Points to draw: the chosen history window, joined up to the boat while recording. */
  function visibleTrack() {
    const win = setting('track_window'), from = win < 0 ? 0 : Date.now() - win * 60000;
    const pts = win < 0 ? state.track : state.track.filter((p) => p.t >= from);
    const b = lastPos();
    return state.recording && b ? [...pts, b] : pts;
  }
  function drawTrack() { const pts = visibleTrack(); setData('track', pts.length > 1 ? [line(pts)] : []); }
  /** A trip left recording with nothing for longer than the break in Settings: save it and stop. */
  function finishStaleTrip(now = Date.now()) {
    const gap = setting('trip_split'), last = state.track[state.track.length - 1];
    if (gap < 0 || !last || !state.recording || now - last.t <= gap * 3600000) return false;
    archiveCurrent(true);
    state.recording = false; recordingChanged();
    snack(`Your last trip was saved and recording stopped: nothing was recorded for over ${C.fmtDuration(gap)}.`);
    return true;
  }
  const tripSpans = () => state.trips.map((t) => [t.start, t.end]);
  const currentCatches = () => { const s = trackStats(); return C.catchesIn(state.catches, s.start, s.end, tripSpans()); };
  const catchesOf = (t) => { const s = trackStats(); return C.catchesIn(state.catches, t.start, t.end, [...tripSpans(), [s.start, s.end]]); };
  /** Save the track as finished trips and start afresh. */
  function archiveCurrent(keepAll) {
    const parts = C.archiveTrack(state.track, setting('trip_split') < 0 ? null : setting('trip_split') * 3600000, keepAll, (t) =>
      C.catchesIn(state.catches, t.start, t.end, tripSpans()).length > 0 || state.marks.some((m) => m.time >= t.start && m.time <= t.end));
    for (const { trip, points } of parts) saveTrip(trip, points);
    state.track = []; statsCache = null; save();
    drawTrack(); drawCatches();
    return parts.length;
  }
  function startTrip(message, quiet = false) {
    if (state.recording) return;
    if (!startGps()) return;
    if (state.track.length >= 2) archiveCurrent(true); else state.track = [];
    state.recording = true; state.recSince = Date.now(); save();
    recordingChanged();
    if (quiet) return;
    snack(message || (look().rec_button ? 'Recording a trip. To stop, press and hold the red light.' : 'Recording a trip.') +
      (IOS ? ' Keep the planner open with the screen on: iOS stops the GPS when the phone locks.' : ''),
    ['Undo', () => { state.recording = false; state.track = []; save(); recordingChanged(); }]);
  }
  async function askStopTrip() {
    if (!state.recording) return;
    const s = trackStats();
    if (state.track.length < 2) {
      if (await confirmBox(null, "Stop recording? The boat hasn't moved yet, so there's no track to save. Catches and marks are kept.", 'Stop', 'Keep recording')) {
        state.recording = false; state.track = []; save(); recordingChanged();
      }
      return;
    }
    const n = currentCatches().length;
    const what = [C.fmtDuration((Date.now() - recStart()) / 3600000), C.fmtNm(s.distanceNm), n ? `${n} catch${n === 1 ? '' : 'es'}` : null].filter(Boolean).join(' · ');
    const a = await dialog('Stop recording this trip?', [what, "It's saved in Trips & catches, where you can look back at it or export it."],
      [['Discard…', 'discard', 'neutral'], ['Keep recording', null], ['Stop and save', 'save', 'primary']]);
    if (a === 'save') { archiveCurrent(true); state.recording = false; save(); recordingChanged(); snack('Trip saved'); }
    if (a === 'discard' && await confirmBox(null, "Throw away the track of this trip? This can't be undone. Catches and marks are kept.", 'Discard')) {
      state.recording = false; state.track = []; save(); recordingChanged();
    }
  }
  function recordingChanged() { statsCache = null; updateModeLabel(); drawTrack(); updateFields(); refreshTrips(); }
  /** "● Record" with a grey dot; while recording, a slowly pulsing red light and the time. Press and hold to stop. */
  function updateRecord() {
    const b = $('rec');
    b.hidden = !look().rec_button;
    b.classList.toggle('on', !!state.recording);
    $('rec-text').textContent = state.recording ? recTime() : 'Record';
    b.setAttribute('aria-label', state.recording ? `Recording a trip, ${recTime()}. Press and hold to stop.` : 'Record a trip');
  }
  {
    const b = $('rec');
    let hold = 0, held = false;
    b.addEventListener('pointerdown', () => { if (fields.editing) return; held = false; clearTimeout(hold); hold = setTimeout(() => { held = true; if (navigator.vibrate) navigator.vibrate(20); if (state.recording) askStopTrip(); else startTrip(null); }, 600); });
    for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) b.addEventListener(ev, () => clearTimeout(hold));
    b.addEventListener('contextmenu', (e) => e.preventDefault());
    b.addEventListener('click', () => {
      if (held || fields.editing) return;
      if (!state.recording) { startTrip(null); return; }
      snack(`Recording ${recTime()} · ${C.fmtNm(trackStats().distanceNm)}. To stop, press and hold the red light.`);
    });
  }

  // ---- After a reload: carry on where things were left ------------------------------------------------------------------------------------
  function resumeGps() {
    if (state.gps || state.recording || nav.active || state.anchor) {
      if (isSecureContext && 'geolocation' in navigator) { startGps(); if (state.mode === 'nav' || state.mode === 'fish') gps.follow = true; styleLocate(); }
    }
    if (state.mode === 'nav' && !nav.active) state.mode = 'chart';
    finishStaleTrip();
    updateModeLabel(); drawBoat(); drawNav(); drawTrack(); updateFields(); refreshModePanel();
  }
