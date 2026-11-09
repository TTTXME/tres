import { defineIcon, f, OL, DEEPS, rr, lines, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'lightbulb',
  zh: '灯泡',
  category: 'home',
  tags: ['idea', 'light', 'lamp', 'tip'],
  variants: ['plus', 'check', 'sparkle', 'bolt'],
  off: true,
  // A bulb that lights up on hover, its glow spreading to fill the glass.
  svg: (u) => {
    const glass = 'M11 15.6C8.8 14 7.4 11.6 7.4 9A8.1 8.1 0 0 1 23.6 9C23.6 11.6 22.2 14 20 15.6V17.6H11Z';
    const base = rr(10.8, 16.8, 9.4, 6.4, 2);
    return `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${glass}" fill="${u.upS}"/></g></g><path d="${glass}" ${OL}/>
      <circle data-n="glow" cx="15.5" cy="9.4" r="5" fill="#fff"/>
      <path d="M12.8 12.6L14.2 9.8L15.5 11.8L16.8 9.8L18.2 12.6" fill="none" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" ${DEEPS}/>
      <g filter="${u.hi}"><path d="${base}" fill="${u.loS}"/></g><path d="${base}" ${OL}/>
      <path d="${lines(12.8, 20, [5.4])}" class="ln" stroke="#fff" data-w="stroke"/>`;
  },
  target: (h) => ({ glow: h ? 1 : 0, r: h ? 7.2 : 5 }),
  draw(p, E) { at(E.glow, { r: f(p.r), opacity: f(p.glow) }); }
});
