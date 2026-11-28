  // ---- Vessels (AIS), from Open Waters AIS (Ais.kt) ---------------------------------------------------------------------------
  // Only while the layer is on and the page is on screen: about once a minute for the area on screen. Shown live, never
  // stored or exported (AISHub's terms). A browser can't set its own User-Agent, so requests can't carry the app's.
  const AIS_API = 'https://ais.openwaters.io/v1/vessels';
  const AIS_OLD_MS = 10 * 60000, AIS_GREY = '#78909C';
  const ais = { all: [], fetched: null, lastTry: 0, failures: 0, busy: false, lastOk: 0, timer: 0, running: false };
  const aisOn = () => layerState('ais', false).on;
  const aisVessels = (now = Date.now()) => ais.all.filter((v) => now - v.seen <= setting('ais_age') * 60000);

  /** The layer was switched on or off. Off: forget everything. */
  function aisChanged() {
    const on = aisOn();
    $('aislabel').hidden = !on;
    if (!on) {
      clearTimeout(ais.timer);
      Object.assign(ais, { all: [], fetched: null, lastTry: 0, failures: 0, lastOk: 0, running: false });
      drawAis();
      return;
    }
    if (!ais.running) { ais.running = true; aisTick(); }
  }
  function aisTick() {
    clearTimeout(ais.timer);
    if (!aisOn()) { ais.running = false; return; }
    if (document.visibilityState === 'visible') aisFetch();
    drawAis();
    ais.timer = setTimeout(aisTick, 5000);
  }
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && ais.running) aisTick(); });

  function aisFetch() {
    if (ais.busy || navigator.onLine === false) return;
    const b = map.getBounds(), want = C.aisClip({ s: b.getSouth(), w: b.getWest(), n: b.getNorth(), e: b.getEast() });
    if (!(want.n > want.s && want.e > want.w)) return;
    const now = Date.now();
    const due = ais.failures > 0 ? 30000 * 2 ** Math.min(3, ais.failures - 1)   // 30 s, 1, 2, then 4 min
      : !ais.fetched || !C.aisContains(ais.fetched, want) ? 10000 : 60000;
    if (now - ais.lastTry < due) return;
    ais.lastTry = now; ais.busy = true;
    const box = C.aisPad(want);
    const url = `${AIS_API}?bbox=${[box.s, box.w, box.n, box.e].map((x) => x.toFixed(2)).join(',')}&max_age=${setting('ais_age') * 60}`;
    (async () => {
      try {
        const r = await fetch(url, { referrerPolicy: 'no-referrer', credentials: 'omit' });
        if (r.status !== 200) throw new Error(`HTTP ${r.status}`);
        const text = await r.text();
        if (text.length > 8e6) throw new Error('answer too big');
        const list = C.parseVessels(JSON.parse(text));
        if (aisOn()) Object.assign(ais, { all: list, fetched: box, failures: 0, lastOk: Date.now() });
      } catch (e) { ais.failures++; }
      ais.busy = false;
      drawAis();
    })();
  }

  function drawAis() {
    const now = Date.now(), list = aisOn() ? aisVessels(now) : [];
    setData('ais', list.map((v) => {
      const dir = v.heading ?? v.cog, moving = C.vesselMoving(v);
      return point(v, { mmsi: String(v.mmsi), name: C.vesselLabel(v), icon: `ais-${moving && dir !== null ? 'move' : 'stop'}${now - v.seen > AIS_OLD_MS ? '-old' : ''}`, rot: moving ? dir ?? 0 : 0 });
    }));
    // Where it would be in six minutes, if it was heard recently.
    setData('ais-vector', list.filter((v) => C.vesselMoving(v) && v.sog !== null && v.cog !== null && now - v.seen <= AIS_OLD_MS)
      .map((v) => line([v, C.destination(v, v.cog, v.sog / 10)])));
    $('aislabel').textContent = aisStatus();
  }
  /** A short line by the map, with the credit AISHub asks to see next to its data. */
  function aisStatus() {
    if (!ais.lastOk) return ais.failures > 0 ? 'AIS: no connection' : 'AIS: loading…';
    const n = aisVessels().length, age = Math.floor((Date.now() - ais.lastOk) / 60000);
    return `AIS: Open Waters, AISHub contributors\n${n} vessel${n === 1 ? '' : 's'}${age >= 3 ? `\nNo update for ${age} min` : ''}`;
  }
  $('aislabel').onclick = () => showAisInfo();

  /** Arrow for a moving vessel, dot for a stopped one. 22 units at 2x. */
  function aisIcon(moving, colour) {
    const cv = Object.assign(document.createElement('canvas'), { width: 44, height: 44 }), c = cv.getContext('2d');
    c.scale(2, 2); c.lineJoin = 'round';
    const m = 11;
    c.beginPath();
    if (moving) { c.moveTo(m, m - 9); c.lineTo(m + 6, m + 8); c.lineTo(m, m + 4.5); c.lineTo(m - 6, m + 8); c.closePath(); } else c.arc(m, m, 4.5, 0, 7);
    c.fillStyle = colour; c.fill(); c.strokeStyle = '#ffffff'; c.lineWidth = 1.4; c.stroke();
    return c.getImageData(0, 0, 44, 44);
  }
  function addAisIcon(id) {
    if (!map.getStyle()) return;
    if (map.hasImage(id)) map.removeImage(id);
    map.addImage(id, aisIcon(id.includes('move'), id.endsWith('-old') ? AIS_GREY : aisColour()), { pixelRatio: 2 });
  }
  /** The colour or size changed in Display. */
  function styleAis() {
    for (const id of ['ais-move', 'ais-stop', 'ais-move-old', 'ais-stop-old']) if (map.hasImage(id)) addAisIcon(id);
    if (map.getLayer('ais')) map.setLayoutProperty('ais', 'icon-size', look().ais_scale);
    if (map.getLayer('ais-vector')) map.setPaintProperty('ais-vector', 'line-color', aisColour());
  }

  function showVessel(v) {
    const b = lastPos(), min = Math.floor((Date.now() - v.seen) / 60000);
    const size = v.length > 0 ? `${v.length} m${v.beam > 0 ? ` × ${v.beam} m` : ''}` : null;
    const lines = [[C.vesselType(v.type), size, C.vesselStatus(v.navStatus)].filter(Boolean).join(' · '),
      `Last heard ${min < 1 ? 'less than a minute ago' : `${min} min ago`}`,
      v.sog !== null ? `${v.sog.toFixed(1)} kn${v.cog !== null && C.vesselMoving(v) ? ` → ${C.fmtBearing(v.cog)}` : ''}` : null,
      b ? C.fromBoat(b, v) : null, v.destination ? `Destination: ${v.destination}` : null,
      [`MMSI ${v.mmsi}`, v.callsign || null, v.flag || null].filter(Boolean).join(' · '), C.fmtPos(v)].filter(Boolean);
    const link = (text, href) => el('a.link', { href, target: '_blank', rel: 'noopener noreferrer' }, text);
    dialog(C.vesselLabel(v), [el('p.msg', {}, lines.join('\n')), link('Open Waters AIS', 'https://openwaters.io/ais/'), el('br', {}),
      v.source === 'aisstream' ? link('Received by aisstream.io', 'https://aisstream.io') : link('Data: AISHub contributors', 'https://www.aishub.net')]);
  }
  function showAisInfo() {
    const link = (text, href) => [el('a.link', { href, target: '_blank', rel: 'noopener noreferrer' }, text), el('br', {})];
    dialog('Vessels (AIS)', ['Boats that transmit AIS, picked up by volunteer shore stations and passed on over the internet. Positions run a minute ' +
      "or more behind, and boats without AIS or out of range of a station aren't shown.",
    'Arrow: moving (the line shows 6 minutes ahead). Dot: stopped. Grey: not heard for 10 minutes. Change how long they stay in Settings, the size in Display and the opacity in Layers.',
    ...link('Open Waters AIS', 'https://openwaters.io/ais/'), ...link('Data: AISHub contributors', 'https://www.aishub.net'), ...link('aisstream.io', 'https://aisstream.io')]);
  }
