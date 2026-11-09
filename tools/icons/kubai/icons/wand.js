import { defineIcon, f, DEEP, rr, star, at, clamp01, rattle } from '../packages/core/src/kit.js';
import { rig, pose } from './_tools-dev.js';

const STICK = rr(0, -1.7, 19.6, 3.4, 1.7);
const SPARKS = [[8.8, 6.2, 2.8], [25, 16.6, 2.5]];

export default defineIcon({
  name: 'wand',
  zh: '魔法棒',
  category: 'tools',
  tags: ['magic', 'auto', 'enhance', 'ai', 'sparkle', '魔棒'],
  // A magic wand with a star on its tip; on hover it flicks, the star turns and sparks twinkle.
  svg: (u) => rig(u, 'w', [{ d: STICK, fill: u.loS }]) + rig(u, 's', [{ d: star(6, 2.8), fill: u.upS }])
    + SPARKS.map((s, i) => `<path data-n="k${i}" d="${star(s[2], s[2] * 0.36, 4)}" ${DEEP}/>`).join(''),
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const o = clamp01(p.o), a = rattle(o, 8, 2);
    const stick = `rotate(${f(a)} 4.6 23) translate(4.6 23) rotate(-45)`;
    pose(E, 'w', 1, stick);
    const tip = [4.6 + 15.4 * Math.cos((-45 + a) * Math.PI / 180), 23 + 15.4 * Math.sin((-45 + a) * Math.PI / 180)];
    pose(E, 's', 1, `translate(${f(tip[0] + 4.4)} ${f(tip[1] - 4.2)}) rotate(${f(72 * o)})`);
    SPARKS.forEach((s, i) => at(E['k' + i], { transform: `translate(${s[0]} ${s[1]}) scale(${f(1 - 0.5 * Math.sin(clamp01(o * 1.4 - i * 0.3) * Math.PI))})` }));
  }
});
