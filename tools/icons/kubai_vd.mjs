#!/usr/bin/env node
// P570 (owner, 2026-11-09: «переделай все иконки внутри нашего приложения, сделай их красивыми, по возможности анимированными
// и интуитивно понятными», with the Kubai Icons style): Kubai icon definitions (the MIT library's format, tools/icons/kubai and
// tools/icons/scamera) to Android drawables. The core renders an icon to SVG at any point of its motion
// (renderSVG(def, {progress})); this file turns those frames into a VectorDrawable per pose and AnimatedVectorDrawables that
// morph between them, wrapped in an animated-selector (state_pressed / state_activated = the hover pose).
//
// SVG -> VectorDrawable: every group transform is baked into the path coordinates (arcs become four cubics each, so a
// path keeps one command structure in every frame); an outside outline (a 2-wide stroke masked by its own shape) is drawn
// before its fill, which then covers the inner half - the same picture without a mask; gradients in bounding-box units are
// placed on the frame's own bounding box; the drop shadow (a filter) becomes three soft translucent layers of the shapes
// it holds, 2 units lower; the top glow is left out. Opacity of groups is multiplied into the paths' alphas.
//
//   node tools/icons/kubai_vd.mjs <icon module> <resource name> <accent #hex> <out dir> [--static] [--preview dir]
//
// Writes <name>.xml (the selector, or the rest vector with --static), kb_<name>_rest.xml, kb_<name>_hover.xml,
// kb_<name>_in.xml, kb_<name>_out.xml; --preview writes <name>_rest.svg / <name>_hover.svg of the same shapes (checks).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const core = (f) => pathToFileURL(path.join(here, 'kubai/packages/core/src', f)).href;
const { renderSVG, parse } = await import(core('render.js'));
const { EASE } = await import(core('motion.js'));

// ---------- numbers and matrices ----------
const r3 = (n) => {const v=Math.round(n*1000)/1000;return Object.is(v,-0)?0:v;};
const mul = (a, b) => [a[0]*b[0]+a[2]*b[1], a[1]*b[0]+a[3]*b[1], a[0]*b[2]+a[2]*b[3], a[1]*b[2]+a[3]*b[3], a[0]*b[4]+a[2]*b[5]+a[4], a[1]*b[4]+a[3]*b[5]+a[5]];
const ID = [1, 0, 0, 1, 0, 0];
const ap = (m, x, y) => [m[0]*x+m[2]*y+m[4], m[1]*x+m[3]*y+m[5]];
function parseTransform(s) {
  let m = ID;
  if (!s) return m;
  for (const t of s.matchAll(/(\w+)\s*\(([^)]*)\)/g)) {
    const a = t[2].split(/[\s,]+/).filter(Boolean).map(Number);
    let n = ID;
    switch (t[1]) {
      case 'translate': n = [1, 0, 0, 1, a[0], a[1] || 0]; break;
      case 'scale': n = [a[0], 0, 0, a.length > 1 ? a[1] : a[0], 0, 0]; break;
      case 'rotate': {
        const r = a[0] * Math.PI / 180, c = Math.cos(r), sn = Math.sin(r);
        n = [c, sn, -sn, c, 0, 0];
        if (a.length > 2) n = mul(mul([1, 0, 0, 1, a[1], a[2]], n), [1, 0, 0, 1, -a[1], -a[2]]);
        break;
      }
      case 'matrix': n = a.slice(0, 6); break;
      case 'skewX': n = [1, 0, Math.tan(a[0] * Math.PI / 180), 1, 0, 0]; break;
      case 'skewY': n = [1, Math.tan(a[0] * Math.PI / 180), 0, 1, 0, 0]; break;
      default: throw new Error('transform ' + t[1]);
    }
    m = mul(m, n);
  }
  return m;
}
const scaleOf = (m) => Math.sqrt(Math.abs(m[0]*m[3]-m[1]*m[2]));

