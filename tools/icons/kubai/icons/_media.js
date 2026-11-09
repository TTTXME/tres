// Shared pieces for the media icons: the round button that playback controls sit on, and
// the rounded triangles drawn on it.
import { OL, circ, rp, f } from '../packages/core/src/kit.js';

/** The round button: a mid-tone disc with its shadow, top light and outline. */
export const button = (u) => `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(15.5, 12.5, 11.5)}" fill="${u.loS}"/></g></g>
    <path d="${circ(15.5, 12.5, 11.5)}" ${OL}/>`;

/** A rounded triangle pointing right (dir 1) or left (dir -1): base at x0, tip at x1. */
export const tri = (x0, x1, cy, half, r = 0.8) => rp([[x0, cy - half], [x1, cy], [x0, cy + half]], [r, r * 0.9, r]);

/** A translate transform. */
export const tr = (x, y = 0) => `translate(${f(x)} ${f(y)})`;
