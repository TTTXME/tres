// Shared geometry for the arrow icons. Two bodies carry the marks: a disc on the circle
// keyline and a tile on the square keyline, each a mid-tone back with a lighter inset
// front. Marks are deep 2.2 strokes with solid heads, drawn in coordinates centred on the
// body and placed with a transform, so every direction reuses the same shapes.
import { defineIcon, KR, OL, DEEP, DEEPS, f, circ, rr, rp, arrow, at, bump } from '../packages/core/src/kit.js';

export const CX = 15.5, CY = 12.5;
/** Radius of the disc's lighter front; the band round it stays wider than 2. */
export const DISC_IN = 8.4;
/** Half the side of the tile's lighter front. */
export const TILE_IN = 7.3;

const DISC_D = circ(CX, CY, 11.5), DISC_F = circ(CX, CY, DISC_IN);
const TILE_D = rr(5.2, 2.2, 20.6, 20.6, KR), TILE_F = rr(CX - TILE_IN, CY - TILE_IN, TILE_IN * 2, TILE_IN * 2, 1.8);

/** A back piece: mid-tone fill with shadow and top glow, plus its outline. */
export const back = (u, d) => `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${d}" fill="${u.loS}"/></g></g><path d="${d}" ${OL}/>`;
/** A front piece: light fill with top glow, plus its outline. */
export const front = (u, d) => `<g filter="${u.hi}"><path d="${d}" fill="${u.upS}"/></g><path d="${d}" ${OL}/>`;

/** The disc body: back disc and its lighter inset front. */
export const disc = (u) => back(u, DISC_D) + front(u, DISC_F);
/** The tile body: back square and its lighter inset front. */
export const tile = (u) => back(u, TILE_D) + front(u, TILE_F);

/** Stroke attributes of a deep 2.2 mark. */
export const STROKE = `fill="none" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" ${DEEPS}`;
/** A deep mark: a stroked path under its solid heads. */
export const mark = (shaft, heads, n) => `<g${n ? ` data-n="${n}"` : ''}><path d="${shaft}" ${STROKE}/><path d="${heads}" ${DEEP}/></g>`;
/** A mark whose paths draw() rewrites: elements `${n}S` (stroke) and `${n}H` (heads). */
export const liveMark = (n) => `<g data-n="${n}"><path data-n="${n}S" ${STROKE}/><path data-n="${n}H" ${DEEP}/></g>`;

/** Rotates a point about the origin by a degrees (clockwise on screen). */
export const rot = ([x, y], a) => {
  const r = a * Math.PI / 180, c = Math.cos(r), s = Math.sin(r);
  return [x * c - y * s, x * s + y * c];
};
/** Places a mark drawn round the origin: rotate by a, then shift (dx, dy) on the body centre. */
export const place = (a, dx = 0, dy = 0) => `translate(${f(CX + dx)} ${f(CY + dy)}) rotate(${f(a)})`;

/** Unit step along a direction given in degrees (0 points right, 90 down). */
export const dir = (a) => rot([1, 0], a);

/** A straight arrow pointing right, centred on the origin, from tail t to tip p. */
export const flatArrow = (t, p, head, half) => arrow(t, 0, p, 0, head, half);

/**
 * A right-angled solid head whose square corner is (x, y) and whose legs run back along
 * -ux and -uy (signs only) for length l, plus the shaft point at the middle of its long side.
 */
export function cornerHead(x, y, sx, sy, l) {
  const head = rp([[x - sx * l, y], [x, y], [x, y - sy * l]], [0.5, 0.6, 0.5]);
  return { head, mid: [x - sx * l / 2, y - sy * l / 2] };
}

/** The lighter front of a disc or tile, its centre shifted by (dx, dy) and grown by g. */
const faceAt = (body, dx = 0, dy = 0, g = 0) => (body === 'tile'
  ? rr(CX + dx - TILE_IN - g, CY + dy - TILE_IN - g, (TILE_IN + g) * 2, (TILE_IN + g) * 2, 1.8)
  : circ(CX + dx, CY + dy, DISC_IN + g));

/** A disc or tile body whose lighter front leanBody() can move; same markup as disc()/tile(). */
export const liveBody = (u, body) => {
  const d = faceAt(body);
  return back(u, body === 'tile' ? TILE_D : DISC_D)
    + `<g filter="${u.hi}"><path data-n="fc" d="${d}" fill="${u.upS}"/></g><path data-n="fcO" d="${d}" ${OL}/>`;
};
/** Shifts the front of a liveBody() by (dx, dy) and grows it by g. */
export const leanBody = (E, body, dx = 0, dy = 0, g = 0) => {
  const d = faceAt(body, dx, dy, g);
  at(E.fc, { d }); at(E.fcO, { d });
};

/** An out-and-back swell, 0 at rest and at the end of the hover. */
export const swell = bump;

/**
 * A disc or tile icon with one straight arrow pointing at angle a. On hover the arrow
 * darts up to about `go + dart` units the way it points and settles `go` units out; the
 * lighter front leans after it by up to `lean`.
 */
export function straightIcon({ body, a, tail, tip, go, dart, lean = 0, head = 3.4, half = 2.9, ...meta }) {
  const ar = flatArrow(tail, tip, head, half);
  return defineIcon({
    category: 'arrows',
    ...meta,
    svg: (u) => liveBody(u, body) + mark(ar.shaft, ar.head, 'arr'),
    target: (h) => ({ o: h ? 1 : 0 }),
    draw(p, E) {
      const [ux, uy] = dir(a), w = swell(p.o), s = go * p.o + dart * w, l = lean * w;
      leanBody(E, body, ux * l, uy * l);
      at(E.arr, { transform: place(a, ux * s, uy * s) });
    }
  });
}

// Chevrons: arms run dx back and dy out from the vertex at the origin, pointing right.
const CH = { dx: 3.4, dy: 4 };
const chev = (x, dx = CH.dx, dy = CH.dy) => `M${f(x - dx)} ${f(-dy)}L${f(x)} 0L${f(x - dx)} ${f(dy)}`;

/** A disc icon with one chevron pointing at angle a; it steps that way on hover. */
export function chevronIcon({ a, ...meta }) {
  return defineIcon({
    category: 'arrows',
    ...meta,
    svg: (u) => disc(u) + `<path data-n="ch" d="${chev(2.4, 4.4, 5.4)}" ${STROKE}/>`,
    target: (h) => ({ s: h ? 1.2 : 0 }),
    draw(p, E) {
      const [ux, uy] = dir(a);
      at(E.ch, { transform: place(a, ux * p.s, uy * p.s) });
    }
  });
}

/**
 * A disc icon with two chevrons pointing at angle a. On hover the leading one steps
 * further than the trailing one, so the pair opens up as it moves.
 */
export function chevronsIcon({ a, ...meta }) {
  const gap = 5.2, v1 = -0.9;
  return defineIcon({
    category: 'arrows',
    ...meta,
    svg: (u) => disc(u) + `<g data-n="g"><path data-n="c1" d="${chev(v1)}" ${STROKE}/><path data-n="c2" d="${chev(v1 + gap)}" ${STROKE}/></g>`,
    target: (h) => ({ s: h ? 1 : 0 }),
    draw(p, E) {
      at(E.g, { transform: place(a) });
      at(E.c1, { transform: `translate(${f(p.s * 0.5)} 0)` });
      at(E.c2, { transform: `translate(${f(p.s)} 0)` });
    }
  });
}

export { f, at, arrow };
