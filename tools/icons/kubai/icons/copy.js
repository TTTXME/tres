import { defineIcon, KR, f, OL, DEEPS, rr, lines, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'copy',
  zh: '复制',
  category: 'text',
  tags: ['duplicate', 'clone', 'paste'],
  variants: ['plus', 'check', 'x'],
  // Two sheets; the copy slides out from the original on hover.
  svg: (u) => {
    const sheet = rr(0, 0, 13.6, 15.6, KR);
    return `<g data-n="b"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${sheet}" fill="${u.loS}"/></g></g><path d="${sheet}" ${OL}/></g>
      <g data-n="fr"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${sheet}" fill="${u.up(15.6)}"/></g></g><path d="${sheet}" ${OL}/>
        <path d="${lines(2.6, 4.2, [8.4, 5.6])}" class="ln" ${DEEPS} stroke-opacity=".55"/></g>`;
  },
  target: (h) => ({ b: h ? [4.6, 1.6] : [5.2, 2.2], fr: h ? [12.8, 7.8] : [12.2, 7.2] }),
  draw(p, E) {
    at(E.b, { transform: `translate(${f(p.b[0])} ${f(p.b[1])})` });
    at(E.fr, { transform: `translate(${f(p.fr[0])} ${f(p.fr[1])})` });
  }
});
