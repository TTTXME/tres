import { defineIcon, f, OL, DEEP, circ, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'eye',
  zh: '眼睛',
  category: 'interface',
  tags: ['view', 'see', 'visible', 'watch', 'preview'],
  off: true,
  // An eye that glances to the side on hover.
  svg: (u) => {
    const eye = 'M3.6 12.5C7 6.6 11 4.2 15.5 4.2C20 4.2 24 6.6 27.4 12.5C24 18.4 20 20.8 15.5 20.8C11 20.8 7 18.4 3.6 12.5Z';
    return `<clipPath id="${u.id('eye')}"><path d="${eye}"/></clipPath>
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${eye}" fill="${u.loS}"/></g></g>
      <g clip-path="url(#${u.id('eye')})"><g data-n="iris"><g filter="${u.hi}"><path d="${circ(0, 0, 5.4)}" fill="${u.upS}"/></g><path d="${circ(0, 0, 5.4)}" ${OL}/>
        <circle data-n="pup" ${DEEP}/></g></g>
      <path d="${eye}" ${OL}/>`;
  },
  target: (h) => ({ x: h ? 2.6 : 0, pup: h ? 2.9 : 2.3 }),
  draw(p, E) { at(E.iris, { transform: `translate(${f(15.5 + p.x)} 12.5)` }); at(E.pup, { r: f(p.pup) }); }
});
