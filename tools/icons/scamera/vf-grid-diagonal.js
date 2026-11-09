import { defineIcon } from '../kubai/packages/core/src/kit.js';
import { viewfinder, linesMark, segs, SCREEN, at } from './_camera.js';

const [x, y, w, h] = SCREEN, i = 1.6;
const L = [[x + i, y + i, x + w - i, y + h - i], [x + w - i, y + i, x + i, y + h - i], [x + w / 2, y + i, x + w / 2, y + h - i], [x + i, y + h / 2, x + w - i, y + h / 2]];
export default defineIcon({
  name: 'vf-grid-diagonal',
  zh: '对角线网格',
  category: 'camera',
  tags: ['grid', 'diagonal', 'composition'],
  // The viewfinder with its diagonals and centre cross; on press they close in on the centre.
  svg: (u) => `${viewfinder(u)}${linesMark}`,
  target: (hv) => ({ s: hv ? 0.8 : 1 }),
  draw(p, E) { at(E.ln, { d: segs(L, p.s) }); }
});
