import { defineIcon, KR, CR, f, OL, rr, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'picture-in-picture',
  zh: '画中画',
  category: 'media',
  tags: ['pip', 'mini player', 'float', 'video'],
  // A small player floats in the corner of the page and pops out of the window on hover.
  svg: (u) => {
    const body = rr(4, 3.3, 23, 18.4, KR), mini = rr(0, 0, 9.6, 7, 1.6);
    return `
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${body}" fill="${u.loS}"/></g></g>
    <path d="${body}" ${OL}/>
    <path d="${rr(6, 5.3, 19, 14.4, CR)}" fill="#fff"/>
    <g data-n="mini"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${mini}" fill="${u.upS}"/></g></g><path d="${mini}" ${OL}/>
      <path d="M3.8 1.8L6.6 3.5L3.8 5.2Z" stroke-width="1" stroke-linejoin="round" data-t="fill:#2F6FE0" data-ck="c"/></g>`;
  },
  target: (h) => ({ mini: h ? [17.2, 12.8, 1.1] : [14, 10.6, 1] }),
  draw(p, E) {
    at(E.mini, { transform: `translate(${f(p.mini[0])} ${f(p.mini[1])}) scale(${f(p.mini[2])})` });
  }
});
