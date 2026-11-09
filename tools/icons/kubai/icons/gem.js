import { defineIcon, f, OL, R, rp, at, clamp01 } from '../packages/core/src/kit.js';
import { back, front } from './_commerce-charts.js';

const sparkle = rp([0, 1, 2, 3, 4, 5, 6, 7].map((i) => {
  const a = i * Math.PI / 4, r = i % 2 ? 1.1 : 3.4;
  return [Math.sin(a) * r, -Math.cos(a) * r];
}), 0.3);

export default defineIcon({
  name: 'gem',
  zh: '宝石',
  category: 'commerce',
  tags: ['diamond', 'jewel', 'premium', 'vip', 'luxury', '钻石', '会员'],
  variants: ['plus', 'check', 'star', 'sparkle'],
  // A cut gem; on hover a glint flashes beside its crown.
  svg: (u) => {
    const gem = rp([[9.6, 3.6], [21.4, 3.6], [27, 9.8], [15.5, 22.8], [4, 9.8]], [1.6, 1.6, 1.2, 1.4, 1.2]);
    const crown = rp([[9.6, 3.6], [21.4, 3.6], [27, 9.8], [4, 9.8]], [1.6, 1.6, 1.2, 1.2]);
    return `${back(u, gem, u.loS)}${front(u, crown, u.upS)}
      <path d="M11.2 13.2L15.5 19L19.8 13.2" fill="none" class="ln" stroke="#fff" data-w="stroke" stroke-linecap="round" stroke-linejoin="round"/>
      <g data-n="sp"><g filter="${u.hi}"><path d="${sparkle}" fill="${u.upS}"/></g><path d="${sparkle}" ${OL}/></g>`;
  },
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const o = clamp01(p.o);
    at(E.sp, { transform: `translate(25.4 2.6) rotate(${f(-45 * (1 - o))}) scale(${f(Math.max(0.001, o))})` });
  }
});
