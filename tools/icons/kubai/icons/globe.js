import { defineIcon, f, OL, circ, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'globe',
  zh: '地球',
  category: 'travel',
  tags: ['world', 'earth', 'internet', 'language', 'web'],
  variants: ['lock', 'search', 'check', 'x', 'alert', 'clock', 'cog'],
  off: true,
  // A globe whose meridian swings round as if it turns.
  svg: (u) => {
    const disc = circ(15.5, 12.5, 11.5);
    return `<clipPath id="${u.id('gl')}"><path d="${disc}"/></clipPath>
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${disc}" fill="${u.loS}"/></g></g>
      <g clip-path="url(#${u.id('gl')})" fill="none" stroke-width="1.6" stroke="#fff" data-w="stroke">
        <path d="M3 12.5H28"/><path data-n="mer"/>
      </g>
      <path d="${disc}" ${OL}/>`;
  },
  target: (h) => ({ rx: h ? 8.6 : 4.6 }),
  draw(p, E) {
    const rx = p.rx;
    at(E.mer, { d: `M${f(15.5 - rx)} 12.5A${f(rx)} 11.5 0 1 0 ${f(15.5 + rx)} 12.5A${f(rx)} 11.5 0 1 0 ${f(15.5 - rx)} 12.5Z` });
  }
});
