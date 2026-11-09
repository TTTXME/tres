// Shared pieces for the device icons.
import { OL, f } from '../packages/core/src/kit.js';

/** A filled piece with its shadow, top glow and outside outline. */
export function solid(u, d, fill, { sh = true, hi = true, rule } = {}) {
  const r = rule ? ` fill-rule="${rule}"` : '';
  let body = `<path d="${d}" fill="${fill}"${r}/>`;
  if (hi) body = `<g filter="${u.hi}">${body}</g>`;
  if (sh) body = `<g filter="${u.sh}">${body}</g>`;
  return `${body}<path d="${d}"${r} ${OL}/>`;
}

/** Circle drawn clockwise, so it merges with rr/rp shapes in one path. */
export const circCW = (cx, cy, r) => `M${f(cx - r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 1 ${f(cx + r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 1 ${f(cx - r)} ${f(cy)}Z`;

/** Open arc of radius r round (cx, cy) from angle a0 to a1, degrees clockwise from north. */
export function arcPath(cx, cy, r, a0, a1) {
  const p = (a) => [cx + r * Math.sin(a * Math.PI / 180), cy - r * Math.cos(a * Math.PI / 180)];
  const s = p(a0), e = p(a1);
  return `M${f(s[0])} ${f(s[1])}A${f(r)} ${f(r)} 0 ${a1 - a0 > 180 ? 1 : 0} 1 ${f(e[0])} ${f(e[1])}`;
}

/** Eases a sub-range of a 0→1 progress: 0 before `a`, 1 after `b`. */
export const span = (o, a, b) => Math.max(0, Math.min(1, (o - a) / (b - a)));
