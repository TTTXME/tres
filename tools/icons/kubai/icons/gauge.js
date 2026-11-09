import { defineIcon, f, OL, rp, at, clamp01, rattle } from '../packages/core/src/kit.js';

const BODY = 'M4 16.4A11.5 11.5 0 0 1 27 16.4V18.8Q27 21 24.8 21H6.2Q4 21 4 18.8Z';

export default defineIcon({
  name: 'gauge',
  zh: '仪表盘',
  category: 'charts',
  tags: ['dashboard', 'speedometer', 'meter', 'performance', 'speed', 'kpi', '速度'],
  variants: ['plus', 'check', 'up', 'down', 'cog'],
  // A dial gauge; on hover the needle swings up the scale, overshoots and settles.
  svg: (u) => `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${BODY}" fill="${u.loS}"/></g></g><path d="${BODY}" ${OL}/>
    <path d="M7.1 16.4A8.4 8.4 0 0 1 23.9 16.4Z" fill="#fff"/>
    <path data-n="nd" d="${rp([[14.2, 16.4], [15.5, 9.2], [16.8, 16.4]], [0, 0.5, 0])}" data-t="fill:#2F6FE0"/>
    <circle cx="15.5" cy="16.4" r="1.9" data-t="fill:#2F6FE0"/>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const o = clamp01(p.o);
    at(E.nd, { transform: `rotate(${f(-55 + 112 * o + rattle(o, 20, 2))} 15.5 16.4)` });
  }
});
