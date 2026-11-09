  /* <ios-kit> shared iOS controls of the concept (the same code in every artboard) */
  // slider: drag anywhere (relative, no jump), tap jumps, double tap resets; rubber band past the ends, glass knob while held, spring on release
  iosSlider(id, k, onK, o) {
    o = o || {};
    const st = this.state, ACC = o.acc || '#0A84FF', SPR = 'cubic-bezier(.34,1.56,.64,1)';
    const clamp = (x) => Math.max(0, Math.min(1, x));
    const d = st.drag && st.drag.id === id ? st.drag : null;
    const n = (st.bump && st.bump[id]) || 0;
    const pos = 'calc(14px + ' + clamp(k).toFixed(4) + ' * (100% - 28px))', over = d ? d.over : 0, live = !!(d && d.moving);
    const w = (this._sl && this._sl.id === id && this._sl.w) || 300;
    const bump = () => { const b = { ...(this.state.bump || {}) }; b[id] = (b[id] || 0) + 1; this.setState({ drag: null, bump: b });
      clearTimeout(this['_b' + id]); this['_b' + id] = setTimeout(() => { const c = { ...(this.state.bump || {}) }; delete c[id]; this.setState({ bump: c }); }, 700); };
    const end = (e, cancel) => { const s = this._sl; if (!s || s.id !== id) return; this._sl = null;
      if (!cancel && !s.moved && e && e.clientX != null) onK(clamp((e.clientX - s.r.left - 14) / Math.max(1, s.r.width - 28)));
      bump(); };
    const anim = (a, b) => n ? '; animation: ' + (n % 2 ? a : b) + ' .55s ' + SPR : '';
    return {
      down: (e) => { const el = e.currentTarget, r = el.getBoundingClientRect(); try { el.setPointerCapture(e.pointerId); } catch (x) { /* old browsers */ }
        this._sl = { id, r, w: Math.max(1, (r.width || 300) - 28), x0: e.clientX, k0: clamp(k), moved: false }; this.setState({ drag: { id, over: 0, moving: false } }); },
      move: (e) => { const s = this._sl; if (!s || s.id !== id) return; const dx = e.clientX - s.x0;
        if (!s.moved && Math.abs(dx) < 4) return; s.moved = true;
        const raw = s.k0 + dx / s.w, ex = raw < 0 ? raw * s.w : raw > 1 ? (raw - 1) * s.w : 0;
        onK(clamp(raw)); this.setState({ drag: { id, over: Math.sign(ex) * 20 * (1 - Math.exp(-Math.abs(ex) / 70)), moving: true } }); },
      up: (e) => end(e, false), cancel: (e) => end(e, true),
      reset: () => { if (o.def != null) { onK(o.def); bump(); } },
      key: (e) => { onK(clamp(Number(e.target.value) / 1000)); },
      k1000: Math.round(clamp(k) * 1000),
      wrap: 'position: relative; height: 38px; touch-action: pan-y; cursor: pointer; user-select: none; -webkit-user-select: none',
      track: 'position: absolute; left: 0; right: 0; top: 50%; height: ' + (d ? 9 : 6) + 'px; margin-top: ' + (d ? -4.5 : -3) + 'px; border-radius: 5px; overflow: hidden; background: rgba(120,120,128,.36); transform-origin: '
        + (over < 0 ? 'right' : 'left') + '; transform: ' + (over ? 'scaleX(' + (1 + Math.abs(over) / w).toFixed(4) + ')' : 'none')
        + '; transition: ' + (live ? 'height .25s ' + SPR + ', margin .25s ' + SPR : 'transform .6s ' + SPR + ', height .35s ' + SPR + ', margin .35s ' + SPR),
      fill: 'height: 100%; width: ' + pos + '; background: ' + ACC + '; transition: width ' + (live ? '.05s linear' : '.5s ' + SPR),
      knob: 'position: absolute; top: 50%; left: ' + pos + '; width: ' + (d ? 42 : 28) + 'px; height: ' + (d ? 26 : 28) + 'px; margin: ' + (d ? '-13px 0 0 -21px' : '-14px 0 0 -14px')
        + '; border-radius: 14px; pointer-events: none; translate: ' + (over * .9).toFixed(1) + 'px 0; '
        + (d ? 'background: rgba(255,255,255,.18); -webkit-backdrop-filter: blur(2px) saturate(2); backdrop-filter: blur(2px) saturate(2); box-shadow: inset 0 0 0 1.5px rgba(255,255,255,.9), inset 0 -6px 10px rgba(255,255,255,.25), 0 8px 20px rgba(0,0,0,.35)'
          : 'background: #FFFFFF; box-shadow: 0 2px 8px rgba(0,0,0,.35), 0 0 0 .5px rgba(0,0,0,.05)')
        + '; transition: left ' + (live ? '.05s linear' : '.5s ' + SPR) + ', translate ' + (live ? '.05s linear' : '.6s ' + SPR) + ', width .4s ' + SPR + ', height .4s ' + SPR + ', margin .4s ' + SPR + ', background .25s, box-shadow .25s'
        + (d ? '' : anim('iosKnobA', 'iosKnobB')),
      value: (base) => base + '; display: inline-block; transform-origin: right center; transition: transform .45s ' + SPR + ', color .2s; transform: scale(' + (d ? 1.16 : 1) + ')'
        + (d ? '; color: ' + ACC : '') + (d ? '' : anim('iosPopA', 'iosPopB'))
    };
  }
  // segmented control with a sliding thumb
  iosSeg(items, cur, pick, o) {
    o = o || {};
    const k = items.length, i = Math.max(0, items.findIndex((x) => x[1] === cur)), SPR = 'cubic-bezier(.34,1.4,.64,1)';
    return {
      track: 'position: relative; display: grid; grid-template-columns: repeat(' + k + ', minmax(0, 1fr)); padding: 3px; border-radius: 20px; background: rgba(118,118,128,.24)' + (o.margin ? '; margin: ' + o.margin : ''),
      thumb: 'position: absolute; top: 3px; bottom: 3px; left: calc(3px + ' + i + ' * (100% - 6px) / ' + k + '); width: calc((100% - 6px) / ' + k + '); border-radius: 17px; background: ' + (o.thumb || '#636366')
        + '; box-shadow: 0 3px 8px rgba(0,0,0,.25), 0 0 0 .5px rgba(0,0,0,.04); transition: left .45s ' + SPR + '; pointer-events: none',
      segs: items.map(([label, v]) => ({ label, on: v === cur ? 'true' : 'false', pick: () => pick(v),
        style: 'position: relative; min-height: 34px; padding: 4px 6px; border: 0; background: transparent; cursor: pointer; font: inherit; font-size: 13px; line-height: 16px; font-weight: 600; transition: color .2s; color: '
          + (v === cur ? (o.onInk || '#FFFFFF') : 'rgba(235,235,245,.75)') }))
    };
  }
  // switch: the app's one switch (owner's mix of the concept and Material You); on, the theme's colour
  iosSwitch(on, o) {
    o = o || {};
    const A = o.acc || '#FFD60A', SPR = 'cubic-bezier(.34,1.4,.64,1)';
    const hex = (c) => [1, 3, 5].map((i) => parseInt(c.substr(i, 2), 16));
    const mix = (a, b, k) => { const x = hex(a), y = hex(b); return '#' + x.map((v, i) => Math.round(v + (y[i] - v) * k).toString(16).padStart(2, '0')).join(''); };
    return {
      track: 'position: relative; width: 51px; height: 31px; flex: none; box-sizing: border-box; border-radius: 16px; transition: background .25s, border-color .25s; border: .6px solid '
        + (on ? A : 'rgba(255,255,255,.13)') + '; background: ' + (on ? A : '#39393C'),
      knob: 'position: absolute; top: 50%; transform: translateY(-50%); border-radius: 50%; transition: all .35s ' + SPR + '; box-shadow: 0 2px 6px rgba(0,0,0,.3); '
        + (on ? 'left: 22px; width: 26.5px; height: 26.5px; background: ' + mix('#FFFFFF', A, .18) : 'left: 2.5px; width: 24px; height: 24px; background: rgba(255,255,255,.88)')
    };
  }
  /* </ios-kit> */
