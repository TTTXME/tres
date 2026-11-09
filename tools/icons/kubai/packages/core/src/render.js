// Renders an icon definition to SVG markup without a DOM, so the same code runs in the
// build, on a server and in the browser. An icon's draw() writes attributes through
// setAttribute(), which the light element tree below provides.

import { tint, soften, rgb, hex, usesEdge, cssTint, portableShadow, CSS_FILL, CSS_EDGE, CSS_WHITE } from './color.js';
import { interp } from './motion.js';
import { f } from './kit.js';

export const VIEWBOX = '0 -3 31 31';
export const FONT = "ui-sans-serif,system-ui,-apple-system,'Segoe UI',sans-serif";

// ---------- A light element tree ----------
class Node {
  constructor(tag, attrs) { this.tag = tag; this.attrs = attrs; this.children = []; }
  setAttribute(k, v) { this.attrs.set(k, String(v)); }
  getAttribute(k) { return this.attrs.has(k) ? this.attrs.get(k) : null; }
  removeAttribute(k) { this.attrs.delete(k); }
  get tagName() { return this.tag; }
}

const ATTR = /([:\w-]+)\s*=\s*"([^"]*)"/g;
const unesc = (s) => s.replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');

/** Parses the well-formed SVG fragments icon templates produce. */
export function parse(src) {
  const root = new Node('#root', new Map());
  const stack = [root];
  const re = /<(\/?)([\w:-]+)((?:\s+[:\w-]+\s*=\s*"[^"]*")*)\s*(\/?)>|([^<]+)/g;
  let m;
  while ((m = re.exec(src))) {
    const top = stack[stack.length - 1];
    if (m[5] !== undefined) {
      const text = m[5];
      if (text.trim()) top.children.push(unesc(text.trim()));
      continue;
    }
    if (m[1]) {
      if (stack.length < 2 || top.tag !== m[2]) throw new Error(`Unbalanced </${m[2]}> in icon markup`);
      stack.pop();
      continue;
    }
    const attrs = new Map();
    for (const a of m[3].matchAll(ATTR)) attrs.set(a[1], unesc(a[2]));
    const node = new Node(m[2], attrs);
    top.children.push(node);
    if (!m[4]) stack.push(node);
  }
  if (stack.length !== 1) throw new Error(`Unclosed <${stack[stack.length - 1].tag}> in icon markup`);
  return root;
}

export function serialize(node) {
  if (typeof node === 'string') return esc(node);
  const attrs = [...node.attrs].map(([k, v]) => ` ${k}="${esc(v)}"`).join('');
  if (!node.children.length) return `<${node.tag}${attrs}/>`;
  return `<${node.tag}${attrs}>${node.children.map(serialize).join('')}</${node.tag}>`;
}
const walk = (node, fn) => { for (const c of node.children) if (typeof c !== 'string') { fn(c); walk(c, fn); } };
// Like walk, and passes the tone of the badge an element sits in, if any.
const walkTone = (node, fn, tone) => {
  for (const c of node.children) {
    if (typeof c === 'string') continue;
    const t = c.getAttribute('data-badge') || tone;
    fn(c, t);
    walkTone(c, fn, t);
  }
};

// ---------- Fills and filters ----------
const BAND = 1.2;
/** The `u` helper an icon's svg(u) receives: gradient and filter references, unique ids. */
export function makeU(k) {
  const grads = new Map();
  grads.set(`loS-${k}`, [[0, '#93BBFF'], [1, '#7FAEFF']]);
  grads.set(`upS-${k}`, [[0, '#CCDFFF'], [1, '#ADCBFF']]);
  const banded = (kind, h) => {
    const o = f(Math.max(0.5, 1 - BAND / h));
    const id = `${kind}-${k}-${Math.round(o * 1000)}`;
    if (!grads.has(id)) {
      grads.set(id, kind === 'lo'
        ? [[0, '#93BBFF'], [o, '#81B0FF'], [f(o + 0.0001), '#4389FF'], [1, '#97BEFF']]
        : [[0, '#CCDFFF'], [o, '#B2CEFF'], [f(o + 0.0001), '#89B5FF'], [1, '#97BEFF']]);
    }
    return `url(#${id})`;
  };
  const pure = (src) => {
    const id = `pure-${k}-${src.slice(1)}`;
    if (!grads.has(id)) grads.set(id, { pure: src });
    return `url(#${id})`;
  };
  return {
    grads, pure,
    lo: (h) => banded('lo', h), up: (h) => banded('up', h),
    loS: `url(#loS-${k})`, upS: `url(#upS-${k})`,
    sh: `url(#sh-${k})`, hi: `url(#hi-${k})`, id: (n) => `${n}-${k}`
  };
}

/**
 * Colour resolvers. `css` writes color-mix() over custom properties (the accent comes
 * from currentColor); a palette object { c, e, deep } writes exact hex values; 'template'
 * writes tokens that fillTemplate() turns into exact values later.
 */
function painter(paint) {
  if (paint === 'template') {
    return {
      css: false,
      template: true,
      tint: (src, edge, tone) => `{t${src}${tone ? `b${tone}` : edge ? 'e' : 'c'}}`,
      white: '{w}',
      pureTop: (base) => `{p${base}}`
    };
  }
  if (!paint || paint === 'css') {
    return {
      css: true,
      tint: (src, edge, tone) => cssTint(src, tone ? `var(--ki-b,${tone})` : edge ? CSS_EDGE : CSS_FILL),
      white: CSS_WHITE,
      pureTop: (base) => `color-mix(in srgb,${base},#fff var(--ki-l,0%))`,
      shadow: cssTint('#8AB6FF', CSS_FILL)
    };
  }
  const c = rgb(paint.c), e = rgb(paint.e || paint.c);
  return {
    css: false,
    tint: (src, edge, tone) => tint(src, tone ? rgb(paint.b || tone) : edge ? e : c),
    white: paint.deep ? tint('#2F6FE0', e) : '#FFFFFF',
    pureTop: (base) => (paint.deep ? soften(base, 0.72) : base),
    shadow: tint('#8AB6FF', c)
  };
}
// Writes a colour as a presentation attribute (exact) or an inline style (CSS form, since
// presentation attributes do not take var()).
function paintAttr(el, attr, value, css) {
  if (!css) { el.setAttribute(attr, value); return; }
  el.removeAttribute(attr);
  const prev = el.getAttribute('style');
  el.setAttribute('style', (prev ? prev + ';' : '') + `${attr}:${value}`);
}

function defsMarkup(k, grads, P) {
  const stop = (o, color) => P.css ? `<stop offset="${o}" style="stop-color:${color}"/>` : `<stop offset="${o}" stop-color="${color}"/>`;
  const g = [...grads].map(([id, stops]) => {
    if (stops.pure) {
      const base = P.tint(stops.pure, usesEdge(stops.pure));
      return `<linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">${stop(0, P.pureTop(base))}${stop(1, base)}</linearGradient>`;
    }
    return `<linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">${stops.map(([o, c]) => stop(o, P.tint(c, false))).join('')}</linearGradient>`;
  }).join('');
  const liveShadow = `<feGaussianBlur in="SourceAlpha" stdDeviation="2"/><feOffset dy="2" result="o"/>`
    + `<feFlood style="flood-color:${P.shadow}" flood-opacity="0.21"/><feComposite in2="o" operator="in"/>`
    + `<feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge>`;
  return g
    + `<filter id="sh-${k}" x="-40%" y="-40%" width="180%" height="190%" color-interpolation-filters="sRGB">`
    + (P.css ? liveShadow : P.template ? '{s}' : portableShadow(P.shadow, 0.21)) + `</filter>`
    + `<filter id="hi-${k}" x="-10%" y="-10%" width="120%" height="130%" color-interpolation-filters="sRGB">`
    + `<feColorMatrix in="SourceAlpha" type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0" result="a"/>`
    + `<feOffset in="a" dy="0.7"/><feGaussianBlur stdDeviation="0.35"/>`
    + `<feComposite in2="a" operator="arithmetic" k2="-1" k3="1"/>`
    + `<feColorMatrix type="matrix" values="0 0 0 0 1 0 0 0 0 1 0 0 0 0 1 0 0 0 0.5 0"/>`
    + `<feBlend in2="SourceGraphic" mode="overlay"/></filter>`;
}

// ---------- Which shapes get an outline ----------
/** Bounding boxes [x0, y0, x1, y1] of each subpath of a path, from its absolute commands. */
function subpathBoxes(d) {
  const boxes = [];
  let b = null, x = 0, y = 0;
  const add = (px, py) => { if (b) { b[0] = Math.min(b[0], px); b[1] = Math.min(b[1], py); b[2] = Math.max(b[2], px); b[3] = Math.max(b[3], py); } };
  for (const [, cmd, body] of d.matchAll(/([MLHVQCA Z])([^MLHVQCAZ]*)/g)) {
    const n = (body.match(/-?\d*\.?\d+(?:e-?\d+)?/g) || []).map(Number);
    if (cmd === 'M') { b = [n[0], n[1], n[0], n[1]]; boxes.push(b); [x, y] = n; for (let i = 2; i < n.length; i += 2) { add(n[i], n[i + 1]); x = n[i]; y = n[i + 1]; } }
    else if (cmd === 'L') for (let i = 0; i < n.length; i += 2) { add(n[i], n[i + 1]); x = n[i]; y = n[i + 1]; }
    else if (cmd === 'H') for (const v of n) { add(v, y); x = v; }
    else if (cmd === 'V') for (const v of n) { add(x, v); y = v; }
    else if (cmd === 'Q' || cmd === 'C') { const w = cmd === 'Q' ? 4 : 6; for (let i = 0; i + w <= n.length; i += w) { for (let j = 0; j < w; j += 2) add(n[i + j], n[i + j + 1]); x = n[i + w - 2]; y = n[i + w - 1]; } }
    else if (cmd === 'A') for (let i = 0; i + 7 <= n.length; i += 7) {
      const ex = n[i + 5], ey = n[i + 6], mx = (x + ex) / 2, my = (y + ey) / 2;
      add(ex, ey);
      if (n[i + 3]) { add(mx - n[i], my); add(mx + n[i], my); add(mx, my - n[i + 1]); add(mx, my + n[i + 1]); }
      x = ex; y = ey;
    }
  }
  return boxes;
}
const hasClass = (el, name) => (el.getAttribute('class') || '').split(/\s+/).includes(name);
const IDENTITY = [1, 0, 0, 1, 0, 0];
const multiply = (m, n) => [
  m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
  m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
  m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5]
];
/** The matrix of an SVG transform attribute. */
function matrixOf(transform) {
  let m = IDENTITY;
  for (const [, name, body] of (transform || '').matchAll(/(\w+)\(([^)]*)\)/g)) {
    const n = body.trim().split(/[\s,]+/).map(Number);
    if (name === 'translate') m = multiply(m, [1, 0, 0, 1, n[0], n[1] || 0]);
    else if (name === 'scale') m = multiply(m, [n[0], 0, 0, n[1] ?? n[0], 0, 0]);
    else if (name === 'matrix') m = multiply(m, n);
    else if (name === 'rotate') {
      const r = n[0] * Math.PI / 180, c = Math.cos(r), s = Math.sin(r), x = n[1] || 0, y = n[2] || 0;
      m = multiply(m, [c, s, -s, c, x - c * x + s * y, y - s * x - c * y]);
    }
  }
  return m;
}
/** The on-canvas bounding box of each outlined shape in the tree, in document order. */
function outlineHulls(tree) {
  const hulls = [];
  const visit = (node, parent) => {
    for (const el of node.children) {
      if (typeof el === 'string') continue;
      const m = multiply(parent, matrixOf(el.getAttribute('transform')));
      if (hasClass(el, 'ol')) {
        const pts = subpathBoxes(el.getAttribute('d') || '').flatMap(([x0, y0, x1, y1]) => [[x0, y0], [x1, y0], [x1, y1], [x0, y1]])
          .map(([x, y]) => [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]]);
        hulls.push(pts.length ? [Math.min(...pts.map((p) => p[0])), Math.min(...pts.map((p) => p[1])), Math.max(...pts.map((p) => p[0])), Math.max(...pts.map((p) => p[1]))] : null);
      }
      visit(el, m);
    }
  };
  visit(tree, IDENTITY);
  return hulls;
}
const area = (b) => (b[2] - b[0]) * (b[3] - b[1]);
const within = (a, b) => a[0] >= b[0] - 0.01 && a[1] >= b[1] - 0.01 && a[2] <= b[2] + 0.01 && a[3] <= b[3] + 0.01;

