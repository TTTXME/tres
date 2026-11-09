import { defineIcon, f, OL, at } from '../packages/core/src/kit.js';

const X0 = 5.6, X1 = 25.4, A = 1.5, LAMBDA = 8, T = 4;
const ribbon = (y, ph) => {
  const top = [], n = 48;
  for (let i = 0; i <= n; i++) {
    const x = X0 + (X1 - X0) * i / n;
    top.push([x, y + A * Math.sin(2 * Math.PI * (x - X0) / LAMBDA - ph)]);
  }
  const bot = top.map(([x, yy]) => [x, yy + T]).reverse(), pts = (a) => a.map(([x, yy]) => `${f(x)} ${f(yy)}`).join('L');
  // Round ends: half circles from the top edge to the bottom edge.
  return `M${pts(top)}A${T / 2} ${T / 2} 0 0 1 ${pts(bot)}A${T / 2} ${T / 2} 0 0 1 ${pts(top.slice(0, 1))}Z`;
};

export default defineIcon({
  name: 'waves',
  zh: '海浪',
  category: 'nature',
  tags: ['water', 'sea', 'ocean', 'wave', 'beach', 'swim', '水'],
  // Two rolling bands of water; on hover the waves roll on by one swell.
  svg: (u) => `<g filter="${u.sh}"><g filter="${u.hi}"><path data-n="w1" fill="${u.loS}"/></g></g><path data-n="w1O" stroke-linejoin="round" ${OL}/>
    <g filter="${u.sh}"><g filter="${u.hi}"><path data-n="w2" fill="${u.upS}"/></g></g><path data-n="w2O" stroke-linejoin="round" ${OL}/>`,
  target: (h) => ({ ph: h ? Math.PI * 2 : 0 }),
  draw(p, E) {
    const a = ribbon(6.4, p.ph), b = ribbon(14.4, p.ph);
    at(E.w1, { d: a }); at(E.w1O, { d: a }); at(E.w2, { d: b }); at(E.w2O, { d: b });
  }
});
