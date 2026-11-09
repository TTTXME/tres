import { defineIcon, CLOCK_MINUTES, f, OL, DEEP, DEEPS, SOFTS, rp, circ, at } from '../packages/core/src/kit.js';
import { polar } from './_security-time-health.js';

const CX = 16.8, CY = 13.4, RA = 10.6;

export default defineIcon({
  name: 'history',
  zh: '历史记录',
  category: 'time',
  tags: ['history', 'recent', 'past', 'log', 'undo', 'activity', '最近'],
  // A clock ringed by an arrow running back round it; on hover the arrow reaches
  // further back.
  svg: (u) => `
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(CX, CY, 7.4)}" fill="${u.loS}"/></g></g>
    <path d="${circ(CX, CY, 7.4)}" ${OL}/>
    <path d="${circ(CX, CY, 4.8)}" fill="#fff"/>
    <path d="M${CX} ${CY}V${CY - 2.4}" transform="rotate(${CLOCK_MINUTES / 2} ${CX} ${CY})" stroke-width="2.6" stroke-linecap="round" ${SOFTS}/>
    <path d="M${CX} ${CY}V${CY - 3.6}" transform="rotate(${(CLOCK_MINUTES * 6) % 360} ${CX} ${CY})" stroke-width="2" stroke-linecap="round" ${DEEPS}/>
    <circle cx="${CX}" cy="${CY}" r="1.4" ${DEEP}/>
    <path data-n="arc" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>
    <path data-n="head" ${DEEP}/>`,
  target: (h) => ({ a: h ? -140 : -105 }),
  draw(p, E) {
    // The arc runs anticlockwise from the top right to angle a; the head points on along it.
    const s = polar(CX, CY, RA, 30), e = polar(CX, CY, RA, p.a - 2), r = p.a * Math.PI / 180;
    at(E.arc, { d: `M${f(s[0])} ${f(s[1])}A${RA} ${RA} 0 ${30 - p.a + 2 > 180 ? 1 : 0} 0 ${f(e[0])} ${f(e[1])}` });
    const o = polar(CX, CY, RA + 2.6, p.a), i = polar(CX, CY, RA - 1.6, p.a), t = [-Math.cos(r), -Math.sin(r)];
    const m = polar(CX, CY, RA + 0.5, p.a), tip = [m[0] + t[0] * 3.4, m[1] + t[1] * 3.4];
    at(E.head, { d: rp([o, tip, i], [0.5, 0.6, 0.5]) });
  }
});
