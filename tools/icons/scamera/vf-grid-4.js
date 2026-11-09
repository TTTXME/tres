import { defineIcon } from '../kubai/packages/core/src/kit.js';
import { viewfinder, linesMark, segs, gridLines, at } from './_camera.js';

const L = gridLines(4);
export default defineIcon({
  name: 'vf-grid-4',
  zh: '四分网格',
  category: 'camera',
  tags: ['grid', 'quarters', 'composition'],
  // The viewfinder with a 4 x 4 grid; on press the lines close in on the centre.
  svg: (u) => `${viewfinder(u)}${linesMark}`,
  target: (h) => ({ s: h ? 0.8 : 1 }),
  draw(p, E) { at(E.ln, { d: segs(L, p.s) }); }
});
