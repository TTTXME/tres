// Browser runtime: animates an icon whose markup is already on the page. The markup
// carries the rest pose; only target() and draw() run here, on hover or state changes.

import { EASE, DURATION, PHASE, interp } from './motion.js';
import { VARIANTS } from './color.js';

let reduced = false;
try { reduced = matchMedia('(prefers-reduced-motion: reduce)').matches; } catch { /* not a browser */ }

const raf = typeof requestAnimationFrame === 'function' ? requestAnimationFrame : (fn) => setTimeout(() => fn(Date.now()), 16);
const caf = typeof cancelAnimationFrame === 'function' ? cancelAnimationFrame : clearTimeout;
const now = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());

/**
 * Attaches motion to a rendered <svg>.
 * `icon` needs target(hover, state) and draw(p, E).
 * Returns { hover(on), state(next), hovered, active, svg, destroy() }.
 */
export function animate(svg, icon, { state = {} } = {}) {
  const E = {};
  for (const el of svg.querySelectorAll('[data-n]')) E[el.getAttribute('data-n')] = el;
  // An outlined path is masked by a copy of its own shape; keep the copy in step. A mask
  // with a shape of its own is left to the icon's draw().
  const clips = [];
  for (const el of svg.querySelectorAll('[mask]')) {
    const id = (el.getAttribute('mask').match(/url\(#([^)]+)\)/) || [])[1];
    const shape = id && svg.querySelector(`mask[id="${id}"] path`);
    if (shape && shape.getAttribute('d') === el.getAttribute('d')) clips.push([el, shape]);
  }
  let on = false, s = { ...state }, frame = 0, stopped = false;
  let cur = icon.target(false, s);
  // While it moves, the icon gets its own compositing layer, so each frame repaints only
  // this icon and not every icon painted next to it. The layer is dropped once it settles.
  const style = svg.style;
  const will = style ? style.willChange : '';
  const lift = (up) => { if (style) style.willChange = up ? 'transform' : will; };

  const paint = (phase) => {
    globalThis[PHASE] = phase;
    icon.draw(cur, E, {});
    globalThis[PHASE] = undefined;
    for (const [el, shape] of clips) {
      const d = el.getAttribute('d');
      if (d && shape.getAttribute('d') !== d) shape.setAttribute('d', d);
    }
  };
  const go = (instant) => {
    const to = icon.target(on, s);
    caf(frame);
    if (instant || reduced) { cur = to; paint(); lift(false); return; }
    const from = cur, t0 = now();
    lift(true);
    const tick = (t) => {
      if (stopped) return;
      const k = Math.min(1, ((typeof t === 'number' ? t : now()) - t0) / DURATION);
      const e = EASE(k);
      cur = interp(from, to, e);
      paint({ out: !on, e });
      if (k < 1) frame = raf(tick); else lift(false);
    };
    frame = raf(tick);
  };
  // A non-default starting state is applied at once.
  if (Object.keys(s).length) go(true);

  return {
    hover(next, instant) { if (Boolean(next) !== on) { on = Boolean(next); go(instant); } },
    state(next, instant) { s = { ...s, ...next }; go(instant); },
    get hovered() { return on; },
    get active() { return Boolean(s.active); },
    svg,
    destroy() { stopped = true; caf(frame); lift(false); }
  };
}

/**
 * Wires hover, focus and touch on `target` (the icon itself, or a parent such as a button)
 * to a controller. Tap icons toggle `active` on click. Returns a cleanup function.
 */
export function bindTrigger(target, ctl, icon, onToggle) {
  let count = 0;
  const enter = (e) => { if (!e.pointerType || e.pointerType === 'mouse') { count++; ctl.hover(true); } };
  const leave = (e) => { if (!e.pointerType || e.pointerType === 'mouse') { count = Math.max(0, count - 1); if (!count) ctl.hover(false); } };
  // Keyboard focus plays the motion like hover; focus left behind by a mouse click does not.
  let focused = false;
  const focus = (e) => {
    let visible = true;
    try { visible = e.target.matches(':focus-visible'); } catch { /* older browsers */ }
    if (!visible || focused) return;
    focused = true; count++; ctl.hover(true);
  };
  const blur = () => { if (!focused) return; focused = false; count = Math.max(0, count - 1); if (!count) ctl.hover(false); };
  const click = (e) => {
    if (icon.tap) {
      const active = !ctl.active;
      if (onToggle) onToggle(active); else ctl.state({ active });
      return;
    }
    // Touch has no hover: a tap plays the motion forward and back.
    if (e.pointerType && e.pointerType !== 'mouse') { ctl.hover(true); setTimeout(() => ctl.hover(false), DURATION + 240); }
  };
  const handlers = { pointerenter: enter, pointerleave: leave, focusin: focus, focusout: blur, click };
  const off = () => { for (const type in handlers) target.removeEventListener(type, run); };
  // An icon taken off the page stops listening to the parent it was bound to.
  const run = (e) => { if (ctl.svg && !ctl.svg.isConnected) off(); else handlers[e.type](e); };
  for (const type in handlers) target.addEventListener(type, run);
  return off;
}
export { badgeStyle, uid } from './icon.js';
export { VARIANTS };

/** Sets the variant for every icon inside `root` that does not choose its own. */
export function setVariant(root, variant) {
  for (const [k, v] of Object.entries(VARIANTS[variant] || {})) root.style.setProperty(k, v);
}
