  // ---- Display (Appearance.kt), Settings (Settings.kt), Layers, the ☰ menu, data, charts and About --------------------------------
  const OPTIONS = [
    ['arrival_nm', 'Waypoint arrival radius', [0.02, 0.05, 0.1, 0.2, 0.5], ['37 m', '93 m (0.05 nm)', '0.1 nm', '0.2 nm', '0.5 nm'], 0.05],
    ['plan_kn', 'Planning speed (route ETA)', [5, 8, 10, 15, 20, 25, 30], ['5 kn', '8 kn', '10 kn', '15 kn', '20 kn', '25 kn', '30 kn'], 15],
    ['course_up', 'Map orientation while navigating / fishing', [0, 1], ['North up', 'Course up'], 0],
    ['drift_window', 'Drift averaging window', [30, 60, 120, 300], ['30 s', '1 min', '2 min', '5 min'], 60],
    ['drift_project', 'Drift projection on map', [2, 5, 10, 15, 30], ['2 min', '5 min', '10 min', '15 min', '30 min'], 5],
    ['fish_zoom', 'Fish mode zoom', [14, 15, 16, 17], ['Wide', 'Medium', 'Close', 'Very close'], 16],
    ['anchor_m', 'Anchor alarm radius', [20, 30, 50, 75, 100, 150], ['20 m', '30 m', '50 m', '75 m', '100 m', '150 m'], 50],
    ['track_window', 'Track history shown', [1, 2, 5, 10, 15, 30, 60, 120, 240, 480, 720, -1],
      ['1 min', '2 min', '5 min', '10 min', '15 min', '30 min', '1 hour', '2 hours', '4 hours', '8 hours', '12 hours', 'Entire trip'], -1],
    ['track_spacing', 'Track point spacing', [5, 10, 25, 50, 100], ['5 m (most detail)', '10 m', '25 m', '50 m', '100 m (longest history)'], 10],
    ['trip_start', 'Start recording a trip', [1, 0], ['When I start a route or mark a catch', 'Only when I tap Record'], 1],
    ['trip_split', 'Finish a trip by itself after a break of', [2, 4, 6, 12, -1], ['2 hours', '4 hours', '6 hours', '12 hours', 'Never'], 6],
    ['safety_m', 'Safety depth (route check)', [2, 3, 5, 10, 20], ['2 m', '3 m', '5 m', '10 m', '20 m'], 5],
    ['awake', 'Keep the screen on while the GPS is on', [1, 0], ['On', 'Off'], 1],
    ['sound', 'Beep with alerts', [1, 0], ['On', 'Off'], 1],
    ['ais_age', 'Hide AIS vessels not heard for', [10, 30, 60], ['10 min', '30 min', '60 min'], 30],
  ];
  const SECTIONS = [['Navigation', ['arrival_nm', 'plan_kn', 'course_up', 'safety_m']], ['Fishing', ['fish_zoom', 'drift_window', 'drift_project', 'anchor_m']],
    ['Trips and track', ['trip_start', 'trip_split', 'track_window', 'track_spacing']], ['Vessels', ['ais_age']], ['This phone or computer', ['awake', 'sound']]];
  function setting(k) {
    const o = OPTIONS.find((x) => x[0] === k), v = state.settings[k];
    return o[2].includes(v) ? v : o[4];
  }
  const settingLabel = (o) => o[3][o[2].indexOf(setting(o[0]))] ?? String(setting(o[0]));

  function showSettings() {
    dialog('Settings', (close) => {
      const out = [];
      out.push(el('h3.sec', {}, 'GPS'), el('button.srow2', { type: 'button', onclick: () => { close(); if (gps.watch !== null) { stopGps(); snack('GPS off'); } else followBoat(); } },
        el('span', {}, gps.watch !== null ? 'Location is on: your boat shows on the map' : 'Location is off'), el('span.pill', {}, gps.watch !== null ? 'Turn off' : 'Turn on')));
      for (const [heading, keys] of SECTIONS) {
        out.push(el('h3.sec', {}, heading));
        for (const k of keys) {
          const o = OPTIONS.find((x) => x[0] === k);
          const pill = el('span.pill', {}, settingLabel(o));
          out.push(el('button.srow2', { type: 'button', onclick: async () => {
            const i = await pickOne(o[1], o[3], o[2].indexOf(setting(k)));
            if (i === null) return;
            state.settings[k] = o[2][i]; save(); pill.textContent = settingLabel(o); settingsChanged();
          } }, el('span', {}, o[1]), pill));
        }
      }
      out.push(el('p.small', {}, IOS ? 'iOS stops the GPS when the phone locks or you switch apps, so the track, alarms and navigation only work while the planner is open on screen.'
        : 'The GPS, track and alarms only work while this page is open.'));
      return out;
    }, [['Done', null, 'primary']], { cls: 'tall' });
  }
  function settingsChanged() {
    drawTrack(); drawNav(); drawAis(); refreshModePanel(); updateBanner(); keepAwake(gps.watch !== null);
    if ((state.mode === 'nav' || state.mode === 'fish') && gps.follow && gps.fix) moveToBoat(state.mode === 'fish' ? setting('fish_zoom') : map.getZoom());
    if (state.mode === 'route') refreshModePanel();
  }

  // ---- Look: sizes and transparency of everything over the map -------------------------------------------------------------------------
  function applyLook() {
    const L = look(), r = document.documentElement.style;
    r.setProperty('--cs', L.control_scale); r.setProperty('--ca', L.control_alpha);
    r.setProperty('--ia', (0.55 + 0.45 * L.control_alpha).toFixed(3));
    $('scalebar').hidden = !L.scale_bar;
    updateBag(); updateRecord();
    if (map.getLayer('catches')) map.setLayoutProperty('catches', 'icon-size', L.catch_scale);
    if (map.getLayer('places')) map.setLayoutProperty('places', 'text-size', placeSize(L.place_scale));
    updateBoatIcon(); styleAis();
    fields.style();
    refreshModePanel(); updateScaleBar();
  }
  const updateBag = () => { $('btn-bag').hidden = !(state.mode === 'fish' && look().bag_button); };

  // ---- The scale bar (ScaleBarView), above the Follow button or the mode panel --------------------------------------------------------------
  function updateScaleBar() {
    const s = $('scalebar');
    if (s.hidden) return;
    const w = innerWidth, y = innerHeight / 2, span = w / 4;
    const a = map.unproject([w / 2 - span / 2, y]), b = map.unproject([w / 2 + span / 2, y]);
    const mpp = (C.distanceNm({ lat: a.lat, lon: a.lng }, { lat: b.lat, lon: b.lng }) * 1852) / span;
    if (!(mpp > 0)) return;
    const { px, label } = C.scaleBar(mpp, 96 * look().control_scale);
    s.firstChild.textContent = label; s.lastChild.style.width = `${px}px`;
  }
  map.on('move', updateScaleBar);
  function placeScaleBar() {
    const L = look(), size = 44 * L.control_scale, mp = $('modepanel');
    const above = size + 14 + 14 * L.control_scale, overPanel = mp.hidden ? 0 : mp.offsetHeight + 12;
    document.documentElement.style.setProperty('--sb', `${Math.max(above, overPanel)}px`);
  }

  // ---- Your boat (BoatIcons.kt): shapes drawn in a 100 x 100 box, bow up -------------------------------------------------------------------
  const BOATS = [['arrow', 'Classic arrow', '#1E88E5'], ['tinny', 'Tinny', '#B0BEC5'], ['console', 'Centre console', '#F5F7FA'], ['cruiser', 'Cabin cruiser', '#F5F7FA'],
    ['yacht', 'Yacht', '#F5F7FA'], ['catamaran', 'Catamaran', '#F5F7FA'], ['kayak', 'Kayak', '#E53935'], ['jetski', 'Jet ski', '#1E88E5'], ['tug', 'Tugboat', '#E53935'],
    ['duck', 'Rubber duck', '#FFD54F'], ['pirate', 'Pirate ship', '#8D6E63'], ['viking', 'Viking longship', '#8D6E63'], ['submarine', 'Submarine', '#37474F']];
  const BOAT_COLOURS = [['natural', 'Its own colour', null], ['white', 'White', '#F5F7FA'], ['red', 'Red', '#E53935'], ['blue', 'Blue', '#1E88E5'],
    ['green', 'Green', '#43A047'], ['pink', 'Pink', '#F06292'], ['charcoal', 'Charcoal', '#37474F']];
  const mix = (a, b, k) => { const x = rgbOf(a), y = rgbOf(b); return `rgb(${x.map((v, i) => Math.round(v + (y[i] - v) * k)).join(',')})`; };
  const lum = (hex) => { const [r, g, b] = rgbOf(hex).map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
  /** The boat as a canvas [px] square. */
  function drawBoatShape(shape, colourKey, px, alpha = 1) {
    const cv = Object.assign(document.createElement('canvas'), { width: px, height: px }), c = cv.getContext('2d');
    const OUT = '#101C2B', GLASS = '#4FC3F7', BEAK = '#FF9800', RED = '#E53935', WHITE = '#F5F7FA';
    const hull = (BOAT_COLOURS.find((x) => x[0] === colourKey) || [])[2] || (BOATS.find((b) => b[0] === shape) || BOATS[0])[2];
    const light = lum(hull) > 0.6 ? mix(hull, OUT, 0.18) : mix(hull, WHITE, 0.6), dark = mix(hull, OUT, 0.55);
    c.globalAlpha = alpha; c.scale(px / 108, px / 108); c.translate(4, 4); c.lineJoin = 'round'; c.lineCap = 'round';
    const shapeP = (p, col, w = 3.5) => { c.fillStyle = col; c.fill(p); c.strokeStyle = OUT; c.lineWidth = w; c.stroke(p); };
    const P = (f) => { const p = new Path2D(); f(p); return p; };
    const rect = (l, t, r, b, rad, col, w = 2.5) => shapeP(P((p) => p.roundRect(l, t, r - l, b - t, rad)), col, w);
    const oval = (l, t, r, b, col, w = 2.5) => shapeP(P((p) => p.ellipse((l + r) / 2, (t + b) / 2, (r - l) / 2, (b - t) / 2, 0, 0, 7)), col, w);
    const dot = (x, y, r, col) => { c.fillStyle = col; c.beginPath(); c.arc(x, y, r, 0, 7); c.fill(); };
    const stroke = (x0, y0, x1, y1, w, col = OUT) => { c.strokeStyle = col; c.lineWidth = w; c.beginPath(); c.moveTo(x0, y0); c.lineTo(x1, y1); c.stroke(); };
    const pointed = (top, bottom, half, th = half - 2) => P((p) => {
      const mid = top + (bottom - top) * 0.5;
      p.moveTo(50, top); p.bezierCurveTo(50 + half * 0.7, top + (mid - top) * 0.35, 50 + half, top + (mid - top) * 0.7, 50 + half, mid);
      p.lineTo(50 + th, bottom); p.lineTo(50 - th, bottom); p.lineTo(50 - half, mid);
      p.bezierCurveTo(50 - half, top + (mid - top) * 0.7, 50 - half * 0.7, top + (mid - top) * 0.35, 50, top); p.closePath();
    });
    const draw = {
      arrow: () => shapeP(P((p) => { p.moveTo(50, 6); p.lineTo(80, 90); p.lineTo(50, 72); p.lineTo(20, 90); p.closePath(); }), hull, 4.5),
      tinny: () => { rect(43, 82, 57, 96, 3, OUT); shapeP(P((p) => { p.moveTo(30, 28); p.quadraticCurveTo(32, 7, 50, 7); p.quadraticCurveTo(68, 7, 70, 28); p.lineTo(72, 84); p.lineTo(28, 84); p.closePath(); }), hull);
        rect(30, 34, 70, 41, 1.5, dark, 1.5); rect(29, 60, 71, 67, 1.5, dark, 1.5); },
      console: () => { rect(33, 86, 44, 97, 3, OUT); rect(56, 86, 67, 97, 3, OUT); shapeP(pointed(5, 88, 23), hull); rect(40, 44, 60, 60, 3, light); rect(40, 42, 60, 47, 2, GLASS, 1.5); rect(38, 70, 62, 76, 2, dark, 1.5); },
      cruiser: () => { shapeP(pointed(3, 93, 25), hull); rect(33, 30, 67, 70, 8, light);
        shapeP(P((p) => { p.moveTo(35, 36); p.quadraticCurveTo(50, 26, 65, 36); p.lineTo(65, 42); p.quadraticCurveTo(50, 34, 35, 42); p.closePath(); }), GLASS, 1.5); stroke(33, 82, 67, 82, 2, dark); },
      yacht: () => { shapeP(pointed(4, 93, 17), hull); shapeP(P((p) => { p.moveTo(50, 9); p.quadraticCurveTo(59, 22, 50, 34); p.closePath(); }), WHITE, 2);
        shapeP(P((p) => { p.moveTo(50, 36); p.quadraticCurveTo(66, 58, 56, 84); p.lineTo(50, 84); p.closePath(); }), WHITE, 2); dot(50, 36, 3.5, OUT); stroke(50, 36, 50, 84, 2); },
      catamaran: () => { rect(34, 22, 66, 40, 2, dark, 1.5); for (const y of [26, 31, 36]) stroke(36, y, 64, y, 1, light); rect(32, 38, 68, 82, 4, hull); rect(39, 46, 61, 72, 5, light); rect(41, 46, 59, 51, 2, GLASS, 1.5);
        for (const side of [-1, 1]) { const x = 50 + side * 22; shapeP(P((p) => { p.moveTo(x, 4); p.bezierCurveTo(x + 8, 18, x + 9, 40, x + 8, 94); p.lineTo(x - 8, 94); p.bezierCurveTo(x - 9, 40, x - 8, 18, x, 4); p.closePath(); }), hull); } },
      kayak: () => { shapeP(P((p) => { p.moveTo(50, 3); p.bezierCurveTo(59, 24, 61, 52, 59, 70); p.bezierCurveTo(57, 86, 54, 94, 50, 97); p.bezierCurveTo(46, 94, 43, 86, 41, 70); p.bezierCurveTo(39, 52, 41, 24, 50, 3); p.closePath(); }), hull);
        oval(44, 42, 56, 62, OUT); stroke(18, 55, 82, 45, 3); c.save(); c.translate(50, 50); c.rotate((-9 * Math.PI) / 180); c.translate(-50, -50); oval(8, 44, 20, 60, light, 2); oval(80, 40, 92, 56, light, 2); c.restore(); },
      jetski: () => { shapeP(pointed(12, 88, 17, 14), hull); rect(43, 50, 57, 82, 6, dark, 2); stroke(33, 42, 67, 42, 3.5); dot(50, 42, 3.5, OUT); },
      tug: () => { rect(24, 10, 76, 92, 24, hull, 6); rect(35, 24, 65, 50, 4, WHITE); rect(37, 26, 63, 31, 1.5, GLASS, 1.5); oval(43, 56, 57, 70, OUT, 2); dot(50, 63, 3.5, dark); stroke(40, 82, 60, 82, 3); },
      duck: () => { shapeP(P((p) => { p.moveTo(50, 86); p.lineTo(41, 98); p.lineTo(59, 98); p.closePath(); }), hull); oval(24, 28, 76, 92, hull); oval(26, 46, 38, 76, dark, 1.5); oval(62, 46, 74, 76, dark, 1.5);
        shapeP(P((p) => { p.moveTo(42, 16); p.quadraticCurveTo(50, 0, 58, 16); p.closePath(); }), BEAK, 2); oval(36, 12, 64, 40, hull); dot(43, 22, 3, OUT); dot(57, 22, 3, OUT); dot(44, 21, 1, WHITE); dot(58, 21, 1, WHITE); },
      pirate: () => { shapeP(pointed(2, 94, 25, 19), hull); for (const x of [42, 50, 58]) stroke(x, 14, x, 90, 0.8, dark); for (const y of [26, 50, 74]) dot(50, y, 3, OUT);
        rect(24, 20, 76, 30, 4, WHITE); rect(20, 44, 80, 55, 4, WHITE); rect(24, 68, 76, 78, 4, WHITE); rect(50, 87, 66, 98, 1.5, OUT, 1.5); dot(58, 91.5, 2.6, WHITE); stroke(55, 95.5, 61, 95.5, 1.2, WHITE); },
      viking: () => { shapeP(P((p) => { p.moveTo(50, 2); p.bezierCurveTo(62, 16, 68, 36, 68, 56); p.bezierCurveTo(68, 76, 62, 90, 50, 98); p.bezierCurveTo(38, 90, 32, 76, 32, 56); p.bezierCurveTo(32, 36, 38, 16, 50, 2); p.closePath(); }), hull);
        [28, 38, 48, 58, 68, 78].forEach((y, i) => { const col = i % 2 === 0 ? RED : WHITE; oval(29, y - 4.5, 38, y + 4.5, col, 1.5); oval(62, y - 4.5, 71, y + 4.5, col === WHITE ? RED : WHITE, 1.5); });
        rect(22, 44, 78, 55, 2, WHITE); c.fillStyle = RED; for (const x of [30, 42, 54, 66]) c.fillRect(x, 45.5, 6, 8); dot(50, 6, 3.5, OUT); },
      submarine: () => { rect(30, 80, 70, 85, 2, dark, 2); rect(36, 6, 64, 94, 14, hull); rect(42, 30, 58, 54, 7, dark, 2); dot(50, 35, 2.5, light); stroke(44, 98, 56, 98, 2.5); stroke(50, 94, 50, 100, 2.5); },
    };
    (draw[shape] || draw.arrow)();
    return cv;
  }
  function updateBoatIcon() {
    const L = look(), px = Math.round(56 * L.boat_scale * 2);
    const img = drawBoatShape(L.boat_shape, L.boat_colour, px, L.boat_alpha).getContext('2d').getImageData(0, 0, px, px);
    if (map.hasImage('own-boat')) map.removeImage('own-boat');
    if (map.getStyle()) map.addImage('own-boat', img, { pixelRatio: 2 });
  }

  // ---- Display panel ---------------------------------------------------------------------------------------------------------------------
  const DISPLAY = 'Display';
  const refreshDisplayPanel = () => { if (panel.current === DISPLAY) showDisplay(); };
  function showDisplay() {
    panel.show(DISPLAY, (rows) => {
      const L = look(), m = modeLabel(state.mode);
      rows.section(`Data fields · ${m} mode`);
      rows.actionRow('Fields shown', String(FIELDS.filter(([k]) => fields.isVisible(k)).length), async () => {
        const a = await pickMany(`Fields shown: ${m} mode`, FIELDS.map((f) => f[1].replace('  ', ' ')), FIELDS.map(([k]) => fields.isVisible(k)));
        if (a) { const c = fields.cfg(); c.vis = Object.fromEntries(FIELDS.map(([k], i) => [k, a[i]])); save(); fields.arrange(); updateFields(); refreshDisplayPanel(); }
      });
      rows.actionRow('Arrangement', fields.grouping[1], async () => {
        const i = await pickOne(`Arrangement: ${m} mode`, GROUPINGS.map((g) => g[2]), GROUPINGS.indexOf(fields.grouping));
        if (i !== null) { fields.grouping = GROUPINGS[i]; refreshDisplayPanel(); }
      });
      rows.actionRow('Move or resize on screen', 'Edit', () => { panel.hide(); fields.setEditing(true); refreshModePanel(); });
      const knob = (k) => KNOBS.find((x) => x[0] === k);
      for (const [title, items] of [['Data field style · all modes', [['field_scale', 'Size'], ['field_alpha', 'Background']]],
        ['Buttons and panels · all modes', [['control_scale', 'Size'], ['control_alpha', 'Opacity']]],
        ['Map symbols', [['ais_scale', 'AIS vessel size'], ['catch_scale', 'Catch size'], ['place_scale', 'Place name size'], ['boat_scale', 'Boat size'], ['boat_alpha', 'Boat opacity']]]]) {
        rows.section(title);
        for (const [k, label] of items) {
          const [, from, to] = knob(k);
          rows.sliderRow({ label, pct: Math.round(L[k] * 100), from: Math.round(from * 100), to: Math.round(to * 100), onPct: (p) => { state.look[k] = p / 100; save(); applyLook(); } });
        }
      }
      rows.section('Map');
      rows.actionRow('Scale bar', L.scale_bar ? 'On' : 'Off', () => { state.look.scale_bar = !L.scale_bar; save(); applyLook(); refreshDisplayPanel(); });
      rows.actionRow('AIS vessel colour', (AIS_COLOURS.find((c) => c[0] === L.ais_colour) || AIS_COLOURS[0])[1], async () => {
        const i = await pickOne('AIS vessel colour', AIS_COLOURS.map((c) => c[1]), Math.max(0, AIS_COLOURS.findIndex((c) => c[0] === L.ais_colour)));
        if (i !== null) { state.look.ais_colour = AIS_COLOURS[i][0]; save(); styleAis(); refreshDisplayPanel(); }
      });
      rows.section('Your boat');
      const name = el('p.note', {}, (BOATS.find((b) => b[0] === L.boat_shape) || BOATS[0])[1]);
      const strip = el('div.boats', {}, BOATS.map(([k, label]) => {
        const t = el('button.boat', { type: 'button', ariaLabel: label, title: label, ariaPressed: String(k === L.boat_shape), onclick: () => {
          state.look.boat_shape = k; save(); name.textContent = label; strip.querySelectorAll('.boat').forEach((b) => b.setAttribute('aria-pressed', String(b === t))); updateBoatIcon();
        } }, drawBoatShape(k, L.boat_colour, 88));
        return t;
      }));
      rows.view(strip); rows.view(name);
      requestAnimationFrame(() => { const on = strip.querySelector('[aria-pressed=true]'); if (on) strip.scrollLeft = Math.max(0, on.offsetLeft - 56); });
      rows.actionRow('Boat colour', (BOAT_COLOURS.find((c) => c[0] === L.boat_colour) || BOAT_COLOURS[0])[1], async () => {
        const i = await pickOne('Boat colour', BOAT_COLOURS.map((c) => c[1]), Math.max(0, BOAT_COLOURS.findIndex((c) => c[0] === L.boat_colour)));
        if (i !== null) { state.look.boat_colour = BOAT_COLOURS[i][0]; save(); updateBoatIcon(); refreshDisplayPanel(); }
      });
      rows.actionRow('Record button', L.rec_button ? 'On' : 'Off', () => { state.look.rec_button = !L.rec_button; save(); updateModeLabel(); refreshDisplayPanel(); });
      if (state.mode === 'fish') rows.actionRow('Bag limits button', L.bag_button ? 'On' : 'Off', () => { state.look.bag_button = !L.bag_button; save(); updateBag(); refreshDisplayPanel(); });
      rows.section('Reset');
      rows.actionRow(`Layout · ${m} mode`, 'Reset', async () => {
        if (await confirmBox(null, `Are you sure you want to reset the ${m} layout?\n\nData fields go back to their default position and size. Which fields are shown, and how they're arranged, stays the same.`, 'Reset')) {
          fields.resetLayout(); toast('Layout reset');
        }
      });
      rows.actionRow('Sizes and transparency · all modes', 'Reset', async () => {
        if (await confirmBox(null, 'Are you sure you want to reset all sizes and transparency to their defaults?', 'Reset')) {
          for (const [k] of KNOBS) delete state.look[k];
          save(); applyLook(); refreshDisplayPanel();
        }
      });
    });
  }

  // ---- Layers panel ---------------------------------------------------------------------------------------------------------------------
  const LAYERS_PANEL = 'Map layers';
  function showLayers() {
    panel.show(LAYERS_PANEL, (rows) => {
      for (const [title, group] of LAYERS) {
        rows.section(title);
        for (const [key, label, def] of group) {
          const s = layerState(key, def);
          const set = (patch) => { state.layers[key] = { ...layerState(key, def), ...patch }; save(); applyLayers(); if (key === 'wind' || key === 'waves') { windChanged(); fetchWindForView(); } if (key === 'ais') aisChanged(); };
          rows.sliderRow({ label, pct: s.pct, checked: s.on, onChecked: (on) => set({ on }), onPct: (p) => set({ pct: p }) });
        }
      }
      rows.note('Sliders set each layer\'s opacity. No-fishing alerts keep working when zones are hidden. Satellite and online reef relief need internet. ' +
        'Wind and waves are forecasts for the area on screen from Open-Meteo, kept for when you\'re offline (☰ → Wind, waves & tide). ' +
        'AIS asks openwaters.io for vessels in the area on screen.');
      rows.button('Reset layers', async () => {
        if (await confirmBox(null, 'Put every layer back to its default?', 'Reset')) { state.layers = {}; save(); applyLayers(); windChanged(); aisChanged(); showLayers(); }
      });
    });
  }
  $('btn-layers').onclick = () => { if (panel.current === LAYERS_PANEL) panel.hide(); else showLayers(); };

  // ---- The ☰ menu ---------------------------------------------------------------------------------------------------------------------------
  $('btn-menu').onclick = (e) => {
    menuGuard = Date.now() + 300;
    popup(e.currentTarget, [['Search places…', openPlaces], ['Wind, waves & tide…', showWindTidePanel], ['Marks…', showMarks], ['Trips & catches…', showTrips],
      ['Bag & size limits…', () => openBag()], ['Display…', showDisplay], ['Settings…', showSettings], ['Charts & updates…', showCharts],
      ['Data: save, load, export, import…', showDataMenu], ['About', showAbout]]);
  };

  // ---- Data: snapshots in this browser, backup files, GPX -------------------------------------------------------------------------------
  const SNAP_KEY = 'chompchamp.planner.snapshots';
  const snapshots = () => { try { return JSON.parse(localStorage.getItem(SNAP_KEY) || '[]'); } catch (e) { return []; } };
  const PARTS = [['routes', 'Routes'], ['marks', 'Marks'], ['track', 'Current track'], ['trips', 'Saved trips & catch log']];
  const tripsForExport = () => state.trips.map((t) => ({ trip: t, pts: tripRaw(t.id) })).filter((x) => x.pts);
  async function pickParts(title, options, preset, labels = (p) => p[1]) {
    const a = await pickMany(title, options.map(labels), options.map((p) => preset.includes(p[0])), 'Continue');
    if (!a) return null;
    const parts = options.filter((p, i) => a[i]).map((p) => p[0]);
    if (!parts.length) { toast('Nothing selected'); return null; }
    return parts;
  }
  async function showDataMenu() {
    const items = [
      ['Save snapshot in this browser…', async () => {
        const name = await askText('Snapshot name', `Snapshot ${new Date().toLocaleString([], { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false })}`);
        if (!name) return;
        const list = snapshots().filter((s) => s.name !== name);
        list.unshift({ name, time: Date.now(), data: C.exportBackup(state, ['routes', 'marks', 'track']) });
        try { localStorage.setItem(SNAP_KEY, JSON.stringify(list.slice(0, 20))); toast(`Saved snapshot "${name}"`); } catch (e) { toast("This browser is full, so the snapshot couldn't be saved."); }
      }],
      ['Load snapshot…', showSnapshots],
      ['Export backup file (.json)…', async () => {
        const parts = await pickParts('Export backup', PARTS, PARTS.map((p) => p[0]));
        if (parts) download(`chompchamp-backup-${stamp()}.json`, C.exportBackup({ ...state, trips: tripsForExport() }, parts), 'application/json');
      }],
      ['Export GPX (marks, routes, track)…', async () => {
        const parts = await pickParts('Export GPX', PARTS.slice(0, 3), ['routes', 'marks']);
        if (parts) download(`chompchamp-${stamp()}.gpx`, C.exportGpx({ ...state, marks: parts.includes('marks') ? state.marks : [], routes: parts.includes('routes') ? state.routes : [],
          plan: parts.includes('routes') ? state.plan : C.emptyRoute(), track: parts.includes('track') ? state.track : [] }), 'application/gpx+xml');
      }],
      ['Import backup or GPX file…', () => pickFile(IOS ? '' : '.json,.gpx,application/json,application/gpx+xml', openAny)],
      ['Delete everything in this browser…', async () => {
        if (!await confirmBox('Delete my data', "Delete all routes, marks, trips, catches and the track from this browser? The charts stay. Export a backup first if you want them. This can't be undone.", 'Delete')) return;
        state.trips.forEach((t) => { try { localStorage.removeItem(TRIP_KEY + t.id); } catch (e) { /* gone */ } });
        Object.assign(state, { routes: [], marks: [], groups: [], track: [], catches: [], trips: [], anchor: null, shownTrip: null, recording: false, plan: C.emptyRoute() });
        C.tidyGroups(state.groups, []); undo.length = 0; planChanged(); showTripOnMap(null, [], false); recordingChanged(); drawData();
      }],
    ];
    const i = await pickList('Data', items.map((x) => x[0]));
    if (i !== null) items[i][1]();
  }
  async function showSnapshots() {
    const list = snapshots();
    if (!list.length) { toast('No snapshots yet'); return; }
    const i = await pickList('Snapshots', list.map((s) => el('span.two', {}, el('span', {}, s.name), el('span.sub', {}, timeText(s.time)))));
    if (i === null) return;
    const s = list[i];
    const a = await dialog(s.name, 'Loading replaces the routes, marks and current track in this browser with this snapshot. Saved trips and the catch log stay as they are.',
      [['Delete', 'del', 'neutral'], ['Cancel', null], ['Load', 'load', 'primary']]);
    if (a === 'load') applyBundle(C.parseBackup(s.data), ['routes', 'marks', 'track'], false);
    if (a === 'del' && await confirmBox(null, `Delete snapshot "${s.name}"?`, 'Delete')) localStorage.setItem(SNAP_KEY, JSON.stringify(list.filter((x) => x !== s)));
  }

  // ---- Charts & updates ---------------------------------------------------------------------------------------------------------------
  async function showCharts() {
    const info = (kind, none) => (files[kind] ? `${files[kind].name} · ${mb(files[kind].size)}` : none);
    await dialog('Charts & updates', (close) => [
      el('p.msg', {}, `Chart: ${info('chart', 'not opened yet')}\nReef relief: ${info('relief', 'not opened: shows online instead (needs internet)')}`),
      el('p.small', {}, `The chart (wa.pmtiles) and reef relief (wa-relief.pmtiles) come with each ChompChamp release. They're kept in this browser, so you only open them once. A new chart needs opening again.`),
      actionList(close, [['Open .pmtiles file…', chooseChart], files.chart ? ['Remove the chart from this browser', () => removeFile('chart'), true] : null,
        files.relief ? ['Remove the reef relief from this browser', () => removeFile('relief'), true] : null,
        ['Latest release (charts and the Android app)', () => open('https://github.com/wufle/chompchamp/releases/latest', '_blank', 'noopener')]]),
      el('p.small', {}, (location.protocol === 'https:' ? 'This planner updates itself from the website when you\'re online.' : 'This planner is a file: get a newer one from the latest release, or use the website, which updates itself.') + ` Planner ${DATA.version}.`)],
    [['Close', null, 'primary']]);
  }
  function showAbout() {
    dialog('ChompChamp', [el('img.logo', { src: $('logo').src, alt: '', width: 64, height: 64 }),
      `Planner ${DATA.version}\nChart: ${files.chart ? `${files.chart.name} · ${mb(files.chart.size)}` : 'not opened'}\n\nFree offline fishing chartplotter for Western Australia. ` +
      'Source code, data credits and licences are on GitHub.',
      el('p.small', {}, 'Your routes, marks, trips and catches are kept in this browser only: clearing its data deletes them, so export a backup now and then.'),
      el('a.link', { href: 'https://github.com/wufle/chompchamp', target: '_blank', rel: 'noopener noreferrer' }, 'github.com/wufle/chompchamp'),
      el('br', {}), el('a.link', { href: 'https://wufle.github.io/chompchamp/', target: '_blank', rel: 'noopener noreferrer' }, 'wufle.github.io/chompchamp (with GPS, for phones)')],
    [['Close', null, 'primary']]);
  }
