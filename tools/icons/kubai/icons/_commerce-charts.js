// Shared pieces for the commerce and chart icons: filled layers with their outlines,
// the coin that spins, and the chart card.
import { KR, f, OL, circ, rr, poly, arrow, at, clamp01 } from '../packages/core/src/kit.js';

/** A back layer: fill with shadow and top glow, then its outside outline. */
export const back = (u, d, fill, n) => `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${d}" fill="${fill}"${n ? ` data-n="${n}"` : ''}/></g></g><path d="${d}" ${OL}${n ? ` data-n="${n}O"` : ''}/>`;
/** A front layer: fill with top glow only, then its outside outline. */
export const front = (u, d, fill, n) => `<g filter="${u.hi}"><path d="${d}" fill="${fill}"${n ? ` data-n="${n}"` : ''}/></g><path d="${d}" ${OL}${n ? ` data-n="${n}O"` : ''}/>`;
/** Sets the same attributes on several named elements. */
export const atAll = (E, names, o) => names.forEach((n) => at(E[n], o));

/** The square chart card every chart icon sits on. */
export const CARD = rr(5.2, 2.2, 20.6, 20.6, KR);
export const card = (u) => back(u, CARD, u.loS);

/**
 * A coin seen face on: a mid-tone disc with a lighter inset face and a deep mark. It spins
 * once about its vertical axis; the mark shows only while the front faces the viewer.
 */
export const COIN_NAMES = ['c1', 'c2', 'c3', 'c4', 'cm'];
export const coin = (u, mark) => `
  <g filter="${u.sh}"><g filter="${u.hi}"><g data-n="c1"><path d="${circ(15.5, 12.5, 11.5)}" fill="${u.loS}"/></g></g></g>
  <g data-n="c2"><path d="${circ(15.5, 12.5, 11.5)}" ${OL}/></g>
  <g filter="${u.hi}"><g data-n="c3"><path d="${circ(15.5, 12.5, 8.2)}" fill="${u.upS}"/></g></g>
  <g data-n="c4"><path d="${circ(15.5, 12.5, 8.2)}" ${OL}/></g>
  <g data-n="cm">${mark}</g>`;
export const coinSpin = (p, E) => {
  const o = clamp01(p.o), c = Math.cos(2 * Math.PI * o);
  const sx = Math.sign(c || 1) * Math.max(0.04, Math.abs(c));
  const t = `translate(15.5 0) scale(${f(sx)} 1) translate(-15.5 0)`;
  ['c1', 'c2', 'c3', 'c4'].forEach((n) => at(E[n], { transform: t }));
  at(E.cm, { transform: t, opacity: c > 0 ? 1 : 0 });
};
/** A glyph stroked as a deep mark on the coin face. */
export const glyph = (d) => `<path d="${d}" fill="none" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" data-t="stroke:#2F6FE0"/>`;

/** The zigzag arrow on a chart card shared by trending-up and trending-down. */
export const trendSvg = () => `<path data-n="ln" fill="none" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" stroke="#fff" data-w="stroke"/>
  <path data-n="hd" fill="#fff" data-w="fill"/>`;
export const trendDraw = (p, E) => {
  const q = p.q, n = q.length;
  const a = arrow(q[n - 4], q[n - 3], q[n - 2], q[n - 1], 3.6, 2.8);
  const pts = [];
  for (let i = 0; i < n - 2; i += 2) pts.push([q[i], q[i + 1]]);
  at(E.ln, { d: poly(pts) + a.shaft.replace(/^M[^L]+/, '') });
  at(E.hd, { d: a.head });
};
