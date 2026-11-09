import { defineIcon, f, OL, circ, gear, at } from '../packages/core/src/kit.js';

const GEAR = gear(6, 11.4, 9);

export default defineIcon({
  name: 'settings',
  zh: '设置',
  category: 'interface',
  tags: ['gear', 'cog', 'preferences', 'options', 'config'],
  svg: (u) => `
    <g filter="${u.sh}"><g filter="${u.hi}"><g data-n="gear"><path d="${GEAR}" fill="${u.loS}"/></g></g></g><g data-n="gearO"><path d="${GEAR}" ${OL}/></g>
    <path d="${circ(15.5, 12.5, 5.8)}" fill="#fff"/>
    <g filter="${u.hi}"><path data-n="hub" fill="${u.upS}"/></g>
    <path data-n="hubO" ${OL}/>`,
  target: (h) => ({ rot: h ? 60 : 0, hub: h ? 2.9 : 3.5 }),
  draw(p, E) {
    at(E.gear, { transform: `rotate(${f(p.rot)} 15.5 12.5)` }); at(E.gearO, { transform: `rotate(${f(p.rot)} 15.5 12.5)` });
    const d = circ(15.5, 12.5, p.hub);
    at(E.hub, { d }); at(E.hubO, { d });
  }
});