// ---------- path data: absolute M / L / C / Q / Z ----------
function tokens(d) { return d.match(/[a-zA-Z]|-?(?:\d+\.?\d*|\.\d+)(?:e[-+]?\d+)?/gi) || []; }
function arcToCubics(x1, y1, rx, ry, phi, fa, fs, x2, y2) {
  // the SVG arc as exactly four cubics (a fixed structure for morphing); a degenerate arc as four straight cubics
  const out = [];
  if ((x1 === x2 && y1 === y2) || !rx || !ry) {
    for (let i = 1; i <= 4; i++) { const t0 = (i - 1) / 4, t1 = i / 4;
      const p = (t) => [x1 + (x2 - x1) * t, y1 + (y2 - y1) * t];
      out.push([...p(t0 + (t1 - t0) / 3), ...p(t0 + 2 * (t1 - t0) / 3), ...p(t1)]); }
    return out;
  }
  rx = Math.abs(rx); ry = Math.abs(ry);
  const r = phi * Math.PI / 180, c = Math.cos(r), s = Math.sin(r);
  const dx = (x1 - x2) / 2, dy = (y1 - y2) / 2;
  const xp = c * dx + s * dy, yp = -s * dx + c * dy;
  let l = xp * xp / (rx * rx) + yp * yp / (ry * ry);
  if (l > 1) { rx *= Math.sqrt(l); ry *= Math.sqrt(l); }
  const num = rx*rx*ry*ry - rx*rx*yp*yp - ry*ry*xp*xp, den = rx*rx*yp*yp + ry*ry*xp*xp;
  let k = Math.sqrt(Math.max(0, num / den)); if (fa === fs) k = -k;
  const cxp = k * rx * yp / ry, cyp = -k * ry * xp / rx;
  const cx = c * cxp - s * cyp + (x1 + x2) / 2, cy = s * cxp + c * cyp + (y1 + y2) / 2;
  const ang = (ux, uy, vx, vy) => { const a = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a; };
  const t1 = ang(1, 0, (xp - cxp) / rx, (yp - cyp) / ry);
  let dt = ang((xp - cxp) / rx, (yp - cyp) / ry, (-xp - cxp) / rx, (-yp - cyp) / ry);
  if (!fs && dt > 0) dt -= 2 * Math.PI; else if (fs && dt < 0) dt += 2 * Math.PI;
  const seg = dt / 4, kk = 4 / 3 * Math.tan(seg / 4);
  const pt = (t) => [cx + rx * Math.cos(t) * c - ry * Math.sin(t) * s, cy + rx * Math.cos(t) * s + ry * Math.sin(t) * c];
  const dv = (t) => [-rx * Math.sin(t) * c - ry * Math.cos(t) * s, -rx * Math.sin(t) * s + ry * Math.cos(t) * c];
  for (let i = 0; i < 4; i++) {
    const a0 = t1 + i * seg, a1 = a0 + seg;
    const p0 = pt(a0), p1 = pt(a1), d0 = dv(a0), d1 = dv(a1);
    out.push([p0[0] + kk * d0[0], p0[1] + kk * d0[1], p1[0] - kk * d1[0], p1[1] - kk * d1[1], ...(i === 3 ? [x2, y2] : p1)]);
  }
  return out;
}
/** Absolute segments [cmd, ...points] with the matrix applied. */
function normalize(d, m) {
  const t = tokens(d); let i = 0, cmd = '', x = 0, y = 0, sx = 0, sy = 0, lc = null, lq = null;
  const out = [];
  const num = () => Number(t[i++]);
  const P = (px, py) => ap(m, px, py);
  while (i < t.length) {
    if (/[a-zA-Z]/.test(t[i])) cmd = t[i++];
    const rel = cmd === cmd.toLowerCase(), C = cmd.toUpperCase();
    const bx = rel ? x : 0, by = rel ? y : 0;
    switch (C) {
      case 'M': x = bx + num(); y = by + num(); sx = x; sy = y; out.push(['M', ...P(x, y)]); cmd = rel ? 'l' : 'L'; lc = lq = null; break;
      case 'L': x = bx + num(); y = by + num(); out.push(['L', ...P(x, y)]); lc = lq = null; break;
      case 'H': x = bx + num(); out.push(['L', ...P(x, y)]); lc = lq = null; break;
      case 'V': y = (rel ? y : 0) + num(); out.push(['L', ...P(x, y)]); lc = lq = null; break;
      case 'C': { const a = [bx + num(), by + num(), bx + num(), by + num()]; x = bx + num(); y = by + num();
        out.push(['C', ...P(a[0], a[1]), ...P(a[2], a[3]), ...P(x, y)]); lc = [a[2], a[3]]; lq = null; break; }
      case 'S': { const c1 = lc ? [2 * x - lc[0], 2 * y - lc[1]] : [x, y]; const a = [bx + num(), by + num()]; x = bx + num(); y = by + num();
        out.push(['C', ...P(c1[0], c1[1]), ...P(a[0], a[1]), ...P(x, y)]); lc = a; lq = null; break; }
      case 'Q': { const a = [bx + num(), by + num()]; x = bx + num(); y = by + num();
        out.push(['Q', ...P(a[0], a[1]), ...P(x, y)]); lq = a; lc = null; break; }
      case 'T': { const a = lq ? [2 * x - lq[0], 2 * y - lq[1]] : [x, y]; x = bx + num(); y = by + num();
        out.push(['Q', ...P(a[0], a[1]), ...P(x, y)]); lq = a; lc = null; break; }
      case 'A': { const rx = num(), ry = num(), phi = num(), fa = num(), fsw = num(); const x2 = bx + num(), y2 = by + num();
        for (const c of arcToCubics(x, y, rx, ry, phi, fa, fsw, x2, y2)) out.push(['C', ...P(c[0], c[1]), ...P(c[2], c[3]), ...P(c[4], c[5])]);
        x = x2; y = y2; lc = lq = null; break; }
      case 'Z': out.push(['Z']); x = sx; y = sy; lc = lq = null; break;
      default: throw new Error('path command ' + cmd);
    }
  }
  return out;
}
const segsToD = (segs) => segs.map((s) => s[0] + s.slice(1).map(r3).join(' ')).join('');
const shape = (segs) => segs.map((s) => s[0] + (s.length - 1)).join('');
function bbox(segs) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const s of segs) for (let k = 1; k < s.length; k += 2) { x0 = Math.min(x0, s[k]); x1 = Math.max(x1, s[k]); y0 = Math.min(y0, s[k + 1]); y1 = Math.max(y1, s[k + 1]); }
  return [x0, y0, x1, y1];
}
const translateSegs = (segs, dx, dy) => segs.map((s) => [s[0], ...s.slice(1).map((v, k) => v + (k % 2 ? dy : dx))]);

