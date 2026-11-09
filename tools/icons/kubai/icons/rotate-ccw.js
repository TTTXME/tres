import { defineIcon, DEEP, arcArrow } from '../packages/core/src/kit.js';
import { disc, STROKE, place, swell, at } from './_arrows.js';

const ring = (r) => arcArrow(40, 310, r, 2.6, 3.2), ARC = ring(4.6);

export default defineIcon({
  name: 'rotate-ccw',
  zh: '逆时针旋转',
  category: 'arrows',
  tags: ['rotate', 'counterclockwise', 'anticlockwise', 'undo', 'turn', '旋转'],
  // A disc with a counter-clockwise circular arrow that spins a full turn on hover,
  // opening out a little as it goes.
  svg: (u) => disc(u) + `<g data-n="arr"><path data-n="aS" d="${ARC.arc}" ${STROKE}/><path data-n="aH" d="${ARC.head}" ${DEEP}/></g>`,
  target: (h) => ({ rot: h ? 360 : 0 }),
  draw(p, E) {
    const a = ring(4.6 + 0.6 * swell(p.rot / 360));
    at(E.aS, { d: a.arc }); at(E.aH, { d: a.head });
    at(E.arr, { transform: `${place(-p.rot)} scale(-1 1)` });
  }
});
