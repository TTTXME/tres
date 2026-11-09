// Shared geometry for the file and text icons: the page with a folded top-right corner
// that the typed file icons use, and the square card with white text bars that the
// text formatting icons use.
import { KR, OL, R, rp, rr } from '../packages/core/src/kit.js';

const X0 = 6.3, X1 = 24.7, Y0 = 1.5, Y1 = 23.5, FOLD = 6;

export const pagePath = rp([[X0, Y0], [X1 - FOLD, Y0], [X1, Y0 + FOLD], [X1, Y1], [X0, Y1]], [KR, 0.5, 0.5, KR, KR]);
export const foldPath = rp([[X1 - FOLD, Y0], [X1, Y0 + FOLD], [X1 - FOLD, Y0 + FOLD]], [0.4, 0.4, 1.2]);

/** The page (mid tone, shadow and top light), the type mark, then the lighter fold on top. */
export const typedPage = (u, inner) => `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${pagePath}" fill="${u.loS}"/></g></g><path d="${pagePath}" ${OL}/>
    ${inner}
    <g filter="${u.hi}"><path d="${foldPath}" fill="${u.upS}"/></g><path d="${foldPath}" ${OL}/>`;

/** Badges a document type takes. */
export const FILE_BADGES = ['plus', 'minus', 'check', 'x', 'alert', 'info', 'up', 'down', 'right', 'clock', 'star', 'heart', 'lock', 'search', 'sync', 'sparkle'];

/** The text card, and its three row centres. */
export const CARD = rr(5.2, 2.2, 20.6, 20.6, KR);
export const ROWS = [7.6, 12.5, 17.4];
/** Height of a white text bar. */
export const BAR = 2.4;
export const card = (u, inner) => `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${CARD}" fill="${u.loS}"/></g></g><path d="${CARD}" ${OL}/>
    ${inner}`;
/** A white text bar starting at x, centred on row y, w long. */
export const bar = (x, y, w) => rp(R(x, y - BAR / 2, Math.max(BAR, w), BAR), BAR / 2);