const innerCache = new WeakMap();
/**
 * Only an object's silhouette carries an outline. A shape that stays inside a larger
 * outlined shape throughout the hover is told apart by its lighter fill, so it needs none;
 * anything that sticks out, or moves out, keeps its own. Returns the indexes, in document
 * order, of the outlined shapes to leave bare.
 */
function innerOutlines(def, state) {
  let found = innerCache.get(def);
  if (found) return found;
  const tree = parse(def.svg(makeU('x')));
  const E = {};
  walk(tree, (el) => { const n = el.getAttribute('data-n'); if (n) E[n] = el; });
  const rest = def.target(false, state), hover = def.target(true, state);
  found = null;
  for (const k of [0, 0.25, 0.5, 0.75, 1]) {
    def.draw(interp(rest, hover, k), E, {});
    const hulls = outlineHulls(tree);
    const inner = new Set(hulls.flatMap((h, i) => (h && hulls.some((o, j) => j !== i && o && area(o) > area(h) && within(h, o)) ? [i] : [])));
    found = found ? new Set([...found].filter((i) => inner.has(i))) : inner;
  }
  innerCache.set(def, found);
  return found;
}

/**
 * Builds an icon's inner SVG markup (defs and layers; no <svg> wrapper).
 *
 * options:
 * - k: id suffix, unique per icon on the page. '__K__' leaves a placeholder to replace.
 * - hover: render the end of the hover instead of the rest pose.
 * - progress: 0..1 between rest and hover (overrides hover), for frame sheets.
 * - state: tap state passed to target().
 * - paint: 'css' (default), 'template', or { c, e, deep, b } for exact colours (b: badge colour).
 * - names: keep data-n attributes so a runtime can animate the markup (default true).
 */
