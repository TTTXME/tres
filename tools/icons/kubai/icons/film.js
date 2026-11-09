import { defineIcon, KR, f, OL, rr, at } from '../packages/core/src/kit.js';

// Frames repeat every 9.2 units, sprocket holes every 4.6: one frame advance is a full period.
const PITCH = 9.2;
const STRIP = (() => {
  let win = '', holes = '';
  for (let k = -2; k <= 3; k++) win += rr(12 + k * PITCH, 9.4, 7, 6.2, 1);
  for (let k = -3; k <= 6; k++) {
    const x = 6.3 + k * PITCH / 2 - 1.2;
    holes += rr(x, 5.2, 2.4, 2.4, 0.7) + rr(x, 17.4, 2.4, 2.4, 0.7);
  }
  return { win, holes };
})();

export default defineIcon({
  name: 'film',
  zh: '胶片',
  category: 'media',
  tags: ['film strip', 'movie', 'cinema', 'reel', 'frames', '电影'],
  variants: ['plus', 'check', 'x', 'down', 'up', 'clock', 'star', 'heart', 'search', 'lock'],
  // A film strip with frames and sprocket holes; on hover it advances one frame.
  svg: (u) => {
    const body = rr(4, 3.3, 23, 18.4, KR);
    return `<clipPath id="${u.id('in')}"><path d="${body}"/></clipPath>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${body}" fill="${u.loS}"/></g></g>
    <g clip-path="url(#${u.id('in')})"><g data-n="strip">
      <path d="${STRIP.win}" fill="#fff"/>
      <path d="${STRIP.holes}" fill="#fff" data-w="fill"/></g></g>
    <path d="${body}" ${OL}/>`;
  },
  target: (h) => ({ x: h ? -PITCH : 0 }),
  draw(p, E) { at(E.strip, { transform: `translate(${f(p.x)} 0)` }); }
});
