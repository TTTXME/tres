import { defineIcon, f, OL, DEEPS, rp, rr, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'pencil',
  zh: '编辑',
  category: 'text',
  tags: ['edit', 'write', 'draw', 'pen'],
  variants: ['plus', 'check', 'x', 'sparkle', 'lock'],
  off: true,
  // A pencil that writes a line on hover.
  svg: (u) => {
    const body = rr(7.1, 9.6, 15, 6.8, 0.8), tip = rp([[22.1, 9.6], [28.4, 13], [22.1, 16.4]], [0.3, 0.9, 0.3]), cap = rr(2.8, 9.6, 4.7, 6.8, 2);
    return `<path data-n="ink" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>
      <g filter="${u.sh}"><g filter="${u.hi}"><g data-n="pen"><path d="${body}" fill="${u.loS}"/><path d="${tip}" fill="${u.upS}"/><path d="${cap}" fill="${u.upS}"/></g></g></g>
      <g data-n="penO"><path d="${body}" ${OL}/><path d="${tip}" ${OL}/><path d="${cap}" ${OL}/></g>`;
  },
  target: (h) => ({ push: h ? 1.6 : 0, ink: h ? 9 : 0 }),
  draw(p, E) {
    const t = `rotate(135 15.5 13) translate(${f(p.push)} 0)`;
    at(E.pen, { transform: t }); at(E.penO, { transform: t });
    at(E.ink, { d: `M6.4 23.6H${f(6.4 + p.ink)}`, opacity: p.ink > 0.2 ? 1 : 0 });
  }
});
