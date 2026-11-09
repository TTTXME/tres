import { defineIcon, circ } from '../packages/core/src/kit.js';
import { figure, pose } from './_w2-people.js';

const G = { S: [18.4, 11.2], H: [15.4, 15.4], A: [18.2, 11], tw: 5, arm: [3.6, 3.4], aw: 2.8, leg: [4.6, 4.4], lw: 3.2 };
const FWD_LEG = [58, -12], BACK_LEG = [-42, -112], FWD_ARM = [44, 124], BACK_ARM = [-62, -24];

export default defineIcon({
  name: 'person-running',
  zh: '跑步',
  category: 'people',
  tags: ['run', 'running', 'jog', 'sprint', 'exercise', 'fitness', 'fast', '跑'],
  // A person running to the right; on hover they take one stride, arms and legs pumping through.
  svg: (u) => figure(u, circ(21, 5, 2.8)),
  target: (h) => (h
    ? { nl: BACK_LEG, fl: FWD_LEG, na: FWD_ARM, fa: BACK_ARM }
    : { nl: FWD_LEG, fl: BACK_LEG, na: BACK_ARM, fa: FWD_ARM }),
  draw(p, E) { pose(E, G, p); }
});
