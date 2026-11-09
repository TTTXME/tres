// Shared helpers for the security, time and health icons.
import { f, clamp01 } from '../packages/core/src/kit.js';

/** Circle, clockwise: unites with rr/rp shapes in one path instead of punching a hole. */
export const cwCirc = (cx, cy, r) => `M${f(cx - r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 1 ${f(cx + r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 1 ${f(cx - r)} ${f(cy)}Z`;
/** Progress through one stage [a, b] of a 0..1 motion. */
export const stage = (o, a, b) => clamp01((o - a) / (b - a));
/** How much of a traced stroke shows: it lifts off over the first quarter, then is written again. */
export const retrace = (o) => (o < 0.25 ? 1 - o / 0.25 : (o - 0.25) / 0.75);
/** Dash offset for a path drawn with pathLength="10" stroke-dasharray="10 10". */
export const dashOff = (shown) => f(10 * (1 - clamp01(shown)));
/** Point on a circle, angle in degrees clockwise from north. */
export const polar = (cx, cy, r, a) => [cx + r * Math.sin(a * Math.PI / 180), cy - r * Math.cos(a * Math.PI / 180)];
