import { defineIcon, KR, f, rp } from '../packages/core/src/kit.js';
import { rig, pose } from './_tools-dev.js';

// An L of three blocks and a fourth block that belongs in its open corner.
const S = 8.4, X0 = 4.4, Y0 = 6.8;
const L = rp([[X0, Y0], [X0 + S, Y0], [X0 + S, Y0 + S], [X0 + 2 * S, Y0 + S], [X0 + 2 * S, Y0 + 2 * S], [X0, Y0 + 2 * S]], [KR, KR, 0.4, KR, KR, KR]);
const BLOCK = rp([[X0 + S, Y0], [X0 + 2 * S, Y0], [X0 + 2 * S, Y0 + S], [X0 + S, Y0 + S]], KR);

export default defineIcon({
  name: 'blocks',
  zh: '积木',
  category: 'development',
  tags: ['blocks', 'modules', 'apps', 'integrations', 'build', 'components', '模块'],
  variants: ['plus', 'check', 'x', 'search'],
  // Three joined blocks and a loose one; on hover the loose block drops into the gap.
  svg: (u) => rig(u, 'l', [{ d: L, fill: u.loS }]) + rig(u, 'b', [{ d: BLOCK, fill: u.upS }]),
  target: (h) => ({ k: h ? 0 : 1 }),
  draw(p, E) {
    const k = p.k;
    pose(E, 'b', 1, `translate(${f(4.2 * k)} ${f(-4.2 * k)}) rotate(${f(12 * k)} ${X0 + 1.5 * S} ${Y0 + 0.5 * S})`);
  }
});