// element geometry as path data (in its own coordinates)
function geometry(el) {
  const g = (k, v = 0) => Number(el.getAttribute(k) ?? v);
  switch (el.tag) {
    case 'path': return el.getAttribute('d') || '';
    case 'circle': { const cx = g('cx'), cy = g('cy'), r = g('r');
      return `M${cx - r} ${cy}A${r} ${r} 0 1 0 ${cx + r} ${cy}A${r} ${r} 0 1 0 ${cx - r} ${cy}Z`; }
    case 'ellipse': { const cx = g('cx'), cy = g('cy'), rx = g('rx'), ry = g('ry');
      return `M${cx - rx} ${cy}A${rx} ${ry} 0 1 0 ${cx + rx} ${cy}A${rx} ${ry} 0 1 0 ${cx - rx} ${cy}Z`; }
    case 'rect': { const x = g('x'), y = g('y'), w = g('width'), h = g('height'); let rx = Number(el.getAttribute('rx') ?? el.getAttribute('ry') ?? 0);
      rx = Math.min(rx, w / 2, h / 2);
      if (!rx) return `M${x} ${y}H${x + w}V${y + h}H${x}Z`;
      return `M${x + rx} ${y}H${x + w - rx}A${rx} ${rx} 0 0 1 ${x + w} ${y + rx}V${y + h - rx}A${rx} ${rx} 0 0 1 ${x + w - rx} ${y + h}H${x + rx}A${rx} ${rx} 0 0 1 ${x} ${y + h - rx}V${y + rx}A${rx} ${rx} 0 0 1 ${x + rx} ${y}Z`; }
    case 'line': return `M${g('x1')} ${g('y1')}L${g('x2')} ${g('y2')}`;
    case 'polyline': case 'polygon': { const p = (el.getAttribute('points') || '').trim().split(/[\s,]+/).map(Number); let d = '';
      for (let k = 0; k < p.length; k += 2) d += (k ? 'L' : 'M') + p[k] + ' ' + p[k + 1];
      return d + (el.tag === 'polygon' ? 'Z' : ''); }
    default: return null;
  }
}