export function renderInner(def, { k = 'k', hover = false, progress, state = {}, paint = 'css', names = true } = {}) {
  const P = painter(paint);
  const u = makeU(k);
  const tree = parse(def.svg(u));
  const E = {};
  walk(tree, (el) => { const n = el.getAttribute('data-n'); if (n) E[n] = el; });
  const p = typeof progress === 'number'
    ? interp(def.target(false, state), def.target(true, state), progress)
    : def.target(Boolean(hover), state);
  def.draw(p, E, {});

  const skip = innerOutlines(def, state);
  let outlineIndex = 0;
  const masks = [];
  walkTone(tree, (el, tone) => {
    if (hasClass(el, 'ol') && skip.has(outlineIndex++)) {
      el.removeAttribute('class');
      el.removeAttribute('data-t');
      el.setAttribute('fill', 'none');
    }
    const cls = el.getAttribute('class');
    if (cls) {
      const list = cls.split(/\s+/);
      if (list.includes('ol')) {
        const id = `ol-${k}-${masks.length}`;
        masks.push(`<mask id="${id}" maskUnits="userSpaceOnUse" x="-40" y="-40" width="111" height="109"><rect x="-40" y="-40" width="111" height="109" fill="#fff"/><path d="${esc(el.getAttribute('d') || '')}" fill="#000"${el.getAttribute('fill-rule') ? ` fill-rule="${el.getAttribute('fill-rule')}"` : ''}/></mask>`);
        el.setAttribute('mask', `url(#${id})`);
        el.setAttribute('fill', 'none');
        el.setAttribute('stroke-width', '2');
      } else if (list.includes('ln')) {
        el.setAttribute('fill', 'none');
        el.setAttribute('stroke-width', '1.6');
        el.setAttribute('stroke-linecap', 'round');
      }
      el.removeAttribute('class');
    }
    if (el.tag === 'text' && !el.getAttribute('font-family')) el.setAttribute('font-family', FONT);
    const t = el.getAttribute('data-t');
    if (t) {
      const [attr, src] = t.split(':');
      paintAttr(el, attr, P.tint(src, usesEdge(src), tone), P.css);
      el.removeAttribute('data-t');
    }
    const w = el.getAttribute('data-w');
    if (w) { paintAttr(el, w, P.white, P.css); el.removeAttribute('data-w'); }
    for (const a of [...el.attrs.keys()]) if (a.startsWith('data-') && !(names && a === 'data-n')) el.removeAttribute(a);
  });
  return `<defs>${defsMarkup(k, u.grads, P)}${masks.join('')}</defs>${tree.children.map(serialize).join('')}`;
}

/** A standalone SVG document: exact colours, square canvas with room for the shadow. */
export function renderSVG(def, { accent = '#5393FF', variant = 'color', badge, size = 96, pad = 2.5, hover = false, progress, state, k } = {}) {
  const paint = variant === 'soft'
    ? { c: soften(accent, 0.1), e: hex(rgb(accent).map((v) => v * 0.84)), deep: true }
    : { c: accent, e: accent, deep: false };
  if (badge) paint.b = badge;
  const side = 31 + pad * 2, box = [15.5 - side / 2, 12.5 - side / 2, side, side].map(f);
  const inner = renderInner(def, { k: k || `ki-${def.name}${variant === 'soft' ? '-soft' : ''}`, hover, progress, state, paint, names: false });
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${box.join(' ')}" width="${size}" height="${size}" fill="none">${inner}</svg>`;
}
