import { defineIcon } from '../packages/core/src/kit.js';
import { liveBody, leanBody, swell, liveMark, place, cornerHead, f, at } from './_arrows.js';

export default defineIcon({
  name: 'expand',
  zh: '展开',
  category: 'arrows',
  tags: ['maximize', 'fullscreen', 'enlarge', 'grow', 'resize', '最大化'],
  // A tile with two arrows pointing out to opposite corners; on hover they dart out as
  // the face swells with them, then settle a little further out.
  svg: (u) => liveBody(u, 'tile') + liveMark('m'),
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const w = swell(p.o), L = 0.6 * p.o + 1.5 * w;
    const k = 4.6 + L, ne = cornerHead(k, -k, 1, -1, 4.2), sw = cornerHead(-k, k, -1, 1, 4.2);
    const m = f(k - 2.1 + 0.4), i = f(1.4 + 0.6 * L);
    leanBody(E, 'tile', 0, 0, 0.6 * w);
    at(E.m, { transform: place(0) });
    at(E.mS, { d: `M${i} ${-i}L${m} ${-m}M${-i} ${i}L${-m} ${m}` });
    at(E.mH, { d: ne.head + sw.head });
  }
});
