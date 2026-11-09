import { defineIcon, KR, f, OL, rr, at, clamp01 } from '../packages/core/src/kit.js';

// A caption row from x 7.8 to `e`, broken into two words at `s` with a gap of 2 * `g`.
const row = (y, s, g, e) => `M7.8 ${y}H${f(s - g)}M${f(s + g)} ${y}H${f(e)}`;
const mix = (a, b, k) => a + (b - a) * k;
// Each line as [split, end]: the caption on screen, the short stub it clears to, the next caption.
const OLD = [[12.2, 23.2], [18.8, 23.2]], STUB = [[9, 10], [9, 10]], NEXT = [[18.4, 23.2], [11.4, 19.6]];

export default defineIcon({
  name: 'subtitles',
  zh: '字幕',
  category: 'media',
  tags: ['captions', 'cc', 'closed captions', 'transcript', 'text track'],
  off: true,
  // A screen with two caption lines along its foot; on hover they clear and the next caption types in.
  svg: (u) => {
    const body = rr(4, 3.3, 23, 18.4, KR);
    return `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${body}" fill="${u.loS}"/></g></g><path d="${body}" ${OL}/>
    <path data-n="cap" fill="none" stroke-width="2.2" stroke-linecap="round" stroke="#fff" data-w="stroke"/>`;
  },
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    // The lower line runs a little behind the upper one; the word gap closes as a line clears.
    const line = (i, y) => {
      const t = clamp01(p.o * 1.25 - i * 0.25);
      const k = t < 0.5 ? t * 2 : 2 - t * 2, [a, b] = t < 0.5 ? [OLD[i], STUB[i]] : [NEXT[i], STUB[i]];
      return row(y, mix(a[0], b[0], k), 1.8 * (1 - k), mix(a[1], b[1], k));
    };
    at(E.cap, { d: line(0, 13.4) + line(1, 17.4) });
  }
});
