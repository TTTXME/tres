import { defineIcon, KR, CR, f, OL, ACC, R, rp, rr, lines, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'split-view',
  zh: '分屏',
  category: 'interface',
  tags: ['columns', 'layout', 'side by side', 'window'],
  variants: ['plus', 'x'],
  // A window whose single page splits into two on hover; the new page carries the
  // opened result's line.
  svg: (u) => {
    const body = rr(4, 3.3, 23, 18.4, KR), head = rp(R(4, 3.3, 23, 4.6), [KR, KR, 0, 0]);
    return `
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${body}" fill="${u.loS}"/></g></g>
    <path d="${body}" ${OL}/>
    <path data-n="L" fill="#fff"/>
    <g data-n="Rg"><path data-n="Rp" fill="#fff"/><path data-n="Ra" ${ACC}/></g>
    <g filter="${u.hi}"><path d="${head}" fill="${u.upS}"/></g>
    <path d="${head}" ${OL}/>`;
  },
  target: (h) => ({ L: h ? [6, 9.9, 8.9, 9.8] : [6, 9.9, 19, 9.8], Rr: h ? [16.1, 9.9, 8.9, 9.8, 1] : [25, 9.9, 0, 9.8, 0] }),
  draw(p, E) {
    const [x, y, w, hh] = p.L;
    at(E.L, { d: rr(x, y, w, hh, CR) });
    const [rx, ry, rw, rh, op] = p.Rr;
    at(E.Rp, { d: rr(rx, ry, rw, rh, CR) });
    at(E.Ra, { d: lines(rx + 1.8, ry + 2.6, [Math.max(0, rw - 3.6)]) });
    at(E.Rg, { opacity: f(op) });
  }
});
