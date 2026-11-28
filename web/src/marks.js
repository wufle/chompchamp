  // ---- Marks and their groups (MarksPanel.kt, the mark menu), and Search places (PlacesPanel.kt) ---------------------------------
  const MARKS = 'Marks';
  const groupLabel = (g) => g.name || 'Ungrouped';
  const marksIn = (name) => state.marks.filter((m) => m.group === name);
  const nMarks = (n) => `${n} mark${n === 1 ? '' : 's'}`;
  const visibleMarks = () => { const shown = new Set(state.groups.filter((g) => g.visible).map((g) => g.name)); return state.marks.filter((m) => shown.has(m.group)); };
  /** Nearest first with a GPS fix, otherwise newest first. */
  const sortedMarks = (list) => { const b = lastPos(); return [...list].sort(b ? (x, y) => C.distanceNm(b, x) - C.distanceNm(b, y) : (x, y) => y.time - x.time); };
  const depthText = (d) => (/^[\d>]/.test(d) ? `${d} m` : d);
  const markLabel = (m) => { const b = lastPos(); return m.name + (b ? ` · ${C.fmtNm(C.distanceNm(b, m))}` : '') + (m.depth ? ` · ${depthText(m.depth)}` : ''); };
  const dotLabel = (g, suffix = '') => el('span', {}, el('span', { style: `color:${g.color}` }, '●'), `  ${groupLabel(g)}${suffix}`);
  function marksChanged() { C.tidyGroups(state.groups, state.marks); save(); drawData(); refreshMarks(); }
  const refreshMarks = () => { if (panel.current === MARKS) showMarks(); };

  async function addMark(p, depth = undefined) {
    if (depth === undefined && reader) { try { const r = await reader.at(p); if (r.known && !r.land && r.depth) depth = depthOf(r); } catch (e) { /* no depth */ } }
    const m = { id: C.uuid(), lat: p.lat, lon: p.lon, name: `Mark ${state.marks.length + 1}`, time: Date.now(), group: '' };
    if (depth && depth !== '--' && depth !== 'land') m.depth = depth;
    const g = C.findGroup(state.groups, ''); if (g) g.visible = true;   // a new mark must never be invisible
    state.marks.push(m); marksChanged();
    snack(`Added ${m.name}`, ['Rename', async () => { const n = await askText('Rename mark', m.name); if (n) { m.name = n; marksChanged(); } }]);
  }
  function deleteMarks(list) {
    const ids = new Set(list.map((m) => m.id));
    state.marks = state.marks.filter((m) => !ids.has(m.id));
    marksChanged();
  }

  function showMarks() {
    panel.show(MARKS, (rows) => {
      if (!layerState('marks', true).on) rows.note('Marks are switched off in Layers, so none are showing.');
      rows.section('Groups shown on the map');
      for (const g of state.groups) rows.groupRow(groupLabel(g), marksIn(g.name).length, g.color, g.visible, (on) => { g.visible = on; save(); drawData(); }, () => showGroup(g.name));
      const hidden = state.groups.some((g) => !g.visible);
      rows.actionRow('All groups', hidden ? 'Show all' : 'Hide all', () => { state.groups.forEach((g) => { g.visible = hidden; }); marksChanged(); });
      rows.actionRow('New group', 'Add', async () => { const n = C.groupName(await askText('New group', '') || ''); if (n) { C.ensureGroup(state.groups, n); marksChanged(); } });
      rows.actionRow('All marks', 'List', showMarksList);
      rows.note('Tap a group to see, move or delete its marks.');
    });
  }
  async function showMarksList() {
    if (!state.marks.length) { toast('No marks yet'); return; }
    const list = sortedMarks(state.marks);
    const i = await pickList('Marks', list.map(markLabel), [['Groups…', 'g', 'neutral']]);
    if (i === 'g') showMarks(); else if (typeof i === 'number') goToMark(list[i]);
  }
  /** Choose a group or make a new one. [keep] adds a first option that resolves to undefined. -> name, or null if cancelled. */
  async function pickGroup(title, suggestion = '', keep = null) {
    const opts = [...(keep ? [keep] : []), 'New group…', ...state.groups.map((g) => dotLabel(g))];
    const i = await pickList(title, opts);
    if (i === null) return null;
    if (keep && i === 0) return undefined;
    if (i === (keep ? 1 : 0)) { const n = C.groupName(await askText('New group', suggestion) || ''); return n ? C.ensureGroup(state.groups, n) : null; }
    return state.groups[i - (keep ? 2 : 1)].name;
  }
  function moveMarks(list, target) {
    list.forEach((m) => { m.group = target; });
    const g = C.findGroup(state.groups, target);
    snack(g && !g.visible ? `Moved ${nMarks(list.length)}. That group is hidden on the map.` : `Moved ${nMarks(list.length)} to ${g ? groupLabel(g) : 'Ungrouped'}`);
    marksChanged();
  }
  async function showGroup(name) {
    const g = C.findGroup(state.groups, name); if (!g) return;
    const list = sortedMarks(marksIn(name));
    const a = list.length
      ? await pickList(`${groupLabel(g)} · ${nMarks(list.length)}`, list.map(markLabel), [['Edit group…', 'edit', 'neutral'], ['Select…', 'select']], 'Close')
      : await dialog(`${groupLabel(g)} · ${nMarks(0)}`, "No marks in this group yet. Move some here from another group, or from a mark's own menu.", [['Edit group…', 'edit', 'neutral'], ['Close', null, 'primary']]);
    if (a === 'edit') editGroup(name);
    else if (a === 'select') selectMarks(name);
    else if (typeof a === 'number') goToMark(list[a]);
  }
  /** Tick marks, then move or delete them together. */
  async function selectMarks(name) {
    const list = sortedMarks(marksIn(name));
    const boxes = list.map(() => el('input', { type: 'checkbox' }));
    const a = await dialog('Select marks', [el('div.items', {}, el('button.item.allbtn', { type: 'button', onclick: () => { const on = boxes.some((b) => !b.checked); boxes.forEach((b) => { b.checked = on; }); } }, 'All'),
      list.map((m, i) => el('label.item.check', {}, boxes[i], el('span', {}, markLabel(m)))))], [['Delete…', 'del', 'neutral'], ['Cancel', null], ['Move…', 'move', 'primary']]);
    const chosen = list.filter((m, i) => boxes[i].checked);
    if (!a) return;
    if (!chosen.length) { toast('No marks selected'); return; }
    if (a === 'move') { const t = await pickGroup(`Move ${nMarks(chosen.length)} to`); if (t !== null && t !== undefined) moveMarks(chosen, t); }
    if (a === 'del' && await confirmBox(null, `Delete ${nMarks(chosen.length)}? This can't be undone.`, 'Delete')) deleteMarks(chosen);
  }
  async function editGroup(name) {
    const g = C.findGroup(state.groups, name); if (!g) return;
    const list = marksIn(name), items = [];
    if (name) items.push(['Rename', async () => {
      const n = C.groupName(await askText('Rename group', g.name) || '');
      const clash = C.findGroup(state.groups, n);
      if (!n || n === g.name) return;
      if (clash && clash !== g) { toast(`There's already a group called ${groupLabel(clash)}`); return; }
      list.forEach((m) => { m.group = n; }); g.name = n; marksChanged();
    }]);
    items.push(['Colour', async () => {
      const i = await pickList(`Colour of ${groupLabel(g)}`, C.GROUP_COLORS.map(([label, hex]) => el('span', {}, el('span', { style: `color:${hex}` }, '●'), `  ${label}`)));
      if (i !== null) { g.color = C.GROUP_COLORS[i][1]; marksChanged(); }
    }]);
    if (list.length) items.push(['Move all marks to…', async () => { const t = await pickGroup(`Move ${nMarks(list.length)} to`); if (t !== null && t !== undefined) moveMarks(list, t); }]);
    if (name) items.push(['Delete group, keep its marks', async () => {
      if (await confirmBox(null, `Delete the group "${groupLabel(g)}"? Its ${nMarks(list.length)} will become ungrouped.`, 'Delete')) {
        list.forEach((m) => { m.group = ''; }); state.groups = state.groups.filter((x) => x !== g); marksChanged();
      }
    }]);
    if (list.length) items.push([name ? 'Delete group and its marks' : 'Delete all ungrouped marks', async () => {
      if (await confirmBox(null, `Delete ${nMarks(list.length)} in "${groupLabel(g)}"? This can't be undone.`, 'Delete')) {
        if (name) state.groups = state.groups.filter((x) => x !== g);
        deleteMarks(list);
      }
    }]);
    const i = await pickList(groupLabel(g), items.map((x) => x[0]));
    if (i !== null) items[i][1]();
  }

  function goToMark(m) {
    unfollow();
    map.easeTo({ center: [m.lon, m.lat] });
    markMenu(m);
  }
  /** Tapping a mark: what it is, its catches, and what you can do with it. */
  function markMenu(m) {
    const b = lastPos(), g = C.findGroup(state.groups, m.group);
    const info = `${timeText(m.time)}\n${C.fmtPos(m)}` + (m.depth ? `\nDepth (est.): ${depthText(m.depth)}` : '') + (b ? `\n${C.fromBoat(b, m)}` : '') +
      `\nGroup: ${g ? groupLabel(g) : 'Ungrouped'}${g && !g.visible ? ' (hidden on the map)' : ''}` + (m.photos ? `\n${m.photos.length} photo${m.photos.length === 1 ? '' : 's'} on the phone` : '');
    const caught = catchesAt(m), SHOWN = 5;
    dialog(m.name, (close) => [el('p.msg.muted', {}, info), actionList(close, [
      ...(caught.length <= SHOWN ? caught : caught.slice(0, SHOWN - 1)).map((c) => [`🐟  ${C.catchTitle(c)} · ${C.catchDetails(c)}`, () => viewCatch(c)]),
      caught.length > SHOWN ? [`All ${caught.length} catches here`, () => catchList(m.name, caught)] : null,
      ['Add a catch here', () => markCatch(m)],
      ['Navigate to', () => startNavigation({ name: m.name, waypoints: [{ lat: m.lat, lon: m.lon, name: m.name }] })],
      ['Add to the route', () => { snapshot(); state.plan.waypoints.push({ lat: m.lat, lon: m.lon, name: m.name }); planChanged(); toast('Added to the route'); }],
      ['Move to group…', async () => { const t = await pickGroup(`Move ${m.name} to`); if (t !== null && t !== undefined) moveMarks([m], t); }],
      ['Rename', async () => { const n = await askText('Rename mark', m.name); if (n) { m.name = n; marksChanged(); } }],
      ['Delete mark', async () => {
        const what = `Delete ${m.name}${m.photos ? ` and its ${m.photos.length} photo(s) on the phone` : ''}?`;
        if (!caught.length) { if (await confirmBox(null, what, 'Delete')) deleteMarks([m]); return; }
        const a = await dialog(null, `${what}\n\nIt has ${caught.length === 1 ? 'a catch' : `${caught.length} catches`} in the catch log.`,
          [['Mark only', 'mark', 'neutral'], ['Cancel', null], [caught.length === 1 ? 'Delete mark and catch' : 'Delete mark and catches', 'all', 'primary']]);
        if (a === 'all') { const ids = new Set(caught.map((c) => c.id)); state.catches = state.catches.filter((c) => !ids.has(c.id)); refreshTrips(); }
        if (a) deleteMarks([m]);
      }, true]])], [['Close', null]]);
  }

  // ---- Search places -----------------------------------------------------------------------------------------------------------------
  const PLACES = 'Search places';
  let placeQuery = '', placeHitTimer = 0;
  function openPlaces() {
    placeQuery = '';
    const search = el('input.search', { type: 'search', placeholder: 'Town, harbour, boat ramp, island, bay…', ariaLabel: 'Search places', autocomplete: 'off', enterKeyHint: 'search' });
    const list = el('div', {});
    const results = () => C.searchPlaces(DATA.places.features, placeQuery, lastPos() || mapCentre(), 60);
    const fillPlaces = () => {
      if (!placeQuery.trim()) { list.replaceChildren(el('p.note', {}, 'Type part of a name. Searching "ramp" with a town, like "ramp mandurah", finds its boat ramps.')); return; }
      const hits = results(), from = lastPos() || mapCentre();
      list.replaceChildren(...(hits.length ? hits.map((f) => {
        const p = f.properties, [lon, lat] = f.geometry.coordinates;
        const sub = [C.placeKind(p.kind), p.near && !p.name.includes(p.near) ? `near ${p.near}` : null, `${C.fmtNm(C.distanceNm(from, { lat, lon }))} ${lastPos() ? 'from the boat' : 'from map centre'}`].filter(Boolean).join(' · ');
        return el('button.place', { type: 'button', onclick: () => goPlace(f) }, el('span', {}, p.name), el('span.small', {}, sub), p.detail ? el('span.small', {}, p.detail) : '');
      }) : [el('p.note', {}, `Nothing called "${placeQuery.trim()}". Try fewer letters.`)]));
    };
    search.addEventListener('input', () => { placeQuery = search.value; fillPlaces(); });
    search.addEventListener('keydown', (e) => { if (e.key === 'Enter') { const h = results()[0]; if (h) goPlace(h); search.blur(); } });
    panel.show(PLACES, (rows) => { rows.view(list); fillPlaces(); }, { header: el('div.phdr', {}, search) });
    if (!TOUCH || IOS) setTimeout(() => search.focus(), 50);
  }
  function goPlace(f) {
    const [lon, lat] = f.geometry.coordinates, p = f.properties;
    unfollow();
    map.easeTo({ center: [lon, lat], zoom: Math.min(15, Math.max(map.getZoom(), C.placeZoom(p.rank) + 1)), duration: 1200 });
    // Ring the place for a while, as its label may give way to a nearby one.
    setData('place-hit', [point({ lat, lon }, { name: p.name })]);
    clearTimeout(placeHitTimer); placeHitTimer = setTimeout(() => setData('place-hit', []), 20000);
    if (!layerState('places', true).on) toast(`${p.name}. Place names are off in Layers.`);
    if (PHONE) panel.hide();
  }
