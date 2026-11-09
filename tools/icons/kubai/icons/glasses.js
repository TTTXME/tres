import { defineIcon, f, OL, DEEPS, rp, clamp01, rattle, at } from '../packages/core/src/kit.js';

// The left lens; the right one is its mirror image. The rim is a frame round a clear lens.
const mirror = (pts) => pts.map(([x, y]) => [31 - x, y]).reverse();
const RIM = [[3.4, 6.6], [14, 6.6], [13.6, 18.6], [3.8, 18.6]];
const LENS = [[5.6, 8.8], [11.8, 8.8], [11.4, 16.4], [6, 16.4]];
const rims = rp(RIM, [3.4, 3.4, 4.6, 4.6]) + rp(mirror(RIM), [4.6, 4.6, 3.4, 3.4]);
const lenses = rp(LENS, [2.2, 2.2, 2.6, 2.6]) + rp(mirror(LENS), [2.6, 2.6, 2.2, 2.2]);

export default defineIcon({
  name: 'glasses',
  zh: '眼镜',
  category: 'people',
  tags: ['glasses', 'spectacles', 'eyewear', 'reading', 'vision', 'optical', '近视'],
  // A pair of glasses with a bridge between the rims; on hover they are nudged up and settle level.
  svg: (u) => `<g data-n="g">
    <path d="M13.4 10.6Q15.5 8.6 17.6 10.6" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${rims}" fill="${u.loS}"/></g></g><path d="${rims}" ${OL}/>
    <path d="${lenses}" fill="#fff"/></g>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const o = clamp01(p.o);
    at(E.g, { transform: `translate(0 ${f(-1.2 * o)}) rotate(${f(rattle(o, 5, 2))} 15.5 12.5)` });
  }
});
