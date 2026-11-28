  // ---- Data fields (DataFields.kt): live values in cards over the map. Each mode has its own set and layout. ------------
  const MODES = [['chart', 'Chart'], ['route', 'Route'], ['nav', 'Navigate'], ['fish', 'Fish']];
  const modeLabel = (m) => (MODES.find((x) => x[0] === m) || MODES[0])[1];
  const FIELDS = [
    ['sog', 'SOG  kn', ['chart', 'nav']], ['sog_kmh', 'SOG  km/h', []], ['cog', 'COG  °T', ['chart']], ['pos', 'POSITION', []],
    ['depth', 'DEPTH  m (est.)', ['chart', 'fish']], ['wpt', 'WAYPOINT', ['nav']], ['brg', 'BRG  °T', ['nav']], ['dtw', 'DTW  nm', ['nav']],
    ['xte', 'XTE  nm', ['nav']], ['vmg', 'VMG  kn', []], ['ttg', 'TTG', []], ['eta_wpt', 'ETA WPT', []], ['eta_dest', 'ETA DEST', ['nav']],
    ['remain', 'REMAIN  nm', []], ['drift', 'DRIFT  kn', ['fish']], ['drift_dir', 'DRIFT  °T', ['fish']], ['mark', 'NEAREST MARK', ['fish']],
    ['anchor', 'ANCHOR  m', []], ['trip', 'TRIP  nm', []], ['trip_time', 'TRIP TIME', []], ['trip_avg', 'TRIP AVG  kn', []],
    ['trip_max', 'TRIP MAX  kn', []], ['time', 'TIME', []], ['wind', 'WIND  kn (forecast)', []], ['tide', 'TIDE  m (est.)', []],
    ['waves', 'SEA / SWELL  m', []], ['depth_now', 'DEPTH NOW  m (est.)', []],
  ];
  const placeholder = (k) => (['sog', 'sog_kmh', 'vmg', 'drift', 'trip_avg', 'trip_max'].includes(k) ? '--.-' : ['cog', 'brg', 'drift_dir'].includes(k) ? '---°' : k === 'pos' ? 'No GPS fix' : '--');
  const GROUPINGS = [[0, 'Separate', 'Separate cards (drag each one)', 0], [1, 'Column', 'One group: single column', 1],
    [2, 'Grid × 2', 'One group: grid, 2 across', 2], [3, 'Grid × 3', 'One group: grid, 3 across', 3], [99, 'Row', 'One group: single row', 99]];

  const fields = {
    editing: false,
    cards: new Map(),
    cfg() { const m = state.mode; state.fields[m] = state.fields[m] || {}; return state.fields[m]; },
    isVisible(k) { const v = this.cfg().vis || {}; return v[k] === undefined ? FIELDS.find((f) => f[0] === k)[2].includes(state.mode) : v[k]; },
    setVisible(k, on) { const c = this.cfg(); c.vis = { ...(c.vis || {}), [k]: on }; save(); this.arrange(); },
    get grouping() { const g = this.cfg().group; return GROUPINGS.find((x) => x[0] === (g === undefined ? 1 : g)) || GROUPINGS[0]; },
    set grouping(g) { this.cfg().group = g[0]; save(); this.arrange(); },
    get grouped() { return this.grouping[0] !== 0; },
    size(k) { return (this.cfg().size || {})[k] || 1; },
    setSize(k, v) { const c = this.cfg(); c.size = { ...(c.size || {}), [k]: v }; save(); this.style(); },
    resetLayout() { const c = this.cfg(); delete c.pos; delete c.gpos; delete c.size; save(); this.style(); },
    set(k, text) { const c = this.cards.get(k); if (c && c.value.textContent !== text) c.value.textContent = text; },

    /** Visible cards go loose on the map, or into one group, in list order. */
    arrange() {
      const box = $('fields');
      box.replaceChildren();
      const vis = FIELDS.filter(([k]) => this.isVisible(k));
      for (const [k, label] of FIELDS) {
        if (!this.cards.has(k)) {
          const value = el('span.fv', {}, placeholder(k));
          this.cards.set(k, { root: el('div.fcard', { dataset: { k } }, el('span.fl', {}, label), value), value });
        }
      }
      if (this.grouped) {
        const n = vis.length, g = this.grouping[3];
        const group = el('div.fgroup', {});
        group.style.gridTemplateColumns = `repeat(${Math.max(1, Math.min(g >= 99 ? n : g, Math.max(1, n)))}, auto)`;
        vis.forEach(([k]) => group.append(this.cards.get(k).root));
        if (n) box.append(group);
      } else vis.forEach(([k]) => box.append(this.cards.get(k).root));
      this.style();
    },

    /** Sizes, colours and see-through backgrounds, from Display. */
    style() {
      const L = look(), p = Math.max(2, 6 * L.field_scale), bg = (a, r) => `rgba(16,28,43,${(this.editing ? Math.max(a, 0.6) : a).toFixed(3)})`;
      const shadow = L.field_alpha < 0.4 ? '0 1px 4px #101C2B' : 'none';
      for (const [k, c] of this.cards) {
        const s = L.field_scale * this.size(k), small = ['pos', 'wpt', 'mark'].includes(k);
        c.root.style.cssText = `padding:${p}px ${p * 2}px ${p * 1.5}px;${this.grouped ? '' : `background:${bg(L.field_alpha)};border-radius:10px;`}` +
          (this.editing && !this.grouped ? 'outline:2px dashed #8EC1E6;outline-offset:-2px;' : '');
        c.root.firstChild.style.cssText = `font-size:${9.5 * s}px;text-shadow:${shadow}`;
        c.value.style.cssText = `font-size:${(small ? 13 : 22) * s}px;text-shadow:${shadow}`;
      }
      const g = $('fields').querySelector('.fgroup');
      if (g) g.style.cssText += `;padding:${p / 2}px ${p}px;background:${bg(L.field_alpha)};border-radius:12px;` +
        (this.editing ? 'outline:2px dashed #8EC1E6;outline-offset:-2px;' : 'outline:none;');
      $('fields').classList.toggle('editing', this.editing);
      requestAnimationFrame(() => this.place());
    },

    /** Saved spots are fractions of the free space, so a layout survives turning the phone. */
    place() {
      const box = $('fields'), W = box.clientWidth, H = box.clientHeight;
      if (!W) return;
      const top = 12 + 44 * look().control_scale + 8, c = this.cfg();
      const free = (v) => [Math.max(0, W - v.offsetWidth), Math.max(0, H - v.offsetHeight)];
      const put = (v, x, y) => { v.style.left = `${x}px`; v.style.top = `${y}px`; };
      const g = box.querySelector('.fgroup');
      if (g) {
        const [fx, fy] = free(g);
        put(g, c.gpos ? c.gpos[0] * fx : 0, c.gpos ? c.gpos[1] * fy : Math.min(top, fy));
        return;
      }
      let sx = 0, sy = top, colW = 0;
      for (const v of box.children) {
        const k = v.dataset.k, [fx, fy] = free(v), at = (c.pos || {})[k];
        if (at) { put(v, at[0] * fx, at[1] * fy); continue; }
        if (sy + v.offsetHeight > H - 96 && sy > top) { sx += colW + 6; sy = top; colW = 0; }
        put(v, Math.min(sx, fx), Math.min(sy, fy));
        sy += v.offsetHeight + 6; colW = Math.max(colW, v.offsetWidth);
      }
    },

    setEditing(on) {
      this.editing = on;
      $('editbar').hidden = !on;
      $('btn-mode').hidden = on;
      $('rec').classList.toggle('editing', on); $('btn-bag').classList.toggle('editing', on);
      this.style();
    },
  };
  addEventListener('resize', () => fields.place());

  // Edit layout: drag a card (or the group) to move it, tap one to size or hide it.
  {
    let drag = null;
    $('fields').addEventListener('pointerdown', (e) => {
      if (!fields.editing) return;
      const card = e.target.closest('.fcard');
      if (!card) return;
      const v = fields.grouped ? card.closest('.fgroup') : card;
      e.preventDefault();
      v.setPointerCapture(e.pointerId);
      drag = { v, k: card.dataset.k, x0: e.clientX, y0: e.clientY, l: v.offsetLeft, t: v.offsetTop, moved: false };
    });
    $('fields').addEventListener('pointermove', (e) => {
      if (!drag) return;
      const dx = e.clientX - drag.x0, dy = e.clientY - drag.y0;
      if (!drag.moved && Math.hypot(dx, dy) < 6) return;
      drag.moved = true;
      const box = $('fields'), fx = Math.max(0, box.clientWidth - drag.v.offsetWidth), fy = Math.max(0, box.clientHeight - drag.v.offsetHeight);
      drag.v.style.left = `${Math.max(0, Math.min(fx, drag.l + dx))}px`; drag.v.style.top = `${Math.max(0, Math.min(fy, drag.t + dy))}px`;
    });
    const end = () => {
      if (!drag) return;
      const { v, k, moved } = drag; drag = null;
      if (!moved) { fieldOptions(k); return; }
      const box = $('fields'), fx = Math.max(1, box.clientWidth - v.offsetWidth), fy = Math.max(1, box.clientHeight - v.offsetHeight);
      const at = [v.offsetLeft / fx, v.offsetTop / fy], c = fields.cfg();
      if (fields.grouped) c.gpos = at; else c.pos = { ...(c.pos || {}), [k]: at };
      save();
    };
    $('fields').addEventListener('pointerup', end);
    $('fields').addEventListener('pointercancel', end);
  }
  /** Tapping a field in Edit layout: size or hide just that field. */
  async function fieldOptions(k) {
    const sizes = [[0.75, 'Small'], [1, 'Normal'], [1.35, 'Large'], [1.75, 'Extra large']];
    const cur = Math.max(0, sizes.findIndex((s) => s[0] === fields.size(k)));
    const label = FIELDS.find((f) => f[0] === k)[1].replace('  ', ' ');
    const a = await dialog(label, (close) => [el('div.items', {}, sizes.map(([v, l], i) => el('button.item.radio', { type: 'button', ariaPressed: String(i === cur), onclick: () => close(v) }, el('span.rb', {}), el('span', {}, l))))],
      [['Hide field', 'hide', 'neutral'], ['OK', null, 'primary']]);
    if (a === 'hide') fields.setVisible(k, false);
    else if (typeof a === 'number') fields.setSize(k, a);
  }
  $('done-editing').onclick = () => fields.setEditing(false);
