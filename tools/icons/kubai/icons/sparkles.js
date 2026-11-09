import { defineIcon, f, OL, at, clamp01 } from '../packages/core/src/kit.js';
import { sparkle } from './_w2-interface.js';

const BIG = sparkle(9.4), SMALL = sparkle(4.6);

export default defineIcon({
  name: 'sparkles',
  zh: '智能生成',
  category: 'interface',
  tags: ['ai', 'sparkles', 'magic', 'generate', 'smart', 'assistant', 'new', '人工智能', '闪光'],
  // A large and a small four-point sparkle; on hover the large one turns a quarter and the small one twinkles.
  svg: (u) => `<g filter="${u.sh}"><g filter="${u.hi}"><g data-n="b"><path d="${BIG}" fill="${u.loS}"/></g></g></g>
    <g data-n="bO"><path d="${BIG}" ${OL}/></g>
    <g filter="${u.sh}"><g filter="${u.hi}"><g data-n="s"><path d="${SMALL}" fill="${u.upS}"/></g></g></g>
    <g data-n="sO"><path d="${SMALL}" ${OL}/></g>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const o = clamp01(p.o), k = 1 - 0.35 * Math.sin(Math.PI * clamp01(o * 1.5 - 0.2));
    const b = `translate(13.2 14.4) rotate(${f(90 * o)})`, s = `translate(22.6 5.6) scale(${f(k)})`;
    at(E.b, { transform: b }); at(E.bO, { transform: b });
    at(E.s, { transform: s }); at(E.sO, { transform: s });
  }
});
