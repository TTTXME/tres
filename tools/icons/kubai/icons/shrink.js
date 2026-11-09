import { defineIcon } from '../packages/core/src/kit.js';
import { liveBody, leanBody, swell, liveMark, place, cornerHead, f, at } from './_arrows.js';

export default defineIcon({
  name: 'shrink',
  zh: '收起',
  category: 'arrows',
  tags: ['minimize', 'collapse', 'reduce', 'exit fullscreen', 'resize', '最小化'],
  // A tile with two arrows pointing in from opposite corners; on hover they dart in as
  // the face draws in with them, then settle a little further in.
  svg: (u) => liveBody(u, 'tile') + liveMark('m'),
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const w = swell(p.o), L = 0.5 * p.o + 0.75 * w;
    const k = 1.5 - L, ne = cornerHead(k, -k, -1, 1, 4), sw = cornerHead(-k, k, 1, -1, 4);
    const m = f(k + 2 - 0.4), t = f(5 - 1.4 * L);
    leanBody(E, 'tile', 0, 0, -0.6 * w);
    at(E.m, { transform: place(0) });
    at(E.mS, { d: `M${t} ${-t}L${m} ${-m}M${-t} ${t}L${-m} ${m}` });
    at(E.mH, { d: ne.head + sw.head });
  }
});
