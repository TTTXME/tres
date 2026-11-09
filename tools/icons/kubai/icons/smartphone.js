import { defineIcon, CR, f, OL, DEEPS, rr, lines, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'smartphone',
  zh: '手机',
  category: 'devices',
  tags: ['phone', 'mobile', 'cellphone', 'notification'],
  variants: ['check', 'x', 'lock', 'sync', 'bolt', 'alert', 'down', 'up', 'cog'],
  off: true,
  // A phone whose notification card slides in on hover.
  svg: (u) => {
    const body = rr(7.3, 1, 16.4, 23, 3.4), card = rr(0, 0, 10.4, 4.4, 1.4);
    return `<g data-n="ph">
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${body}" fill="${u.loS}"/></g></g><path d="${body}" ${OL}/>
      <path d="${rr(9.4, 3.4, 12.2, 16.6, CR)}" fill="#fff"/>
      <g data-n="card"><g filter="${u.hi}"><path d="${card}" fill="${u.upS}"/></g><path d="${card}" ${OL}/><path d="${lines(1.8, 2, [4.4])}" class="ln" ${DEEPS}/></g>
    </g>`;
  },
  target: (h) => ({ card: h ? [10.3, 6.2, 1] : [10.3, 3.4, 0], tilt: h ? -5 : 0 }),
  draw(p, E) {
    at(E.card, { transform: `translate(${f(p.card[0])} ${f(p.card[1])})`, opacity: f(p.card[2]) });
    at(E.ph, { transform: `rotate(${f(p.tilt)} 15.5 12.5)` });
  }
});
