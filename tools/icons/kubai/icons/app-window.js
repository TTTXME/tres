import { defineIcon, KR, CR, f, OL, ACC, DEEP, R, rp, rr, at } from '../packages/core/src/kit.js';
import { WIN, back, front } from './_interface.js';

const PAGE = rr(6, 10.9, 19, 8.8, CR);

export default defineIcon({
  name: 'app-window',
  zh: '应用窗口',
  category: 'layout',
  tags: ['window', 'app', 'browser', 'program', 'desktop', '窗口'],
  variants: ['plus', 'x', 'cog', 'lock'],
  // An app window; on hover its page scrolls up a line.
  svg: (u) => `${back(u, WIN)}<path d="${PAGE}" fill="#fff"/>
    <clipPath id="${u.id('pg')}"><path d="${PAGE}"/></clipPath>
    <g clip-path="url(#${u.id('pg')})"><path data-n="ln" ${ACC}/></g>
    ${front(u, rp(R(4, 3.3, 23, 4.6), [KR, KR, 0, 0]))}
    ${[7.2, 10.6, 14].map((x) => `<circle cx="${x}" cy="5.6" r="1.2" ${DEEP}/>`).join('')}`,
  target: (h) => ({ y: h ? -3.4 : 0 }),
  draw(p, E) {
    const y = p.y;
    at(E.ln, { d: [[13.4, 11.6], [16.8, 7.4], [20.6, 9.8]].map(([ly, w]) => `M8.6 ${f(ly + y)}H${f(8.6 + w)}`).join('') });
  }
});
