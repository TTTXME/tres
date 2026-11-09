import { defineIcon, KR, f, OL, DEEP, rp, rr, circ, at } from '../packages/core/src/kit.js';

const NOTE = rp([[11.6, 8.2], [17.2, 7], [17.2, 9.6], [13.4, 10.4], [13.4, 15.6], [11.6, 15.6]], [0.6, 0.6, 0.4, 0.3, 0.6, 0.6])
  + 'M7.6 15.6A2.9 2.2 0 1 1 13.4 15.6A2.9 2.2 0 1 1 7.6 15.6Z';

export default defineIcon({
  name: 'album',
  zh: '专辑',
  category: 'media',
  tags: ['record', 'cover', 'cd', 'music', 'release', '唱片'],
  variants: ['plus', 'check', 'x', 'heart', 'star', 'down', 'search', 'clock'],
  // An album sleeve with its record half out; on hover the record slides further out and turns.
  svg: (u) => {
    const cover = rr(4, 4.2, 16.6, 16.6, KR);
    return `<g data-n="disc"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(0, 0, 8)}" fill="${u.loS}"/></g></g>
      <path d="${circ(0, 0, 8)}" ${OL}/>
      <g data-n="spin"><path d="M4.16 -3.49A5.4 5.4 0 0 1 5.32 0.94" fill="none" stroke-width="1.6" stroke-linecap="round" stroke="#fff" data-w="stroke"/></g></g>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${cover}" fill="${u.upS}"/></g></g><path d="${cover}" ${OL}/>
    <g transform="translate(0.4 0.3)"><path d="${NOTE}" ${DEEP}/></g>`;
  },
  target: (h) => ({ x: h ? 20.2 : 19, rot: h ? 70 : 0 }),
  draw(p, E) {
    at(E.disc, { transform: `translate(${f(p.x)} 12.5)` });
    at(E.spin, { transform: `rotate(${f(p.rot)})` });
  }
});
