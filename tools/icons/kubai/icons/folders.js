import { defineIcon, KR, f, OL, LINE, rp, rr, at } from '../packages/core/src/kit.js';

const TAB_R = [KR, 1.2, 1.2, KR, KR, KR];

export default defineIcon({
  name: 'folders',
  zh: '多个文件夹',
  category: 'files',
  tags: ['directories', 'folders', 'collection', 'projects', 'workspace', '文件夹'],
  variants: ['plus', 'minus', 'check', 'x', 'alert', 'up', 'down', 'right', 'clock', 'star', 'heart', 'lock', 'search', 'sync', 'cog', 'sparkle'],
  // Two folders, one behind the other; on hover the front one opens like the folder icon.
  svg: (u) => {
    const rb = rp([[7.2, 1.4], [12.6, 1.4], [15, 3.8], [27, 3.8], [27, 17], [7.2, 17]], TAB_R), rf = rr(7.2, 3.8, 19.8, 13.2, KR);
    return `
    <g data-n="rear"><g filter="${u.sh}"><path d="${rb}" fill="${u.loS}"/></g><path d="${rb}" ${OL}/>
      <g filter="${u.hi}"><path d="${rf}" fill="${u.upS}"/></g><path d="${rf}" ${OL}/></g>
    <g filter="${u.sh}"><path data-n="back" fill="${u.lo(15.4)}"/></g>
    <path data-n="backO" ${OL}/>
    <g data-n="file"><path d="${rr(0, 0, 14.6, 8, 1)}" fill="#fff"/><path d="M4.6 2.2H10" ${LINE} stroke-width="1"/></g>
    <g filter="${u.hi}"><path data-n="front" fill="${u.up(12.8)}"/></g>
    <path data-n="frontO" ${OL}/>`;
  },
  target: (h) => ({
    rear: h ? -0.8 : 0,
    back: h ? [[4, 9], [9.8, 9], [12.4, 11.6], [24, 11.6], [24.5, 23.4], [4.5, 23.4]] : [[4, 8], [9.8, 8], [12.4, 10.6], [24, 10.6], [24, 23.4], [4, 23.4]],
    fx: h ? 7.4 : 6.6,
    front: h ? [[6.4, 16.2], [26, 16.2], [24.5, 23.4], [5.2, 23.4]] : [[4, 10.6], [24, 10.6], [24, 23.4], [4, 23.4]],
    fr: h ? 1.8 : KR
  }),
  draw(p, E) {
    at(E.rear, { transform: `translate(0 ${f(p.rear)})` });
    const b = rp(p.back, TAB_R), fr = rp(p.front, p.fr);
    at(E.back, { d: b }); at(E.backO, { d: b });
    at(E.file, { transform: `translate(${f(p.fx)} 12.2)` });
    at(E.front, { d: fr }); at(E.frontO, { d: fr });
  }
});
