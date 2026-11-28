  // ---- The app's building blocks: icons, dialogs, popup menus, the docked panel, messages -------------------------
  const SVG = 'http://www.w3.org/2000/svg';
  /** One of the app's own icons (res/drawable, 24 x 24). */
  function icon(name, size = 24) {
    const s = document.createElementNS(SVG, 'svg');
    s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('width', size); s.setAttribute('height', size); s.setAttribute('aria-hidden', 'true');
    for (const [d, evenOdd] of DATA.icons[name] || []) {
      const p = document.createElementNS(SVG, 'path');
      p.setAttribute('d', d); p.setAttribute('fill', 'currentColor');
      if (evenOdd) p.setAttribute('fill-rule', 'evenodd');
      s.append(p);
    }
    return s;
  }

  /**
   * A dialog like MaterialAlertDialog. [body]: text, nodes, or (close) => nodes for rows that close it themselves.
   * [buttons]: [[label, value, kind]], kind 'primary' | 'danger' | 'neutral' (sits on the left). Resolves to the
   * value, or null when dismissed. Several can be open at once, each over the last.
   */
  function dialog(title, body, buttons = [['OK', true, 'primary']], { dismissable = true, cls = '' } = {}) {
    return new Promise((resolve) => {
      const d = el(`dialog.dlg${cls ? `.${cls}` : ''}`, {});
      let done = false;
      const close = (v) => { if (done) return; done = true; d.close(); d.remove(); resolve(v === undefined ? null : v); };
      const kids = (typeof body === 'function' ? body(close) : [body]).flat().filter((x) => x !== null && x !== undefined && x !== false && x !== '')
        .map((b) => (typeof b === 'string' ? el('p.msg', {}, b) : b));
      const btn = ([label, v, kind = '']) => el(`button.tb${kind ? `.${kind}` : ''}`, { type: 'button', onclick: () => close(v) }, label);
      const left = buttons.filter((b) => b[2] === 'neutral'), right = buttons.filter((b) => b[2] !== 'neutral');
      d.append(title ? el('h2', {}, title) : '', el('div.body', {}, kids),
        buttons.length ? el('div.acts', {}, left.map(btn), el('span.grow', {}), right.map(btn)) : '');
      d.addEventListener('cancel', (e) => { e.preventDefault(); if (dismissable) close(null); });
      // Opened by holding the map: the finger lifting mustn't also press what's now under it.
      d.addEventListener('click', (e) => { if (Date.now() < menuGuard) { e.stopPropagation(); e.preventDefault(); } }, true);
      // A tap outside the box closes it, as on the phone.
      d.addEventListener('pointerdown', (e) => { if (e.target === d && dismissable) { const r = d.getBoundingClientRect(); if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) close(null); } });
      // On a touch screen nothing is focused, as on the phone: no keyboard popping up, and no button a stray key could press.
      if (TOUCH) { d.autofocus = true; d.tabIndex = -1; }
      document.body.append(d);
      d.showModal();
      if (!TOUCH) (d.querySelector('input.line, textarea, input.search') || d.querySelector('.acts .primary') || d.querySelector('.acts button:last-child'))?.focus();
    });
  }
  const confirmBox = (title, text, yes = 'OK', no = 'Cancel') => dialog(title, text, [[no, null], [yes, true, 'primary']]);
  /** The text typed, or null if cancelled. */
  async function askText(title, value = '', placeholder = '') {
    const field = el('input.line', { type: 'text', value, placeholder, ariaLabel: title, autocomplete: 'off' });
    field.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); field.closest('dialog').querySelector('.acts .primary').click(); } });
    setTimeout(() => field.select(), 30);
    const ok = await dialog(title, [field], [['Cancel', null], ['OK', true, 'primary']]);
    return ok ? field.value.trim() : null;
  }
  /** A list to pick from (setItems). Labels may be text or nodes. -> index, or null. [extra]: more buttons, resolving to their value. */
  function pickList(title, labels, extra = [], cancel = 'Cancel') {
    return dialog(title, (close) => [el('div.items', {}, labels.map((l, i) => el('button.item', { type: 'button', onclick: () => close(i) }, l)))],
      [...extra, ...(cancel ? [[cancel, null]] : [])]);
  }
  /** One of several, with the current one ticked (setSingleChoiceItems). Closes on a tap. -> index, or null. */
  function pickOne(title, labels, current) {
    return dialog(title, (close) => [el('div.items', {}, labels.map((l, i) => el('button.item.radio', { type: 'button', ariaPressed: String(i === current), onclick: () => close(i) },
      el('span.rb', {}), el('span', {}, l))))], [['Cancel', null]]);
  }
  /** Ticks (setMultiChoiceItems). -> array of booleans, or null. [all] adds an "All" button that ticks or clears them. */
  async function pickMany(title, labels, checked, ok = 'OK', all = false) {
    const boxes = labels.map((l, i) => el('input', { type: 'checkbox', checked: !!checked[i] }));
    const body = el('div.items', {}, labels.map((l, i) => el('label.item.check', {}, boxes[i], el('span', {}, l))));
    if (all) body.prepend(el('button.item.allbtn', { type: 'button', onclick: () => { const on = boxes.some((b) => !b.checked); boxes.forEach((b) => { b.checked = on; }); } }, 'All / none'));
    const a = await dialog(title, [body], [['Cancel', null], [ok, true, 'primary']]);
    return a ? boxes.map((b) => b.checked) : null;
  }
  /** Borderless accent buttons down a dialog, like the mark and catch menus. Each closes it, then runs. */
  const actionList = (close, items) => el('div.alist', {}, items.filter(Boolean).map(([label, fn, danger]) =>
    el(`button.ab${danger ? '.danger' : ''}`, { type: 'button', onclick: () => { close(null); fn(); } }, label)));

  // ---- Popup menu (PopupMenu): under the button that opened it ------------------------------------------------------
  let menuGuard = 0;   // the touch that opened the menu mustn't also pick from it
  function popup(anchor, items, at = null) {
    const m = $('menu');
    m.replaceChildren(...items.filter(Boolean).map(([label, fn]) => el('button', { role: 'menuitem', type: 'button', onclick: () => { if (Date.now() < menuGuard) return; closeMenu(); fn(); } }, label)));
    m.hidden = false;
    const r = anchor ? anchor.getBoundingClientRect() : { left: at.x, right: at.x, top: at.y, bottom: at.y };
    const w = m.offsetWidth, h = m.offsetHeight;
    let x = anchor && r.left + w > innerWidth - 8 ? r.right - w : r.left, y = r.bottom + 4;
    if (y + h > innerHeight - 8) y = Math.max(8, (anchor ? r.top - 4 : r.top) - h);
    m.style.left = `${Math.max(8, Math.min(x, innerWidth - w - 8))}px`; m.style.top = `${y}px`;
    if (!TOUCH) m.querySelector('button')?.focus();
  }
  const closeMenu = () => { $('menu').hidden = true; };
  addEventListener('pointerdown', (e) => { if (!$('menu').hidden && !$('menu').contains(e.target) && Date.now() > menuGuard) closeMenu(); }, true);

  // ---- Messages along the bottom (Snackbar / Toast) -------------------------------------------------------------------
  let snackTimer = 0;
  function snack(text, action = null, ms = 4500) {
    const s = $('snack');
    s.replaceChildren(el('span', {}, text), action ? el('button.tb', { type: 'button', onclick: () => { s.hidden = true; action[1](); } }, action[0]) : '');
    s.hidden = false;
    clearTimeout(snackTimer); snackTimer = setTimeout(() => { s.hidden = true; }, action ? Math.max(ms, 6000) : ms);
  }
  const toast = (text) => snack(text);

  // ---- The docked panel (SettingsPanel): over the bottom of the map, no scrim, so changes show live ----------------
  const panel = {
    current: null, onClose: null,
    get isOpen() { return !$('panel').hidden; },
    /** [build](rows) fills it; [header] stays above the scrolling rows (a search box). */
    show(title, build, { onClose = null, header = null } = {}) {
      const prev = this.onClose; this.onClose = null; if (prev) prev();
      this.onClose = onClose; this.current = title;
      const p = $('panel'), list = el('div.prows', {});
      const keepScroll = p.dataset.title === title ? p.querySelector('.prows')?.scrollTop || 0 : 0;
      build(rows(list));
      p.dataset.title = title;
      p.replaceChildren(el('div.phead', {}, el('h2', {}, title), el('button.x', { type: 'button', ariaLabel: 'Close', title: 'Close', onclick: () => this.hide() }, icon('close'))),
        header || '', list);
      list.scrollTop = keepScroll;
      const was = !p.hidden;
      p.hidden = false;
      if (!was) panelChanged();
    },
    hide() {
      if (!this.isOpen) return;
      this.current = null;
      $('panel').hidden = true; $('panel').replaceChildren(); delete $('panel').dataset.title;
      const f = this.onClose; this.onClose = null; if (f) f();
      panelChanged();
    },
  };
  /** Builders for the compact one-line rows every panel uses. */
  function rows(parent) {
    const add = (n) => { parent.append(n); return n; };
    return {
      section: (title) => add(el('h3.sec', {}, title)),
      note: (text) => add(el('p.note', {}, text)),
      text: (text) => add(el('p.ptext', {}, text)),
      view: (n) => add(n),
      button: (text, fn) => add(el('button.tb.pbtn', { type: 'button', onclick: fn }, text)),
      /** Label ........ (value). The whole row is a button. */
      actionRow: (label, value, fn) => add(el('button.arow', { type: 'button', onclick: fn }, el('span.lbl', {}, label), value !== null && value !== '' ? el('span.pill', {}, value) : '')),
      /** [switch] ● Name ....... 12 ›   The switch shows or hides it; the rest of the row opens it. */
      groupRow: (name, count, color, checked, onChecked, onOpen) => add(el('div.grow-row', {},
        toggle(checked, onChecked, `Show ${name} on the map`),
        el('button.gopen', { type: 'button', onclick: onOpen }, el('span.dot', { style: `background:${color}` }), el('span.lbl', {}, name), el('span.cnt', {}, `${count}  ›`)))),
      /** [switch?] Label ....... [slider] 80%. Whole percents in steps of 5. */
      sliderRow({ label, pct, from = 0, to = 100, checked = null, onChecked = () => {}, onPct }) {
        const val = el('span.pv', {}, `${pct}%`), name = el('span.lbl', {}, label);
        const sl = el('input.slider', { type: 'range', min: from, max: to, step: 5, value: Math.round(pct / 5) * 5, ariaLabel: label });
        paintSlider(sl);
        sl.addEventListener('input', () => { val.textContent = `${sl.value}%`; onPct(Number(sl.value)); });
        const row = add(el('div.srow', {}, checked === null ? '' : toggle(checked, (on) => { row.classList.toggle('off', !on); onChecked(on); }, label), name, sl, val));
        if (checked === false) row.classList.add('off');
        return sl;
      },
    };
  }
  /** The filled part of a slider's track. */
  function paintSlider(sl) { const min = Number(sl.min) || 0, max = Number(sl.max) || 100; sl.style.setProperty('--p', `${((Number(sl.value) - min) / (max - min || 1)) * 100}%`); return sl; }
  addEventListener('input', (e) => { if (e.target.classList && e.target.classList.contains('slider')) paintSlider(e.target); }, true);
  /** A Material switch. */
  function toggle(checked, onChange, label) {
    const i = el('input.sw', { type: 'checkbox', role: 'switch', checked, ariaLabel: label });
    i.addEventListener('change', () => onChange(i.checked));
    return i;
  }
