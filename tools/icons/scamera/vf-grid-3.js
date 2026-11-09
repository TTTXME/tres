import { defineIcon } from '../kubai/packages/core/src/kit.js';
import { viewfinder, linesMark, segs, gridLines, at } from './_camera.js';

const L = gridLines(3);
export default defineIcon({
  name: 'vf-grid-3',
  zh: '三分网格',
  category: 'camera',
  tags: ['grid', 'rule of thirds', 'composition'],
  // The viewfinder with the rule-of-thirds lines; on press the lines close in on the subject.
  svg: (u) => `${viewfinder(u)}${linesMark}`,
  target: (h) => ({ s: h ? 0.78 : 1 }),
  draw(p, E) { at(E.ln, { d: segs(L, p.s) }); }
});
