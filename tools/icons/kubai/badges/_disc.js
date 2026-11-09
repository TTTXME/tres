// The disc every badge sits on: a solid disc in the badge colour with a white glyph.
// compose.js cuts a gap round it out of the base icon, so it reads apart from the body
// without an outline or shadow.
import { f } from '../packages/core/src/kit.js';

export const BADGE_R = 5;
/** Glyph fill and stroke: always white on the disc. */
export const INK = 'fill="#FFFFFF"';
export const INKS = 'stroke="#FFFFFF"';
export const disc = (u, glyph) => `<g data-n="kbG"><circle r="${BADGE_R}" data-t="fill:#5393FF"/>${glyph}</g>`;
/** The badge's own hover pop: a small lift in scale. */
export const pop = (s) => `scale(${f(s)})`;
