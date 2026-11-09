// Standalone SVG documents from pre-rendered templates, for places that have the markup
// but not the icon definitions. The result matches renderSVG for any accent colour.
import { tint, soften, rgb, palette, portableShadow } from './color.js';

/**
 * Fills a template made by renderInner(def, { k: '__K__', paint: 'template', names: false }).
 * `badge` is a hex colour, or 'semantic' for the icon's own `tone`.
 */
export function fillTemplate({ name, markup, tone }, { accent = '#5393FF', variant = 'color', badge, size = 96 } = {}) {
  const paint = palette(accent, variant);
  const c = rgb(paint.c), e = rgb(paint.e);
  const b = badge === 'semantic' ? tone : badge;
  const inner = markup
    .replace(/\{t(#[0-9A-Fa-f]{6})(?:b(#[0-9A-Fa-f]{6})|([ce]))\}/g, (_, src, ink, part) => tint(src, ink ? rgb(b || ink) : part === 'e' ? e : c))
    .replace(/\{p(#[0-9A-Fa-f]{6})\}/g, (_, base) => (paint.deep ? soften(base, 0.72) : base))
    .replaceAll('{w}', paint.deep ? tint('#2F6FE0', e) : '#FFFFFF')
    .replaceAll('{s}', portableShadow(tint('#8AB6FF', c), 0.21))
    .replaceAll('__K__', `ki-${name}${variant === 'soft' ? '-soft' : ''}`);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="-2.5 -5.5 36 36" width="${size}" height="${size}" fill="none">${inner}</svg>`;
}
