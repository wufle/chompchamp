  // ---- Catches, trips, and bag & size limits (CatchDialogs.kt, TripsPanel.kt, BagLimitsPanel.kt) -----------------------------
  const limits = (() => { try { return new C.BagLimits(DATA.bagLimits, DATA.limitAreas); } catch (e) { return null; } })();
  const SPECIES = ['Australian herring', 'Baldchin groper', 'Barramundi', 'Bight redfish', 'Black bream', 'Blowfish', 'Blue morwong',
    'Blue swimmer crab', 'Bluebone', 'Breaksea cod', 'Cobia', 'Coral trout', 'Dhufish', 'Flathead', 'Garfish', 'Giant trevally', 'Golden trevally',
    'Harlequin fish', 'King George whiting', 'King threadfin', 'Mahi mahi', 'Mangrove jack', 'Mulloway', 'Pink snapper', 'Queenfish', 'Rankin cod',
    'Red emperor', 'Samson fish', 'Sand whiting', 'Silver trevally (skippy)', 'Southern bluefin tuna', 'Spangled emperor', 'Spanish mackerel', 'Squid',
    'Swallowtail', 'Tailor', 'WA salmon', 'Wahoo', 'Western rock lobster', 'Yellowfin tuna', 'Yellowtail kingfish'];

  // ---- Saved trips: the list is in the main state, each trip's points under their own key ----------------------------------------
  const TRIP_KEY = 'chompchamp.planner.trip.';
  function saveTrip(trip, points) {
    const i = state.trips.findIndex((t) => t.id === trip.id);
    try { localStorage.setItem(TRIP_KEY + trip.id, C.encodeTrip(points)); } catch (e) { toast("This browser is full, so the trip's track couldn't be kept. Export a backup and delete old trips."); }
    if (i >= 0) state.trips[i] = { ...trip, name: state.trips[i].name }; else state.trips.push(trip);
    state.trips.sort((a, b) => b.start - a.start);
    save();
  }
  const tripPoints = (id) => { try { return C.decodeTrip(localStorage.getItem(TRIP_KEY + id) || ''); } catch (e) { return []; } };
  const tripRaw = (id) => { try { return localStorage.getItem(TRIP_KEY + id) || ''; } catch (e) { return ''; } };
  function deleteTrip(id) {
    state.trips = state.trips.filter((t) => t.id !== id);
    try { localStorage.removeItem(TRIP_KEY + id); } catch (e) { /* gone already */ }
    if (state.shownTrip === id) showTripOnMap(null, [], false);
    save();
  }

  /** A saved trip on the map: a line per stretch, a small dot at the start and a bigger one at the end. */
  let tripEnds = [];
  function showTripOnMap(t, points, zoom) {
    state.shownTrip = t ? t.id : null; save();
    const parts = t ? C.splitTrack(points, C.GAP_MS) : [];
    setData('trip', parts.filter((p) => p.length > 1).map((p) => line(p)));
    tripEnds = parts.length ? [point(parts[0][0], { r: 4, color: '#ffffff' }), point(parts[parts.length - 1][parts[parts.length - 1].length - 1], { r: 5, color: '#1e88e5' })] : [];
    setTripCursor(null);
    drawCatches();
    if (t && zoom && points.length > 1) {
      unfollow();
      const b = new maplibregl.LngLatBounds();
      points.forEach((p) => b.extend([p.lon, p.lat]));
      map.fitBounds(b, { padding: { top: 80, left: 80, right: 80, bottom: Math.round(innerHeight * 0.45) }, maxZoom: 15, duration: 600 });
    }
  }
  const setTripCursor = (p) => setData('trip-dots', [...tripEnds, ...(p ? [point(p, { r: 7, color: '#d81b60' })] : [])]);

  /** Anything recent is always shown, so a new catch is never invisible. A catch with a mark is shown by its mark. */
  function drawCatches() {
    const recent = Date.now() - 12 * 3600000;
    const shown = state.trips.find((t) => t.id === state.shownTrip);
    const list = state.allCatches ? state.catches : [...new Map([...currentCatches(), ...(shown ? catchesOf(shown) : []), ...state.catches.filter((c) => c.time >= recent)].map((c) => [c.id, c])).values()];
    const marked = new Set(state.marks.map((m) => m.id));
    setData('catches', list.filter((c) => c.lat !== undefined && !(c.mark && marked.has(c.mark))).map((c) => point(c, { id: c.id })));
  }

  // ---- Catch entry and viewing ---------------------------------------------------------------------------------------------------
  const markOf = (c) => (c.mark ? state.marks.find((m) => m.id === c.mark) : null);
  const catchesAt = (m) => state.catches.filter((c) => c.mark === m.id).sort((a, b) => b.time - a.time);
  const checkOf = (c, others) => (limits ? C.checkCatch(limits, { species: c.species, count: c.count, lengthCm: c.cm || null, time: c.time, pos: c.lat !== undefined ? c : here().p }, others)
    : { area: null, lines: [], problems: [] });
  /** Limits text for the catch window, with problems in red. */
  function limitsBox(r, released) {
    if (!r.lines.length) return el('div', { hidden: true });
    return el('div.limits', {}, el('div.lh', {}, `BAG & SIZE LIMITS${r.area ? ` · ${C.areaLabel(r.area)}` : ''}`),
      r.lines.map((l) => el('div', {}, l)),
      r.problems.map((x) => el(`div.${released ? 'muted' : 'problem'}`, {}, `⚠ ${x}`)),
      r.problems.length > 0 && released ? el('div', {}, "Released, so that's fine.") : null,
      el('div', {}, "From DPIRD's guide, entered by hand. Always check the current rules."));
  }
  const localInput = (t) => new Date(t - new Date(t).getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  const timeText = (t) => new Date(t).toLocaleString([], { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false });

  /** Mark a catch at the boat (or [at], or add one to [mark]). Without a position it's only logged. */
  async function markCatch(mark = null, at = null) {
    const p = mark ? { lat: mark.lat, lon: mark.lon } : at || lastPos();
    const c = { id: C.uuid(), time: Date.now(), species: '', count: 1, released: false, bait: '', notes: '' };
    if (p) { c.lat = p.lat; c.lon = p.lon; }
    if (mark) { c.mark = mark.id; if (mark.depth) c.depth = mark.depth; }
    else if (p && reader) { try { const r = await reader.at(p); if (r.known && r.depth && !r.land) c.depth = depthOf(r); } catch (e) { /* no depth */ } }
    editCatch(c, true);
  }
  /** New entry, or change [c]. Nothing is stored until Save. */
  async function editCatch(orig, isNew, draft = null) {
    const c = draft || { ...orig };
    const used = (f) => { const n = new Map(); state.catches.map(f).filter(Boolean).forEach((s) => n.set(s, (n.get(s) || 0) + 1)); return [...n.entries()].sort((a, b) => b[1] - a[1]).map((x) => x[0]); };
    const uniq = (a) => { const seen = new Set(); return a.filter((s) => { const k = s.toLowerCase(); if (seen.has(k)) return false; seen.add(k); return true; }); };
    const sp = el('datalist', { id: 'dl-species' }, uniq([...used((x) => x.species), ...SPECIES, ...(limits ? limits.names() : [])]).map((n) => el('option', { value: n })));
    const bl = el('datalist', { id: 'dl-bait' }, used((x) => x.bait).map((n) => el('option', { value: n })));
    const species = el('input.line', { type: 'text', value: c.species, placeholder: 'Species', ariaLabel: 'Species', autocomplete: 'off', autocapitalize: 'sentences' });
    species.setAttribute('list', 'dl-species');
    const num = (v, inputMode) => el('input.line', { type: 'text', inputMode, value: v, autocomplete: 'off' });
    const count = num(String(c.count), 'numeric'), cm = num(c.cm ? C.trimNum(c.cm) : '', 'decimal'), kg = num(c.kg ? C.trimNum(c.kg, 2) : '', 'decimal');
    const bait = el('input.line', { type: 'text', value: c.bait, placeholder: 'Bait or lure', autocomplete: 'off', autocapitalize: 'sentences' });
    bait.setAttribute('list', 'dl-bait');
    const notes = el('textarea.line', { rows: 2, placeholder: 'Notes', autocapitalize: 'sentences' }); notes.value = c.notes;
    const released = toggle(c.released, () => show(), 'Released');
    const when = el('input.when', { type: 'datetime-local', value: localInput(c.time), ariaLabel: 'When' });
    const box = el('div', {});
    const n = (v) => { const x = Number(String(v).trim().replace(',', '.')); return String(v).trim() !== '' && isFinite(x) && x > 0 ? x : undefined; };
    const read = () => {
      c.species = species.value.trim().slice(0, 60);
      c.count = Math.min(999, Math.max(1, Math.round(Number(count.value)) || 1));
      c.cm = n(cm.value); c.kg = n(kg.value);
      c.released = released.checked; c.bait = bait.value.trim().slice(0, 100); c.notes = notes.value.trim().slice(0, 1000);
      const t = new Date(when.value).getTime(); if (isFinite(t)) c.time = t;
      return c;
    };
    const others = state.catches.filter((x) => x.id !== c.id);
    const show = () => box.replaceChildren(limitsBox(checkOf(read(), others), released.checked));
    for (const i of [species, count, cm, when]) { i.addEventListener('input', show); i.addEventListener('change', show); }
    show();
    const cap = (label, input) => el('label.cap', {}, el('span', {}, label), input);
    const where = (c.lat !== undefined ? C.fmtPos(c) : 'No position (no GPS fix)') + (c.depth ? `\nDepth (est.): ${c.depth}${/^[\d>]/.test(c.depth) ? ' m' : ''}` : '');
    const a = await dialog(isNew ? 'Mark' : 'Edit catch', [sp, bl, species, el('div.trio', {}, cap('How many', count), cap('Length (cm)', cm), cap('Weight (kg)', kg)),
      bait, notes, el('label.swrow', {}, el('span', {}, 'Released'), released), box, when, el('p.small', {}, where)],
    [['Cancel', null], ['Save', true, 'primary']], { dismissable: !isNew });
    if (!a) return;
    read();
    // Keeping a fish that's protected, closed, out of size or over the bag: say so first, and offer to release it.
    const problems = c.released ? [] : checkOf(c, others).problems;
    if (problems.length) {
      const how = await dialog('Check before you keep it', [problems.join('\n\n'), "Limits are per person, from DPIRD's guide. Check the current rules."],
        [['Keep anyway', 'keep', 'neutral'], ['Back', 'back'], ['Release it', 'release', 'primary']]);
      if (!how || how === 'back') { editCatch(orig, isNew, c); return; }
      if (how === 'release') c.released = true;
    }
    const oldTitle = C.catchTitle(orig);
    const clean = C.cleanCatch(c);
    if (isNew) {
      if (clean.lat !== undefined && !markOf(clean)) {
        const m = { id: C.uuid(), lat: clean.lat, lon: clean.lon, name: clean.species ? C.catchTitle(clean) : `Mark ${state.marks.length + 1}`, time: clean.time, group: '' };
        if (clean.depth) m.depth = clean.depth;
        const g = C.findGroup(state.groups, ''); if (g) g.visible = true;
        state.marks.push(m); clean.mark = m.id;
      }
      state.catches.push(clean);
    } else {
      const i = state.catches.findIndex((x) => x.id === clean.id);
      if (i >= 0) state.catches[i] = clean;
      // Keep a name the user chose; follow the species otherwise.
      const m = markOf(clean);
      if (m && clean.species && catchesAt(m).length <= 1 && (m.name === oldTitle || /^Mark \d+$/.test(m.name))) m.name = C.catchTitle(clean);
    }
    C.tidyGroups(state.groups, state.marks);
    save(); drawData(); refreshTrips(); refreshMarks();
    if (isNew) {
      buzz(true);
      const started = setting('trip_start') > 0.5 && !state.recording && clean.lat !== undefined && gps.watch !== null;
      if (started) startTrip(null, true);
      snack((clean.mark ? 'Marked ' : 'No position, so logged without a mark: ') + C.catchTitle(clean) + (started ? '. Trip recording started.' : ''));
    }
  }

  async function viewCatch(c) {
    const r = checkOf(c, state.catches.filter((x) => x.id !== c.id));
    const info = [timeText(c.time), C.catchDetails(c), c.bait ? `Bait: ${c.bait}` : '', c.depth ? `Depth (est.): ${c.depth}${/^[\d>]/.test(c.depth) ? ' m' : ''}` : '',
      c.lat !== undefined ? C.fmtPos(c) : '', c.notes, c.photos ? `${c.photos.length} photo${c.photos.length === 1 ? '' : 's'} on the phone` : ''].filter(Boolean).join('\n');
    const m = markOf(c);
    await dialog(C.catchTitle(c), (close) => [el('p.msg.muted', {}, info), limitsBox(r, c.released), actionList(close, [
      ['Edit', () => editCatch(c, false)],
      m ? ['Open its mark', () => goToMark(m)] : null,
      c.lat !== undefined && !m ? ['Show on map', () => { unfollow(); map.easeTo({ center: [c.lon, c.lat], zoom: Math.max(map.getZoom(), 14) }); }] : null,
      ['Delete catch', () => deleteCatch(c), true]])], [['Close', null]]);
  }
  async function deleteCatch(c) {
    const del = () => { state.catches = state.catches.filter((x) => x.id !== c.id); save(); drawData(); refreshTrips(); refreshMarks(); };
    const what = `Delete this catch${c.photos ? ` and its ${c.photos.length} photo(s) on the phone` : ''}? This can't be undone.`;
    const mark = markOf(c);
    if (!mark || catchesAt(mark).some((x) => x.id !== c.id)) { if (await confirmBox(null, what, 'Delete')) del(); return; }
    const a = await dialog(null, what, [['Catch only', 'catch', 'neutral'], ['Cancel', null], ['Delete catch and mark', 'both', 'primary']]);
    if (a) del();
    if (a === 'both') { state.marks = state.marks.filter((x) => x.id !== mark.id); C.tidyGroups(state.groups, state.marks); save(); drawData(); }
  }
  const catchLabel = (c) => el('span.two', {}, el('span', {}, C.catchTitle(c) + (C.catchDetails(c) ? ` · ${C.catchDetails(c)}` : '')), el('span.sub', {}, timeText(c.time)));
  async function catchList(title, list) {
    if (!list.length) { toast('No catches logged yet'); return; }
    const i = await pickList(`${title} (${list.length})`, list.map(catchLabel), [['Export…', 'csv', 'neutral']], 'Close');
    if (i === 'csv') { download(`chompchamp-catches-${stamp()}.csv`, C.catchesCsv(list), 'text/csv'); return; }
    if (typeof i === 'number') {
      const c = list[i];
      if (c.lat !== undefined) { unfollow(); map.easeTo({ center: [c.lon, c.lat] }); }
      viewCatch(c);
    }
  }

  // ---- Trips & catches panel ------------------------------------------------------------------------------------------------------
  const TRIPS = 'Trips & catches';
  let showOlder = false;
  const refreshTrips = () => { if (panel.current === TRIPS) showTrips(); };
  const nCatches = (n) => `${n} catch${n === 1 ? '' : 'es'}`;
  const dur = (ms) => C.fmtDuration(ms / 3600000);
  const clock = (t) => new Date(t).toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false });
  function summary(start, end, duration, dist, moving, avg, max, n) {
    const first = start > 0 ? `${clock(start)} to ${C.fmtClock(end)} · ${dur(duration)}\n` : '';
    const speeds = [avg !== null ? `average ${avg.toFixed(1)} kn under way` : null, max > 0 ? `top ${max.toFixed(1)} kn` : null].filter(Boolean).join(' · ');
    return first + C.fmtNm(dist) + (moving > 0 ? ` · ${dur(moving)} under way` : '') + (speeds ? `\n${speeds}` : '') + (n > 0 ? `\n${nCatches(n)} logged` : '');
  }
  function showTrips() {
    panel.show(TRIPS, (rows) => {
      const s = trackStats();
      if (state.recording) {
        rows.section('Recording this trip');
        const since = recStart(), now = Date.now(), n = currentCatches().length;
        rows.note(state.track.length < 2 ? `Recording since ${C.fmtClock(since)}. Waiting for the boat to move…${n ? `\n${nCatches(n)} logged` : ''}`
          : summary(since, now, now - since, s.distanceNm, s.movingMs, s.avgMovingKn, s.maxKn, n));
        rows.actionRow('Mark a catch', 'Add', () => markCatch());
        if (state.track.length >= 2) rows.actionRow('Speed log and details', 'Open', () => showTripDetails(null));
        rows.actionRow('Stop recording', 'Stop…', askStopTrip);
      } else {
        rows.section('Not recording');
        rows.actionRow('Record a trip', 'Start', () => startTrip(null));
        rows.actionRow('Mark a catch', 'Add', () => markCatch());
      }
      rows.section('Saved trips');
      const shown = state.trips.find((t) => t.id === state.shownTrip);
      if (shown) rows.actionRow(`On the map: ${shown.name}`, 'Hide', () => { showTripOnMap(null, [], false); showTrips(); });
      if (!state.trips.length) rows.note('No saved trips yet.');
      const list = showOlder ? state.trips : state.trips.slice(0, 30);
      for (const t of list) {
        const n = catchesOf(t).length;
        const line2 = [t.start > 0 ? `${C.fmtClock(t.start)}–${C.fmtClock(t.end)}` : null, C.tripDuration(t) > 0 ? dur(C.tripDuration(t)) : null, n ? nCatches(n) : null].filter(Boolean).join(' · ');
        rows.actionRow(line2 ? `${t.name}\n${line2}` : t.name, C.fmtNm(t.nm), () => showTripDetails(t));
      }
      if (list.length < state.trips.length) rows.actionRow('Older trips', String(state.trips.length - list.length), () => { showOlder = true; showTrips(); });
      rows.section('Catch log');
      rows.actionRow('All catches', String(state.catches.length), () => catchList('All catches', [...state.catches].sort((a, b) => b.time - a.time)));
      rows.actionRow('Catches on the map', state.allCatches ? 'All' : 'This trip', () => { state.allCatches = !state.allCatches; save(); drawCatches(); showTrips(); });
      if (state.trips.length) {
        rows.section('Totals');
        const fish = state.catches.reduce((a, c) => a + c.count, 0), rel = state.catches.filter((c) => c.released).reduce((a, c) => a + c.count, 0);
        rows.note(`${state.trips.length} trips · ${C.fmtNm(state.trips.reduce((a, t) => a + t.nm, 0))} · ${Math.floor(state.trips.reduce((a, t) => a + C.tripDuration(t), 0) / 3600000)} h · ` +
          `top speed ${Math.max(...state.trips.map((t) => t.maxKn)).toFixed(1)} kn\n${nCatches(fish)} (fish), ${rel} released`);
      }
      rows.note('Recording starts when you tap Record, start a route, or mark a catch (Settings: Start recording a trip). While it records, a red light at the ' +
        'top left of the map slowly flashes, with the time; press and hold it to stop. A trip left with no GPS for longer than the break in Settings is saved and stopped by itself. ' +
        (IOS ? 'iOS stops the GPS when the phone locks or you switch apps, so keep the planner open with the screen on.' : 'The GPS only works while this page is open.'));
    });
  }
  /** [t] null = the trip in progress. */
  function showTripDetails(t) {
    const points = t ? tripPoints(t.id) : state.track.slice(), fish = t ? catchesOf(t) : currentCatches();
    if (t) showTripOnMap(t, points, true);
    panel.show(t ? t.name : 'This trip', (rows) => {
      if (!t) { const s = C.TripStats.of(points); rows.note(summary(s.start, s.end, s.durationMs, s.distanceNm, s.movingMs, s.avgMovingKn, s.maxKn, fish.length)); }
      else {
        rows.note(summary(t.start, t.end, C.tripDuration(t), t.nm, t.movingMs, C.tripAvg(t), t.maxKn, fish.length));
        if (!layerState('saved_trip', true).on) rows.note("Saved trip is switched off in Layers, so it isn't showing on the map.");
      }
      const chart = speedChart(points, (i) => {
        const p = points[i], kn = C.trackSpeeds(points)[i];
        reading.textContent = [new Date(p.t).toLocaleTimeString([], { weekday: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }),
          kn !== null ? `${kn.toFixed(1)} kn` : null, C.fmtPos(p).replace('  ', ' ')].filter(Boolean).join('  ');
        setTripCursor(p);
      });
      const reading = el('p.reading', {}, 'Drag along the chart to see when, where and how fast');
      if (chart) { rows.view(reading); rows.view(chart); } else rows.note("No times were recorded, so there's no speed log.");
      if (fish.length) rows.actionRow('Catches', String(fish.length), () => catchList(t ? t.name : 'This trip', fish));
      rows.actionRow('Export track (GPX) or speed log (CSV)', 'Export', () => exportTrip(t || C.tripOf(points), points, fish));
      if (t) {
        const on = state.shownTrip === t.id;
        rows.actionRow('On the map', on ? 'Hide' : 'Show', () => { if (on) { showTripOnMap(null, [], false); showTrips(); } else showTripDetails(t); });
        rows.actionRow('Name', 'Rename', async () => { const n = await askText('Trip name', t.name); if (n) { t.name = n.slice(0, 100); save(); showTripDetails(t); } });
        rows.actionRow('Delete this trip', 'Delete', async () => {
          if (await confirmBox(null, `Delete "${t.name}" and its track? This can't be undone. Catches are kept.`, 'Delete')) { deleteTrip(t.id); showTrips(); }
        });
      }
      rows.actionRow('All trips', 'Back', showTrips);
    }, { onClose: () => setTripCursor(null) });
  }
  async function exportTrip(t, points, fish) {
    const base = `chompchamp-${t.start > 0 ? stampOf(t.start) : 'trip'}`;
    const items = [['Track and catches (.gpx)', () => download(`${base}.gpx`, C.tripGpx(t, points, fish), 'application/gpx+xml')],
      ['Speed log (.csv)', () => download(`${base}-speed.csv`, C.speedCsv(points), 'text/csv')],
      ...(fish.length ? [['Catches (.csv)', () => download(`${base}-catches.csv`, C.catchesCsv(fish), 'text/csv')]] : [])];
    const i = await pickList('Export', items.map((x) => x[0]));
    if (i !== null) items[i][1]();
  }
  /** Speed against time (SpeedChartView). Drag along it to read off a moment. Null if there are no times. */
  function speedChart(points, onPick) {
    const speeds = C.trackSpeeds(points), order = points.map((p, i) => i).filter((i) => points[i].t > 0).sort((a, b) => points[a].t - points[b].t);
    if (order.length < 2) return null;
    const t0 = points[order[0]].t, t1 = points[order[order.length - 1]].t;
    if (t1 <= t0) return null;
    const top = Math.max(5, Math.ceil(Math.max(0, ...speeds.filter((s) => s !== null)) / 5) * 5);
    const cv = el('canvas.chart', { height: 130 });
    let cursor = -1;
    const draw = () => {
      const dpr = devicePixelRatio || 1, W = cv.clientWidth || 300, H = 130;
      cv.width = W * dpr; cv.height = H * dpr;
      const c = cv.getContext('2d'); c.scale(dpr, dpr);
      const left = 24, right = W - 6, bottom = H - 16, ceil = 8;
      const x = (t) => left + ((right - left) * (t - t0)) / (t1 - t0), y = (kn) => bottom - (bottom - ceil) * Math.max(0, Math.min(1, kn / top));
      c.font = '10px system-ui, sans-serif'; c.fillStyle = '#9FB3C8'; c.strokeStyle = '#9FB3C83c'; c.textAlign = 'right';
      for (const kn of [0, top / 2, top]) { c.beginPath(); c.moveTo(left, y(kn)); c.lineTo(right, y(kn)); c.stroke(); c.fillText(C.trimNum(kn), left - 4, y(kn) + 3.5); }
      c.textAlign = 'left'; c.fillText('kn', left + 4, y(top) + 13);
      c.fillText(C.fmtClock(t0), left, H - 3); c.textAlign = 'right'; c.fillText(C.fmtClock(t1), right, H - 3);
      c.textAlign = 'center'; c.fillText(C.fmtClock((t0 + t1) / 2), (left + right) / 2, H - 3);
      c.beginPath();
      let pen = false, lastT = 0;
      for (const i of order) {
        const kn = speeds[i], t = points[i].t;
        if (kn === null) { pen = false; continue; }
        if (!pen || t - lastT > C.GAP_MS) c.moveTo(x(t), y(kn)); else c.lineTo(x(t), y(kn));
        pen = true; lastT = t;
      }
      c.strokeStyle = '#8EC1E6'; c.lineWidth = 1.6; c.lineJoin = 'round'; c.stroke();
      if (cursor >= 0) {
        const p = points[cursor];
        c.strokeStyle = '#ffffff'; c.lineWidth = 1.2; c.beginPath(); c.moveTo(x(p.t), ceil); c.lineTo(x(p.t), bottom); c.stroke();
        if (speeds[cursor] !== null) { c.beginPath(); c.arc(x(p.t), y(speeds[cursor]), 4, 0, 7); c.stroke(); }
      }
    };
    const pick = (e) => {
      const b = cv.getBoundingClientRect(), t = t0 + Math.max(0, Math.min(1, (e.clientX - b.left - 24) / (b.width - 30))) * (t1 - t0);
      const i = order.reduce((a, j) => (Math.abs(points[j].t - t) < Math.abs(points[a].t - t) ? j : a), order[0]);
      if (i === cursor) return;
      cursor = i; draw(); onPick(i);
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

  // ---- Bag & size limits panel ----------------------------------------------------------------------------------------------------
  const BAG = 'Bag & size limits';
  const bag = { chosen: null, query: '', shown: undefined };
  const bagArea = () => bag.chosen || (limits ? limits.areaAt(lastPos() || mapCentre()) : null);
  function openBag(at = null) { bag.query = ''; bag.at = at; showBag(); }
  function toggleBag() { if (panel.current === BAG) panel.hide(); else openBag(); }
  /** The boat moved: follow it into the next area. */
  function bagMoved() { if (panel.current === BAG && !bag.chosen && !bag.at && bagArea() !== bag.shown) showBag(); }
  $('btn-bag').onclick = () => { if (!fields.editing) toggleBag(); };
  function showBag() {
    if (!limits) { dialog(BAG, "The bag limits couldn't be read."); return; }
    const pos = bag.at || lastPos() || mapCentre();
    const area = bag.chosen || limits.areaAt(pos);
    bag.shown = area;
    const search = el('input.search', { type: 'search', placeholder: 'Search species', value: bag.query, ariaLabel: 'Search species', autocomplete: 'off', enterKeyHint: 'search' });
    search.addEventListener('keydown', (e) => { if (e.key === 'Enter') search.blur(); });
    const list = el('div.baglist', {});
    search.addEventListener('input', () => { bag.query = search.value; fill(list, area, pos); });
    panel.show(BAG, (rows) => {
      const how = bag.chosen ? 'chosen' : bag.at ? 'where you held' : lastPos() ? 'your position' : 'map position';
      rows.actionRow(`Area${area ? ` · ${how}` : ''}`, area ? C.areaLabel(area) : 'Choose', pickArea);
      rows.view(list);
      fill(list, area, pos);
    }, { header: el('div.phdr', {}, search), onClose: () => { bag.at = null; } });
  }
  async function pickArea() {
    const i = await pickList('Area', ['Follow my position', ...C.LIMIT_AREAS.map((a) => a[1])]);
    if (i === null) return;
    bag.chosen = i === 0 ? null : C.LIMIT_AREAS[i - 1][0]; bag.at = null;
    showBag();
  }
  function fill(box, area, pos) {
    if (!area) { box.replaceChildren(el('p.note', {}, 'Outside WA waters. Choose an area to see its limits.')); return; }
    const groups = limits.find(area, bag.query), out = [], day = C.dayOf(Date.now()), here = bag.chosen ? null : pos;
    if (!groups.length) out.push(el('p.note', {}, `No match for "${bag.query.trim()}" in ${C.areaLabel(area)}. Finfish not listed come under Other finfish.`));
    for (const g of groups) {
      out.push(el('h3.sec', {}, g.name.toUpperCase() + (g.total ? ` · ${g.total} IN TOTAL` : '')));
      if (g.boatClosed) out.push(el('p.dangertext', {}, g.boatClosed));
      if (g.note) out.push(el('p.note', {}, g.note));
      for (const e of g.entries) {
        const closed = e.closures.filter((c) => C.closureOn(c, day));
        const warn = [...closed.map((c) => `${c.area && here && limits.inside(c.area, here) ? 'Closed here now: ' : 'Closed now: '}${c.where}, ${c.dates}.${c.note ? ` ${c.note}` : ''}`),
          ...e.closures.filter((c) => !C.closureOn(c, day)).map((c) => `Closed ${c.dates}: ${c.where}.`)];
        const shut = g.boatClosed && !e.boatOpen && !e.isProtected;
        const pic = DATA.species[e.name];
        const img = pic ? el('button.pic', { type: 'button', ariaLabel: `Picture of ${e.name}`, onclick: () => showPicture(e, pic) }, el('img', { src: `data:image/webp;base64,${pic.img}`, alt: '' }))
          : el('a.pic.none', { href: picturesUrl(e, null), target: '_blank', rel: 'noopener noreferrer', ariaLabel: `Search the web for pictures of ${e.name}` }, icon('search'));
        out.push(el('div.bagrow', {}, img, el('div.bagtext', {},
          el('div.bagtop', {}, el('span', {}, e.name), el(`span.bagv${e.isProtected ? '.dangertext' : ''}`, {}, e.isProtected ? 'Protected' : !e.bag ? '' : shut ? `Shore only: ${e.bag}` : `Bag ${e.bag}`)),
          el('div.small', {}, (e.isProtected ? 'May not be taken' : `Size: ${e.size || 'no minimum'}`) + (e.boat ? ` · Boat limit ${e.boat}` : '')),
          g.boatClosed && e.boatOpen ? el('div.small', {}, 'Open from boats') : null,
          warn.map((w) => el('div.small.dangertext', {}, w)), e.note ? el('div.small', {}, e.note) : null)));
      }
    }
    out.push(el('p.note', {}, `${limits.source}. Own wording, shortened: closures, marine parks and gear rules aren't all listed.${limits.reviewBy && new Date().toISOString().slice(0, 10) > limits.reviewBy ? ' This copy may be out of date.' : ''}`),
      el('a.link', { href: limits.url, target: '_blank', rel: 'noopener noreferrer' }, 'Check the current rules at DPIRD'));
    box.replaceChildren(...out);
  }
  /** A Google image search for the fish (needs internet). The scientific name pins it down, unless ours is an example species. */
  const picturesUrl = (e, p) => `https://www.google.com/search?tbm=isch&q=${encodeURIComponent([e.name, p && !p.example ? p.species : '', 'fish Western Australia'].filter(Boolean).join(' '))}`;
  async function showPicture(e, p) {
    const a = await dialog(e.name, [el('img.bigpic', { src: `data:image/webp;base64,${p.img}`, alt: `Picture of ${e.name}` }),
      el('p.msg', {}, el('i', {}, `${p.example ? 'Example: ' : ''}${p.species}`)),
      el('p.small', {}, "Colour and markings vary. If you can't be sure what it is, let it go."),
      el('a.link', { href: p.page, target: '_blank', rel: 'noopener noreferrer' }, `${[p.by, p.licence].filter(Boolean).join(', ')}, resized`)],
    [['Google Images', 'g', 'neutral'], ['Close', null, 'primary']]);
    if (a === 'g') open(picturesUrl(e, p), '_blank', 'noopener');
  }
