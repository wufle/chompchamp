  // ---- Catch log and bag & size limits ------------------------------------------------------------------------------
  const limits = (() => { try { return new C.BagLimits(DATA.bagLimits, DATA.limitAreas); } catch (e) { return null; } })();
  let limitsAt = null;        // a position picked from the map's menu, or null for the boat / map centre
  let limitArea = '';         // chosen by hand in the Limits tab, until the page is reloaded ('' = where you are)
  let limitQuery = '';
  const COMMON = ['Australian herring', 'Baldchin groper', 'Blue swimmer crab', 'Dhufish', 'Flathead', 'Garfish', 'King George whiting',
    'Pink snapper', 'Samson fish', 'Silver trevally', 'Squid', 'Tailor', 'WA salmon', 'Western rock lobster', 'Yellowtail kingfish'];

  /** Limits text for the catch window, with problems in red. */
  function limitsBox(r, released) {
    if (!r.lines.length) return el('div', { hidden: true });
    return el('div.limits', {}, el('h3', {}, `Bag & size limits${r.area ? ` · ${C.areaLabel(r.area)}` : ''}`),
      r.lines.map((l) => el('div', {}, l)),
      r.problems.map((x) => el(`div.${released ? 'muted' : 'problem'}`, {}, `⚠ ${x}`)),
      r.problems.length > 0 && released ? el('div.muted', {}, 'Released, so that\'s fine.') : null,
      el('div.muted', {}, 'From DPIRD\'s guide, entered by hand. Always check the current rules.'));
  }
  const checkOf = (c, others) => (limits ? C.checkCatch(limits, { species: c.species, count: c.count, lengthCm: c.cm || null, time: c.time, pos: c.lat !== undefined ? c : here().p }, others)
    : { area: null, lines: [], problems: [] });

  const localInput = (t) => { const d = new Date(t - new Date(t).getTimezoneOffset() * 60000); return d.toISOString().slice(0, 16); };

  /** New catch at [p] ('boat', 'centre' or 'here' says where that is), or edit [old]. */
  async function logCatch(p, at, old = null, draft = null) {
    const c = draft || (old ? { ...old } : { id: C.uuid(), time: Date.now(), species: '', count: 1, released: false, bait: '', notes: '', ...p });
    if (!old && !draft && reader) { try { const r = await reader.at(p); if (r.depth && !r.land) c.depth = C.depthText(r).replace(' m', ''); } catch (e) { /* no depth */ } }
    const used = [...new Set(state.catches.map((x) => x.species).filter(Boolean))];
    const list = el('datalist', { id: 'species-list' }, [...new Set([...used, ...COMMON, ...(limits ? limits.names() : [])])].map((n) => el('option', { value: n })));
    const field = (label, input) => el('label.field', {}, el('span.muted', {}, label), input);
    const species = el('input', { type: 'text', value: c.species, placeholder: 'Species', ariaLabel: 'Species', autocomplete: 'off' });
    species.setAttribute('list', 'species-list');
    const count = el('input', { type: 'number', min: 1, max: 999, value: c.count, inputMode: 'numeric' });
    const cm = el('input', { type: 'number', min: 0, step: 'any', value: c.cm ?? '', inputMode: 'decimal' });
    const kg = el('input', { type: 'number', min: 0, step: 'any', value: c.kg ?? '', inputMode: 'decimal' });
    const released = el('input', { type: 'checkbox', checked: c.released });
    const bait = el('input', { type: 'text', value: c.bait, placeholder: 'Bait or lure' });
    const notes = el('textarea', { rows: 2, placeholder: 'Notes' }); notes.value = c.notes;
    const when = el('input', { type: 'datetime-local', value: localInput(c.time) });
    const withMark = el('input', { type: 'checkbox', checked: !old && disp('catchMark', true) });
    const box = el('div', {});
    const read = () => {
      c.species = species.value.trim().slice(0, 60);
      c.count = Math.min(999, Math.max(1, Math.round(Number(count.value)) || 1));
      const n = (v) => { const x = Number(String(v).replace(',', '.')); return v !== '' && isFinite(x) && x > 0 ? x : undefined; };
      c.cm = n(cm.value); c.kg = n(kg.value);
      c.released = released.checked; c.bait = bait.value.trim().slice(0, 100); c.notes = notes.value.trim().slice(0, 1000);
      const t = new Date(when.value).getTime(); if (isFinite(t)) c.time = t;
      return c;
    };
    const others = state.catches.filter((x) => x.id !== c.id);
    const show = () => box.replaceChildren(limitsBox(checkOf(read(), others), released.checked));
    for (const i of [species, count, cm, released, when]) { i.addEventListener('input', show); i.addEventListener('change', show); }
    show();
    const where = c.lat === undefined ? 'No position' : `${{ boat: 'At your boat', centre: 'At the middle of the map', here: 'Where you held' }[at] || 'At'} ${C.fmtPos(c)}${c.depth ? ` · depth (est.) ${c.depth} m` : ''}`;
    const body = [list, species, el('div.trio', {}, field('How many', count), field('Length (cm)', cm), field('Weight (kg)', kg)),
      el('label.row', {}, released, el('span', {}, 'Released')), bait, notes, field('When', when),
      old ? null : el('label.row', {}, withMark, el('span', {}, 'Also save a mark here')), el('p.muted', {}, where), box];
    const a = await dialog(old ? 'Edit catch' : 'Log a catch', body, [['Cancel', null], ['Save', true, 'primary']]);
    if (!a) return;
    read();
    const problems = c.released ? [] : checkOf(c, others).problems;
    if (problems.length) {
      const how = await dialog('Check before you keep it', [...problems, 'Limits are per person, from DPIRD\'s guide. Check the current rules.'],
        [['Back', 'back'], ['Keep anyway', 'keep'], ['Release it', 'release', 'primary']]);
      if (!how || how === 'back') { logCatch(p, at, old, c); return; }
      if (how === 'release') c.released = true;
    }
    const clean = C.cleanCatch(c);
    if (!old && withMark.checked && clean.lat !== undefined) {
      const m = { id: C.uuid(), lat: clean.lat, lon: clean.lon, name: C.catchTitle(clean), time: clean.time, group: openGroup === null ? '' : openGroup };
      if (clean.depth) m.depth = clean.depth;
      const g = C.findGroup(state.groups, m.group); if (g) g.visible = true;
      state.marks.push(m); clean.mark = m.id;
    }
    state.display.catchMark = withMark.checked;
    const i = state.catches.findIndex((x) => x.id === clean.id);
    if (i >= 0) state.catches[i] = clean; else state.catches.push(clean);
    save(); drawData(); renderCatches(); renderMarks();
    toast(old ? 'Catch saved' : `Logged: ${C.catchTitle(clean)}`);
  }

  async function showCatch(c) {
    const r = checkOf(c, state.catches.filter((x) => x.id !== c.id));
    const info = [new Date(c.time).toLocaleString(), C.catchDetails(c), c.bait ? `Bait: ${c.bait}` : '', c.depth ? `Depth (est.): ${c.depth} m` : '',
      c.lat !== undefined ? C.fmtPos(c) : 'No position', c.notes, c.photos ? `${c.photos.length} photo${c.photos.length === 1 ? '' : 's'} on the phone` : ''].filter(Boolean).join('\n');
    const a = await dialog(C.catchTitle(c), [info, limitsBox(r, c.released)], [['Delete', 'del', 'btn danger'], ['Edit', 'edit'], ...(c.lat !== undefined ? [['Show on map', 'map']] : []), ['Close', null, 'primary']]);
    if (a === 'edit') logCatch(c, 'here', c);
    if (a === 'map') { map.easeTo({ center: [c.lon, c.lat], zoom: Math.max(map.getZoom(), 14) }); if (PHONE) collapse(true); }
    if (a === 'del' && await confirmBox('Delete catch', `Delete ${C.catchTitle(c)}? This can't be undone.`, 'Delete')) {
      state.catches = state.catches.filter((x) => x.id !== c.id); save(); drawData(); renderCatches();
    }
  }

  function renderCatches() {
    const pane = $('pane-catches');
    if (!pane) return;
    const list = [...state.catches].sort((a, b) => b.time - a.time);
    const today = list.filter((c) => new Date(c.time).toDateString() === new Date().toDateString());
    const kept = today.filter((c) => !c.released).reduce((s, c) => s + c.count, 0), rel = today.filter((c) => c.released).reduce((s, c) => s + c.count, 0);
    const out = [el('div.btns', {}, el('button.primary', { onclick: () => { const h = here(); logCatch(h.p, h.at); } }, 'Log a catch'),
      el('button.btn', { disabled: !list.length, onclick: () => download(`chompchamp-catches-${stamp()}.csv`, C.catchesCsv(list), 'text/csv') }, 'Export CSV')),
    el('p.muted', {}, `Logged at your boat when the GPS is on, otherwise at the middle of the map. Hold the map to log one somewhere else.${today.length ? ` Today: ${kept} kept, ${rel} released.` : ''}`)];
    if (!list.length) out.push(el('p.empty', {}, 'No catches yet. Catches from the phone come across with its backup (Data tab).'));
    let day = '';
    for (const c of list) {
      const d = new Date(c.time).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
      if (d !== day) { day = d; out.push(el('h3', {}, d)); }
      out.push(el('div.row', {}, el('button.link.grow.clip', { onclick: () => showCatch(c) }, C.catchTitle(c)),
        el('span.muted', {}, `${C.catchDetails(c)} · ${new Date(c.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`)));
    }
    pane.replaceChildren(...out);
  }

  function renderLimits() {
    const pane = $('pane-limits');
    if (!pane) return;
    if (!limits) { pane.replaceChildren(el('p.empty', {}, 'The bag limits couldn\'t be read.')); return; }
    const where = limitsAt || here().p, auto = limits.areaAt(where);
    const area = limitArea || auto || 'west';
    const pick = el('select', { ariaLabel: 'Area', onchange: (e) => { limitArea = e.target.value; renderLimits(); } },
      el('option', { value: '' }, `Where ${limitsAt ? 'you held' : here().at === 'boat' ? 'you are' : 'the map is'}: ${auto ? C.areaLabel(auto) : 'outside WA'}`),
      C.LIMIT_AREAS.map(([k, label]) => el('option', { value: k, selected: k === limitArea }, label)));
    const list = el('div', {});
    const day = C.dayOf(Date.now());
    const fill = () => {
      const groups = limits.find(area, limitQuery);
      const rows = [];
      for (const g of groups) {
        rows.push(el('h3', {}, g.name + (g.total ? ` · total ${g.total}` : '')));
        if (g.boatClosed) rows.push(el('div.problem', {}, `${g.boatClosed}: shore only, except where marked open.`));
        if (g.note) rows.push(el('p.muted', {}, g.note));
        for (const e of g.entries) {
          const closed = e.closures.filter((c) => C.closureOn(c, day));
          const lines = [e.note, e.boat ? `Boat limit: ${e.boat}` : null,
            ...e.closures.filter((c) => !C.closureOn(c, day)).map((c) => `Closed ${c.dates}: ${c.where}.`)].filter(Boolean);
          const shut = g.boatClosed && !e.boatOpen && !e.isProtected;
          rows.push(el('div.limit', {},
            el('div.row', {}, el('strong.grow', {}, e.name),
              el('span.muted', {}, e.isProtected ? 'Protected' : [e.size ? e.size : null, e.bag ? `${shut ? 'shore ' : 'bag '}${e.bag}` : null].filter(Boolean).join(' · '))),
            closed.map((c) => el('div.problem', {}, `${c.area && limits.inside(c.area, where) ? 'Closed here now' : 'Closed now'}: ${c.where}, ${c.dates}.${c.note ? ` ${c.note}` : ''}`)),
            lines.map((l) => el('div.muted', {}, l))));
        }
      }
      if (!groups.length) rows.push(el('p.empty', {}, `Nothing called "${limitQuery}". Try another name.`));
      list.replaceChildren(...rows);
    };
    const old = limits.reviewBy && new Date().toISOString().slice(0, 10) > limits.reviewBy;
    pane.replaceChildren(...[el('div.row', {}, pick),
      el('input', { type: 'search', placeholder: 'Search species (e.g. dhufish, skippy, crab)', ariaLabel: 'Search species', value: limitQuery,
        oninput: (e) => { limitQuery = e.target.value; fill(); } }),
      old ? el('div.problem', {}, 'These limits are due for a check: look at DPIRD\'s current guide.') : null,
      list,
      el('p.muted', {}, `From ${limits.source}, checked ${limits.checked}, in our own words. Limits are per person per day. Always check the current rules: `),
      el('p', {}, el('a', { href: limits.url, target: '_blank', rel: 'noopener noreferrer', style: 'color:var(--accent)' }, 'DPIRD recreational fishing guide'))].filter(Boolean));
    fill();
  }
