// The shape every built icon module exports, and the <svg> wrapper around its markup.
import { VARIANTS } from './color.js';

export const VIEWBOX = '0 -3 31 31';
export const PLACEHOLDER = /__K__/g;

/**
 * Wraps a built icon: its rest markup (with an id placeholder), its motion, and for badged
 * icons the badge's semantic colour.
 */
export const icon = (name, markup, target, draw, tap, tone) => ({ name, markup, target, draw, tap: Boolean(tap), ...(tone ? { tone } : {}) });

/** The --ki-b value for a badgeColor option: 'semantic' picks the badge's own colour. */
export const badgeStyle = (ic, badgeColor) => {
  const v = badgeColor === 'semantic' ? ic.tone : badgeColor;
  return v ? { '--ki-b': v } : {};
};

// Shared by every copy of this module on a page, such as a bundle next to the CDN build.
const COUNT = Symbol.for('kubai-icons.uid');
// Ids made on a server stay distinct from the ones the browser makes after hydration.
const SOURCE = typeof document === 'undefined' ? 's' : '';
/** A short id, unique on the page, for one icon instance's gradients, filters and masks. */
export const uid = () => {
  globalThis[COUNT] = (globalThis[COUNT] || 0) + 1;
  return `ki${SOURCE}${globalThis[COUNT].toString(36)}`;
};

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
const styleOf = (o) => Object.entries(o).map(([k, v]) => `${k}:${v}`).join(';');

/**
 * Full SVG markup for one instance. Options: size (px), color (any CSS colour; defaults to
 * currentColor), variant ('color' | 'soft'; defaults to what the page sets, else 'color'), badgeColor (ink for the corner badge: any CSS
 * colour, or 'semantic'), title (accessible name), className, id.
 */
export function toSvg(ic, { size = 24, color, variant, badgeColor, title, className, id = uid(), attrs = {} } = {}) {
  const style = { ...(VARIANTS[variant] || {}), ...(color ? { color } : {}), ...badgeStyle(ic, badgeColor) };
  const a = {
    xmlns: 'http://www.w3.org/2000/svg', viewBox: VIEWBOX, width: size, height: size, fill: 'none', overflow: 'visible',
    class: ['kubai-icon', `kubai-icon-${ic.name}`, className].filter(Boolean).join(' '),
    ...(title ? { role: 'img' } : { 'aria-hidden': 'true' }),
    ...(Object.keys(style).length ? { style: styleOf(style) } : {}),
    ...attrs
  };
  const head = Object.entries(a).map(([k, v]) => ` ${k}="${esc(v)}"`).join('');
  return `<svg${head}>${title ? `<title>${esc(title)}</title>` : ''}${ic.markup.replace(PLACEHOLDER, id)}</svg>`;
}
