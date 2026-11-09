// Composite icons: a base icon with a badge on its corner, or a slash across it.
// Badges are drawn round the origin; the base may move the anchor with `badgeAt`.

import { f } from './kit.js';

export const BADGE_AT = [22.6, 18.4];
/**
 * Badges are a solid ink disc with a white glyph, so they never blend with the body.
 * A page can recolour the disc with --ki-b (or badgeColor).
 */
export const BADGE_TONE = '#33363D';
/** Radius of the gap cut out of the base round the badge (the disc is 5). */
export const BADGE_GAP = 6.4;

/** Base icon plus a badge. The badge's motion runs alongside the base's own. */
export function withBadge(base, badge, extra = {}) {
  const [x, y] = base.badgeAt || BADGE_AT;
  return {
    ...extra,
    name: `${base.name}-${badge.name}`,
    base: base.name,
    badge: badge.name,
    // The badge's semantic colour (green for add, red for remove …), offered as an option.
    tone: badge.tone,
    tap: base.tap,
    svg: (u) => {
      const m = u.id('kbk');
      return `<mask id="${m}" maskUnits="userSpaceOnUse" x="-40" y="-40" width="111" height="109">`
        + `<rect x="-40" y="-40" width="111" height="109" fill="#fff"/>`
        + `<circle data-n="kbK" cx="${x}" cy="${y}" r="${BADGE_GAP}" fill="#000"/></mask>`
        + `<g mask="url(#${m})">${base.svg(u)}</g>`
        + `<g data-badge="${BADGE_TONE}" transform="translate(${x} ${y})">${badge.svg(u)}</g>`;
    },
    target: (h, s) => ({ ...base.target(h, s), kb: badge.target(h, s) }),
    draw(p, E, inst) {
      base.draw(p, E, inst);
      badge.draw(p.kb, E, inst);
      // The cut-out grows with the badge's hover pop, so the gap stays even.
      if (E.kbK) E.kbK.setAttribute('r', f(BADGE_GAP * (p.kb.s || 1)));
    }
  };
}

/**
 * Base icon with a slash across it, for "off" states. The base keeps its rest pose; on
 * hover the slash gives a short damped shake, like something that won't switch on.
 */
export function withSlash(base, slash, extra = {}) {
  return {
    ...extra,
    name: `${base.name}-off`,
    base: base.name,
    badge: 'off',
    svg: (u) => `${base.svg(u)}${slash.svg(u)}`,
    target: (h, s) => ({ ...base.target(false, s), kb: slash.target(h, s) }),
    draw(p, E, inst) {
      base.draw(p, E, inst);
      slash.draw(p.kb, E, inst);
    }
  };
}
