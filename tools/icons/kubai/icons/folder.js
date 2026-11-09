import { defineIcon, KR, f, OL, LINE, rp, rr, at } from '../packages/core/src/kit.js';

const FOLDER_BACK_R = [KR, 1.4, 1.4, KR, KR, KR];

export default defineIcon({
  name: 'folder',
  zh: '文件夹',
  category: 'files',
  tags: ['directory', 'open', 'files'],
  variants: ['plus', 'minus', 'check', 'x', 'alert', 'info', 'up', 'down', 'right', 'clock', 'star', 'heart', 'lock', 'search', 'sync', 'cog', 'sparkle', 'bolt'],
  svg: (u) => `
    <g filter="${u.sh}"><path data-n="back" fill="${u.lo(21)}"/></g>
    <path data-n="backO" ${OL}/>
    <g data-n="file"><path d="${rr(0, 0, 17, 9, 1)}" fill="#fff"/><path d="M5.5 2.3H11.5" ${LINE} stroke-width="1"/></g>
    <g filter="${u.hi}"><path data-n="front" fill="${u.up(18)}"/></g>
    <path data-n="frontO" ${OL}/>`,
  target: (h) => ({
    back: h ? [[4, 3], [11.2, 3], [14.2, 6], [27, 6], [27.6, 23], [4.6, 23]] : [[4, 2], [11.2, 2], [14.2, 5], [27, 5], [27, 23], [4, 23]],
    fx: h ? 7.9 : 7,
    front: h ? [[6.8, 13], [29.3, 13], [27.6, 23], [5.4, 23]] : [[4, 5], [27, 5], [27, 23], [4, 23]],
    fr: h ? 1.8 : KR
  }),
  draw(p, E) {
    const b = rp(p.back, FOLDER_BACK_R), fr = rp(p.front, p.fr);
    at(E.back, { d: b }); at(E.backO, { d: b });
    at(E.file, { transform: `translate(${f(p.fx)} 9)` });
    at(E.front, { d: fr }); at(E.frontO, { d: fr });
  }
});
