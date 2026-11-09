import { defineIcon, f, OL, at } from '../packages/core/src/kit.js';
import { FACE } from './_comm-people.js';

export default defineIcon({
  name: 'smile',
  zh: '微笑',
  category: 'people',
  tags: ['face', 'happy', 'emoji', 'emotion', 'feedback', 'good', '开心'],
  // A smiling face; on hover the smile widens and the eyes lift.
  svg: (u) => `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${FACE}" fill="${u.loS}"/></g></g><path d="${FACE}" ${OL}/>
    <g fill="#fff" data-w="fill"><circle data-n="e1" cx="11.4" r="1.7"/><circle data-n="e2" cx="19.6" r="1.7"/></g>
    <path data-n="m" fill="none" stroke-width="2.2" stroke-linecap="round" stroke="#fff" data-w="stroke"/>`,
  target: (h) => ({ m: h ? [8.8, 14, 22.2] : [10.8, 15.4, 19.2], ey: h ? 8.4 : 9.8 }),
  draw(p, E) {
    const [x, y, c] = p.m;
    at(E.m, { d: `M${f(x)} ${f(y)}Q15.5 ${f(c)} ${f(31 - x)} ${f(y)}` });
    at(E.e1, { cy: f(p.ey) }); at(E.e2, { cy: f(p.ey) });
  }
});