// ---------- colours ----------
function color(v) {
  if (!v || v === 'none' || v === 'transparent') return null;
  if (v.startsWith('url(')) return { url: v.slice(5, -1) };
  let m;
  if ((m = /^#([0-9a-f]{3})$/i.exec(v))) return { hex: '#' + [...m[1]].map((c) => c + c).join('').toUpperCase() };
  if ((m = /^#([0-9a-f]{6})$/i.exec(v))) return { hex: '#' + m[1].toUpperCase() };
  if ((m = /^rgba?\(([^)]*)\)$/i.exec(v))) { const a = m[1].split(',').map((s) => s.trim());
    return { hex: '#' + a.slice(0, 3).map((n) => Math.round(Number(n)).toString(16).padStart(2, '0')).join('').toUpperCase(), alpha: a.length > 3 ? Number(a[3]) : 1 }; }
  if (v === 'white') return { hex: '#FFFFFF' };
  if (v === 'black') return { hex: '#000000' };
  throw new Error('colour ' + v);
}
const argb = (hex, a = 1) => '#' + Math.round(Math.max(0, Math.min(1, a)) * 255).toString(16).padStart(2, '0').toUpperCase() + hex.slice(1);

// ---------- one frame: the ordered drawing ops ----------
function frameOps(svg) {
  const tree = parse(svg).children[0];
  const defs = new Map();
  const collect = (n) => { for (const c of n.children) if (typeof c !== 'string') {
    const id = c.getAttribute('id'); if (id) defs.set(id, c); collect(c); } };
  collect(tree);
  const vb = tree.getAttribute('viewBox').split(/\s+/).map(Number);
  const base = [1, 0, 0, 1, -vb[0], -vb[1]];
  const ops = [];
  const warn = new Set();
  const inherit = ['fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin', 'fill-rule', 'stroke-opacity', 'fill-opacity'];
  function walk(node, m, style, opacity, shadow, clip) {
    for (const el of node.children) {
      if (typeof el === 'string') continue;
      if (['defs', 'mask', 'clipPath', 'linearGradient', 'radialGradient', 'filter', 'title'].includes(el.tag)) continue;
      if (el.getAttribute('display') === 'none' || el.getAttribute('visibility') === 'hidden') continue;
      const st = { ...style };
      for (const k of inherit) if (el.getAttribute(k) != null) st[k] = el.getAttribute(k);
      const op = opacity * Number(el.getAttribute('opacity') ?? 1);
      const mm = mul(m, parseTransform(el.getAttribute('transform')));
      const filter = el.getAttribute('filter');
      let sh = shadow;
      if (filter && /url\(#sh-/.test(filter)) sh = { id: ops.length + ':' + filter, color: shadowColor(defs, filter) };
      let cl = clip;
      const cp = el.getAttribute('clip-path');
      if (cp) {
        const def = defs.get(cp.slice(5, -1));
        if (def) {
          const cm = mul(mm, parseTransform(def.getAttribute('transform')));
          const segs = [];
          for (const c of def.children) if (typeof c !== 'string') { const g = geometry(c);
            if (g) segs.push(...normalize(g, mul(cm, parseTransform(c.getAttribute('transform'))))); }
          cl = [...(clip || []), segs];
        }
      }
      if (el.tag === 'g' || el.tag === 'svg' || el.tag === 'a') { walk(el, mm, st, op, sh, cl); continue; }
      if (el.tag === 'text') { warn.add('text'); continue; }
      const gd = geometry(el);
      if (gd == null) { warn.add(el.tag); continue; }
      const segs = normalize(gd, mm);
      if (!segs.length) continue;
      const fill = color(st.fill ?? (el.tag === 'line' || el.tag === 'polyline' ? 'none' : 'black'));
      const stroke = color(st.stroke);
      const mask = el.getAttribute('mask');
      const o = { segs, m: mm, local: gd, fill, stroke, sw: Number(st['stroke-width'] ?? 1) * scaleOf(mm), cap: st['stroke-linecap'], join: st['stroke-linejoin'],
        rule: st['fill-rule'], alpha: op, fa: Number(st['fill-opacity'] ?? 1), sa: Number(st['stroke-opacity'] ?? 1),
        outline: Boolean(mask && /url\(#ol-/.test(mask)), shadow: sh, clip: cl };
      if (el.getAttribute('stroke-dasharray')) warn.add('dash');
      ops.push(o);
    }
  }
  walk(tree, base, {}, 1, null, null);
  // an outside outline goes right before the fill of the same shape (the fill covers its inner half)
  const order = [];
  for (const o of ops) {
    if (o.outline) {
      const d = segsToD(o.segs);
      let at = -1;
      for (let k = order.length - 1; k >= 0; k--) if (!order[k].outline && order[k].fill && segsToD(order[k].segs) === d) { at = k; break; }
      if (at >= 0) { order.splice(at, 0, o); continue; }
    }
    order.push(o);
  }
  // resolve gradients on each op's own box
  for (const o of order) for (const k of ['fill', 'stroke']) {
    const c = o[k];
    if (!c || !c.url) continue;
    const g = defs.get(c.url);
    if (!g) { o[k] = null; continue; }
    const stops = g.children.filter((s) => typeof s !== 'string' && s.tag === 'stop').map((s) => {
      const sc = color(s.getAttribute('stop-color') || '#000');
      return { off: Number(s.getAttribute('offset') || 0), hex: sc.hex, a: Number(s.getAttribute('stop-opacity') ?? 1) * (sc.alpha ?? 1) };
    });
    const user = g.getAttribute('gradientUnits') === 'userSpaceOnUse';
    const num = (n, d) => { const v = g.getAttribute(n); if (v == null) return d; return v.endsWith('%') ? Number(v.slice(0, -1)) / 100 : Number(v); };
    let p1 = [num('x1', 0), num('y1', 0)], p2 = [num('x2', 1), num('y2', 0)];
    if (user) { p1 = ap(o.m, ...p1); p2 = ap(o.m, ...p2); }
    else { const b = bbox(normalize(o.local, ID)); const map = (p) => ap(o.m, b[0] + p[0] * (b[2] - b[0]), b[1] + p[1] * (b[3] - b[1])); p1 = map(p1); p2 = map(p2); }
    o[k] = { grad: { x1: p1[0], y1: p1[1], x2: p2[0], y2: p2[1], stops } };
  }
  return { ops: order, warn, size: vb[2] };
}
function shadowColor(defs, filter) {
  const f = defs.get(filter.slice(5, -1));
  const cms = f ? f.children.filter((c) => typeof c !== 'string' && c.tag === 'feColorMatrix') : [];
  const last = cms[cms.length - 1];
  if (!last) return { hex: '#8AB6FF', a: 0.21 };
  const v = last.getAttribute('values').split(/\s+/).map(Number);
  const h = (x) => Math.round(x * 255).toString(16).padStart(2, '0');
  return { hex: ('#' + h(v[4]) + h(v[9]) + h(v[14])).toUpperCase(), a: v[18] };
}

// ---------- the drawn layers (shadow layers first, before each shadowed run) ----------
const SHADOW = [{ w: 4.8, k: 0.18 }, { w: 2.4, k: 0.3 }, { w: 0, k: 0.52 }]; // ~ a 2-unit blur: soft rims and a core
function layers(frame) {
  const out = [];
  let last = null;
  frame.ops.forEach((o, idx) => {
    if (o.shadow && o.shadow.id !== last) {
      // the run of ops under this shadow filter: their silhouettes, 2 units lower
      const run = frame.ops.filter((p) => p.shadow && p.shadow.id === o.shadow.id && (p.fill || p.outline));
      for (const L of SHADOW) for (const p of run) {
        const segs = translateSegs(p.segs, 0, 2);
        out.push({ key: `s${idx}:${L.w}:${frame.ops.indexOf(p)}`, segs, clip: p.clip, fill: L.w ? null : { hex: o.shadow.color.hex }, fa: L.w ? 0 : o.shadow.color.a * L.k,
          stroke: L.w ? { hex: o.shadow.color.hex } : null, sw: L.w + (p.outline ? p.sw : 0), sa: o.shadow.color.a * L.k, alpha: p.alpha, join: 'round', cap: 'round', rule: p.rule });
      }
    }
    last = o.shadow ? o.shadow.id : null;
    if (o.outline) out.push({ key: `o${idx}`, segs: o.segs, clip: o.clip, fill: null, stroke: o.stroke, sw: o.sw, sa: o.sa, alpha: o.alpha, join: o.join || 'round', cap: o.cap, rule: o.rule });
    else out.push({ key: `p${idx}`, segs: o.segs, clip: o.clip, fill: o.fill, fa: o.fa, stroke: o.stroke, sw: o.sw, sa: o.sa, alpha: o.alpha, join: o.join, cap: o.cap, rule: o.rule });
  });
  return out;
}

// ---------- Material You (owner, 2026-11-09: «Стиль иконок сделай ближе к Material You») ----------
// The Kubai layers drawn flat and tonal: no drop shadow, no gradient, two tones of the icon's colour - the back pieces (with the
// outline around every piece) in the colour itself, the front pieces in its light tonal container; white details and other
// solid colours stay.
const hexRgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const rgbHex = (c) => '#' + c.map((v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('').toUpperCase();
const mixHex = (a, b, k) => { const x = hexRgb(a), y = hexRgb(b); return rgbHex(x.map((v, i) => v + (y[i] - v) * k)); };
export const TONAL = 0.6; // the front pieces: the colour 60 % of the way to white
function towardWhite(hex, accent) {
  // how far a colour is from the accent toward white (0 the accent, 1 white), on the channel the accent has most room on
  const c = hexRgb(hex), a = hexRgb(accent);
  let best = 0, k = 0;
  for (let i = 0; i < 3; i++) { const room = 255 - a[i]; if (room > best) { best = room; k = (c[i] - a[i]) / room; } }
  return best ? Math.max(0, Math.min(1, k)) : 0;
}
function flatColor(c, accent) {
  if (!c) return c;
  if (c.hex) return c;
  const st = c.grad.stops, n = st.length;
  const mean = rgbHex(hexRgb(st[0].hex).map((_, i) => st.reduce((s, q) => s + hexRgb(q.hex)[i], 0) / n));
  const a = st.reduce((s, q) => s + q.a, 0) / n;
  const tone = towardWhite(mean, accent) < 0.45 ? accent : mixHex(accent, '#FFFFFF', TONAL);
  return { hex: tone, a };
}
function materialize(ls, accent) {
  return ls.filter((L) => !L.key.startsWith('s')).map((L) => {
    const fill = flatColor(L.fill, accent), stroke = flatColor(L.stroke, accent);
    const fa = fill && fill.a != null ? (L.fa ?? 1) * fill.a : L.fa, sa = stroke && stroke.a != null ? (L.sa ?? 1) * stroke.a : L.sa;
    return { ...L, fill: fill && { hex: fill.hex }, stroke: stroke && { hex: stroke.hex }, fa, sa };
  });
}

// ---------- line (owner, 2026-11-09: «Сделай иконки более минималистичными») ----------
// The Kubai pieces as one-colour lines: every piece's outline stroked in the icon's colour, no fills, no shading; a white
// detail (a hole, a dial) becomes its own outline, a small one a solid dot; a stroke keeps its width. An "on" still (pose 1:
// a filled heart, a pinned pin) stays filled (the tonal style), the filled / outlined pair of SF Symbols and Material.
export const LINE_W = 1.9;
// 2 x area / perimeter over the segments' end points: about the width of a long bar
function thickness(segs) {
  let area = 0, per = 0;
  for (const sp of subpaths(segs)) {
    const p = sp.map((sg) => sg.slice(-2)).filter((q) => q.length === 2);
    for (let i = 0; i < p.length; i++) { const a = p[i], b = p[(i + 1) % p.length]; area += a[0] * b[1] - b[0] * a[1]; per += Math.hypot(b[0] - a[0], b[1] - a[1]); }
  }
  return per ? Math.abs(area) / per : 0;
}
const subpaths = (segs) => segs.reduce((a, sg) => { if (sg[0] === 'M' || !a.length) a.push([]); a[a.length - 1].push(sg); return a; }, []);
function linearize(ls, accent) {
  const out = [];
  // an outline is kept around a filled piece only: around a stroked line (a slash) it is the gap cut into what is under it
  const filled = new Set(ls.filter((L) => L.fill && !L.key.startsWith('o')).map((L) => segsToD(L.segs)));
  // a thin piece (a bar: a slash) drawn solid, without its outline: as an outline it would be two parallel lines
  const thin = new Set([...filled].filter((d) => { const L = ls.find((x) => segsToD(x.segs) === d); return thickness(L.segs) < 3.6; }));
  for (const L0 of ls) {
    if (L0.key.startsWith('s')) continue;
    // a one-colour gradient (the library's pure colour) is a solid colour
    const st = L0.fill && L0.fill.grad && L0.fill.grad.stops;
    const L = st && st.every((q) => q.hex === st[0].hex) ? { ...L0, fill: { hex: st[0].hex } } : L0;
    const d = segsToD(L.segs);
    const isWhite = (c) => c && c.hex === '#FFFFFF';
    if (L.key.startsWith('o') && (!filled.has(d) || thin.has(d))) continue;
    if (!L.key.startsWith('o') && L.fill && thin.has(d)) { out.push({ ...L, fill: { hex: accent }, fa: 1, stroke: null, sw: 0 }); continue; }
    if (L.key.startsWith('o')) { out.push({ ...L, fill: null, stroke: { hex: accent }, sw: LINE_W, sa: 1, join: 'round', cap: 'round' }); continue; }
    if (L.stroke && L.sw > 0 && !L.fill) { out.push({ ...L, stroke: { hex: accent }, sa: Math.min(1, L.sa ?? 1), cap: L.cap || 'round', join: L.join || 'round' }); continue; }
    const solid = L.fill && L.fill.hex && !L.fill.grad;
    if (solid) {
      // each sub-path by its size: a small one (a sprocket hole, a dot) solid - a ring that small is a blur at 24 dp -, a large
      // one its outline (a white one a little thinner: an inner detail)
      const small = [], large = [];
      for (const sp of subpaths(L.segs)) { const b = bbox(sp); (Math.max(b[2] - b[0], b[3] - b[1]) < (isWhite(L.fill) ? 3.4 : 7) ? small : large).push(...sp); }
      if (large.length) out.push({ ...L, key: L.key + 'l', segs: large, fill: null, stroke: { hex: accent }, sw: LINE_W * (isWhite(L.fill) ? 0.85 : 1), sa: 1, join: 'round', cap: 'round' });
      if (small.length) out.push({ ...L, key: L.key + 's', segs: small, fill: { hex: accent }, fa: 1, stroke: null, sw: 0 });
    }
    // a gradient fill without an outline of its own is a shading piece: dropped
  }
  return out;
}

// ---------- XML ----------
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
function gradXml(attr, g, ind) {
  return `${ind}<aapt:attr name="android:${attr}">\n${ind}    <gradient android:type="linear" android:startX="${r3(g.x1)}" android:startY="${r3(g.y1)}" android:endX="${r3(g.x2)}" android:endY="${r3(g.y2)}">\n`
    + g.stops.map((s) => `${ind}        <item android:offset="${r3(s.off)}" android:color="${argb(s.hex, s.a)}" />\n`).join('')
    + `${ind}    </gradient>\n${ind}</aapt:attr>\n`;
}
function pathXml(L, name, ind) {
  const a = [`android:name="${name}"`, `android:pathData="${segsToD(L.segs)}"`];
  const kids = [];
  if (L.fill && L.fill.hex) { a.push(`android:fillColor="${argb(L.fill.hex)}"`); }
  else if (L.fill && L.fill.grad) kids.push(gradXml('fillColor', L.fill.grad, ind + '    '));
  if (L.fill) a.push(`android:fillAlpha="${r3((L.fa ?? 1) * L.alpha)}"`);
  if (L.stroke && L.sw > 0) {
    if (L.stroke.hex) a.push(`android:strokeColor="${argb(L.stroke.hex)}"`); else if (L.stroke.grad) kids.push(gradXml('strokeColor', L.stroke.grad, ind + '    '));
    a.push(`android:strokeWidth="${r3(L.sw)}"`, `android:strokeAlpha="${r3((L.sa ?? 1) * L.alpha)}"`);
    if (L.cap) a.push(`android:strokeLineCap="${L.cap === 'butt' ? 'butt' : L.cap}"`);
    if (L.join) a.push(`android:strokeLineJoin="${L.join}"`);
  }
  if (L.rule === 'evenodd') a.push('android:fillType="evenOdd"');
  return kids.length ? `${ind}<path ${a.join(`\n${ind}    `)}>\n${kids.join('')}${ind}</path>\n` : `${ind}<path ${a.join(`\n${ind}    `)} />\n`;
}
function vectorXml(ls, size, comment) {
  let body = '';
  ls.forEach((L, k) => {
    if (L.clip && L.clip.length) {
      body += `    <group android:name="g${k}">\n`;
      L.clip.forEach((c, j) => { body += `        <clip-path android:name="c${k}_${j}" android:pathData="${segsToD(c)}" />\n`; });
      body += pathXml(L, `p${k}`, '        ') + '    </group>\n';
    } else body += pathXml(L, `p${k}`, '    ');
  });
  return `<?xml version="1.0" encoding="utf-8"?>\n<!-- ${comment} -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:aapt="http://schemas.android.com/aapt"\n`
    + `    android:width="24dp"\n    android:height="24dp"\n    android:viewportWidth="${size}"\n    android:viewportHeight="${size}">\n${body}</vector>\n`;
}
function previewSvg(ls, size) {
  let defs = '', body = '';
  ls.forEach((L, k) => {
    const g = (c, id) => { if (!c || !c.grad) return c ? c.hex : 'none'; const q = c.grad;
      defs += `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${q.x1}" y1="${q.y1}" x2="${q.x2}" y2="${q.y2}">${q.stops.map((s) => `<stop offset="${s.off}" stop-color="${s.hex}" stop-opacity="${s.a}"/>`).join('')}</linearGradient>`;
      return `url(#${id})`; };
    let clip = '';
    if (L.clip && L.clip.length) { defs += `<clipPath id="c${k}">` + L.clip.map((c) => `<path d="${segsToD(c)}"/>`).join('') + '</clipPath>'; clip = ` clip-path="url(#c${k})"`; }
    body += `<path d="${segsToD(L.segs)}" fill="${g(L.fill, 'f' + k)}" fill-opacity="${(L.fa ?? 1) * L.alpha}"${L.stroke && L.sw > 0 ? ` stroke="${g(L.stroke, 's' + k)}" stroke-width="${L.sw}" stroke-opacity="${(L.sa ?? 1) * L.alpha}" stroke-linecap="${L.cap || 'butt'}" stroke-linejoin="${L.join || 'miter'}"` : ''}${L.rule === 'evenodd' ? ' fill-rule="evenodd"' : ''}${clip}/>`;
  });
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" width="96" height="96"><defs>${defs}</defs>${body}</svg>`;
}

// ---------- animation ----------
const TIMES = [0, 0.08, 0.16, 0.26, 0.38, 0.52, 0.7, 1];
const DURATION = 460;
function same(a, b) { return JSON.stringify(a) === JSON.stringify(b); }
// the sampled poses as a sequence of plain animators (valueFrom / valueTo), each over its share of the 460 ms
function seq(prop, type, vals, ts) {
  let a = '';
  for (let j = 1; j < vals.length; j++) {
    const ms = Math.max(1, Math.round((ts[j] - ts[j - 1]) * DURATION));
    a += `                <objectAnimator android:propertyName="${prop}" android:valueType="${type}" android:duration="${ms}"\n`
      + `                    android:interpolator="@android:interpolator/linear"\n                    android:valueFrom="${vals[j - 1]}"\n                    android:valueTo="${vals[j]}" />\n`;
  }
  return `            <set android:ordering="sequentially">\n${a}            </set>\n`;
}
const target = (name, anim) => `    <target android:name="${name}">\n        <aapt:attr name="android:animation">\n${anim}        </aapt:attr>\n    </target>\n`;
function animXml(frames, reverse, comment) {
  // frames: layer lists at TIMES (rest -> hover). Every layer whose path, alpha or colour moves gets one animator with
  // keyframes at the times the eased progress reaches each sampled pose.
  const n = frames[0].length;
  const ts = reverse ? TIMES.map((t) => 1 - t).reverse() : TIMES;
  const fr = reverse ? [...frames].reverse() : frames;
  let targets = '';
  for (let k = 0; k < n; k++) {
    const hold = [];
    const ds = fr.map((f) => segsToD(f[k].segs));
    if (new Set(ds).size > 1) hold.push(['pathData', 'pathType', ds]);
    const fa = fr.map((f) => r3((f[k].fa ?? 1) * f[k].alpha)), sa = fr.map((f) => r3((f[k].sa ?? 1) * f[k].alpha));
    if (fr[0][k].fill && new Set(fa).size > 1) hold.push(['fillAlpha', 'floatType', fa]);
    if (fr[0][k].stroke && fr[0][k].sw > 0 && new Set(sa).size > 1) hold.push(['strokeAlpha', 'floatType', sa]);
    const sws = fr.map((f) => r3(f[k].sw || 0));
    if (fr[0][k].stroke && new Set(sws).size > 1) hold.push(['strokeWidth', 'floatType', sws]);
    const cols = (key) => fr.map((f) => (f[k][key] && f[k][key].hex) ? argb(f[k][key].hex) : null);
    for (const [key, prop] of [['fill', 'fillColor'], ['stroke', 'strokeColor']]) { const c = cols(key); if (c[0] && c.every(Boolean) && new Set(c).size > 1) hold.push([prop, 'colorType', c]); }
    for (const [prop, type, vals] of hold) targets += target(`p${k}`, seq(prop, type, vals, ts));
    const cl0 = fr[0][k].clip;
    if (cl0) cl0.forEach((_, j) => {
      const cds = fr.map((f) => segsToD(f[k].clip[j]));
      if (new Set(cds).size > 1) targets += target(`c${k}_${j}`, seq('pathData', 'pathType', cds, ts));
    });
  }
  return targets;
}

// ---------- main ----------
export async function convert(modulePath, name, accent, outDir, { animated = true, preview, comment = '', pose = 0, source, style = 'line' } = {}) {
  // modulePath: an icon module's path, or an icon definition (a composite built by tools/icons/build.mjs)
  const def = typeof modulePath === 'string' ? (await import(pathToFileURL(path.resolve(modulePath)).href)).default : modulePath;
  const frames = [];
  let size = 36, warn = new Set();
  // pose 1: a still of the pressed pose (an "on" state of its own resource)
  for (const t of (animated ? TIMES : [pose])) {
    const svg = renderSVG(def, { accent, progress: EASE(t), size: 96 });
    const fo = frameOps(svg);
    fo.warn.forEach((w) => warn.add(w));
    size = fo.size;
    frames.push(style === 'line' ? linearize(layers(fo), accent) : style === 'material' ? materialize(layers(fo), accent) : layers(fo));
  }
  if (warn.has('text')) throw new Error(`${name}: text in the icon (not drawable as a vector path)`);
  // one structure in every frame, else the rest pose alone
  let moving = animated;
  for (const f of frames) if (f.length !== frames[0].length || f.some((L, k) => shape(L.segs) !== shape(frames[0][k].segs) || Boolean(L.clip) !== Boolean(frames[0][k].clip))) moving = false;
  const rest = frames[0], hover = frames[frames.length - 1];
  const head = `P570: generated by tools/icons/build.mjs from ${source || (typeof modulePath === 'string' ? path.basename(modulePath) : def.name)} (Kubai Icons shapes, ${style === 'line' ? 'one-colour line' : style === 'material' ? 'Material You tonal' : 'Kubai'} style), accent ${accent}${comment ? ' - ' + comment : ''}`;
  fs.mkdirSync(outDir, { recursive: true });
  const write = (f, s) => fs.writeFileSync(path.join(outDir, f), s);
  const moves = moving && frames.some((f) => !same(f.map((L) => [segsToD(L.segs), L.alpha, L.fa, L.sa]), rest.map((L) => [segsToD(L.segs), L.alpha, L.fa, L.sa])));
  if (!moves) {
    write(`${name}.xml`, vectorXml(rest, size, head));
  } else {
    write(`kb_${name}_rest.xml`, vectorXml(rest, size, head + ' - rest'));
    write(`kb_${name}_hover.xml`, vectorXml(hover, size, head + ' - pressed'));
    const avd = (from, rev) => `<?xml version="1.0" encoding="utf-8"?>\n<!-- ${head} - ${rev ? 'pressed to rest' : 'rest to pressed'} -->\n`
      + `<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:aapt="http://schemas.android.com/aapt"\n    android:drawable="@drawable/kb_${name}_${from}">\n${animXml(frames, rev)}</animated-vector>\n`;
    write(`kb_${name}_in.xml`, avd('rest', false));
    write(`kb_${name}_out.xml`, avd('hover', true));
    write(`${name}.xml`, `<?xml version="1.0" encoding="utf-8"?>\n<!-- ${head}: the rest pose, the pressed / activated pose and the motion between them (460 ms) -->\n`
      + `<animated-selector xmlns:android="http://schemas.android.com/apk/res/android">\n`
      + `    <item android:id="@+id/kb_pressed" android:state_pressed="true" android:drawable="@drawable/kb_${name}_hover" />\n`
      + `    <item android:id="@+id/kb_activated" android:state_activated="true" android:drawable="@drawable/kb_${name}_hover" />\n`
      + `    <item android:id="@+id/kb_rest" android:drawable="@drawable/kb_${name}_rest" />\n`
      + `    <transition android:fromId="@id/kb_rest" android:toId="@id/kb_pressed" android:drawable="@drawable/kb_${name}_in" />\n`
      + `    <transition android:fromId="@id/kb_pressed" android:toId="@id/kb_rest" android:drawable="@drawable/kb_${name}_out" />\n`
      + `    <transition android:fromId="@id/kb_rest" android:toId="@id/kb_activated" android:drawable="@drawable/kb_${name}_in" />\n`
      + `    <transition android:fromId="@id/kb_activated" android:toId="@id/kb_rest" android:drawable="@drawable/kb_${name}_out" />\n`
      + `</animated-selector>\n`);
  }
  if (preview) {
    fs.mkdirSync(preview, { recursive: true });
    fs.writeFileSync(path.join(preview, `${name}_rest.svg`), previewSvg(rest, size));
    fs.writeFileSync(path.join(preview, `${name}_hover.svg`), previewSvg(hover, size));
  }
  return { moving: moves, warn: [...warn], layers: rest.length };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  const a = process.argv.slice(2);
  const opt = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : undefined; };
  const [mod, name, accent, out] = a;
  const r = await convert(mod, name, accent, out, { animated: !a.includes('--static'), preview: opt('--preview') });
  console.log(`${name}: ${r.layers} layers, ${r.moving ? 'animated' : 'static'}${r.warn.length ? ', left out: ' + r.warn.join(' ') : ''}`);
}
