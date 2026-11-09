// Colour derivation. Every colour in an icon is written against the reference blue
// #5393FF and moved to the chosen accent by its relative lightness.

export const REF = [83, 147, 255];
export const hex = (c) => '#' + c.map((v) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('').toUpperCase();
export const rgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));

/** Exact tint: moves a reference colour to the accent `sel` (an [r, g, b] array). */
export function tint(src, sel) {
  const s = rgb(src);
  return hex([0, 1, 2].map((i) => {
    const amt = i === 2 ? (s[0] - REF[0]) / (255 - REF[0]) : (s[i] - REF[i]) / (255 - REF[i]);
    return amt >= 0 ? sel[i] + (255 - sel[i]) * amt : sel[i] * (1 + amt);
  }));
}
/** Lifts a colour toward white by k. */
export const soften = (h, k) => hex(rgb(h).map((v) => v + (255 - v) * k));
/** Pulls a colour toward its own grey by `sat`, then darkens it by `dark`. */
export const mute = (h, sat, dark) => {
  const c = rgb(h), g = 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2];
  return hex(c.map((v) => (v + (g - v) * sat) * (1 - dark)));
};
/** Outlines and deep marks follow the edge colour; lighter colours follow the fill. */
export const usesEdge = (src) => rgb(src)[0] <= REF[0];

// ---------- CSS form ----------
// The same rule written as color-mix() over CSS custom properties, so one markup string
// takes any accent from `color` (currentColor) without script. The red and green amounts
// of each reference colour differ by a few hundredths; their mean is used.
export const CSS_FILL = 'var(--ki-c,currentColor)';
export const CSS_EDGE = 'var(--ki-e,var(--ki-c,currentColor))';
export const CSS_WHITE = 'var(--ki-w,#fff)';
const pct = (n) => `${+(n * 100).toFixed(1)}%`;
export function cssTint(src, base) {
  const s = rgb(src);
  const amt = ((s[0] - REF[0]) / (255 - REF[0]) + (s[1] - REF[1]) / (255 - REF[1])) / 2;
  if (Math.abs(amt) < 0.005) return base;
  return amt > 0 ? `color-mix(in srgb,${base} ${pct(1 - amt)},#fff)` : `color-mix(in srgb,${base} ${pct(1 + amt)},#000)`;
}

/**
 * A drop shadow's filter primitives in the form design tools read as a shadow effect.
 * The colour has to be a plain hex value, so it suits exported files and not live icons.
 */
export function portableShadow(color, opacity) {
  const [r, g, b] = rgb(color).map((v) => +(v / 255).toFixed(4));
  return '<feFlood flood-opacity="0" result="BackgroundImageFix"/>'
    + '<feColorMatrix in="SourceAlpha" type="matrix" values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 127 0" result="hardAlpha"/>'
    + '<feOffset dy="2"/><feGaussianBlur stdDeviation="2"/><feComposite in2="hardAlpha" operator="out"/>'
    + `<feColorMatrix type="matrix" values="0 0 0 0 ${r} 0 0 0 0 ${g} 0 0 0 0 ${b} 0 0 0 ${opacity} 0"/>`
    + '<feBlend in2="BackgroundImageFix" mode="normal" result="shadow"/>'
    + '<feBlend in="SourceGraphic" in2="shadow" mode="normal"/>';
}

/**
 * Variants as CSS custom properties, which inherit: set on an element, they reach every
 * icon inside it. `soft` is the clear look: fills lifted toward white, outlines a step
 * deeper, white marks turned deep. `color` is the default look, and undoes an inherited one.
 */
export const VARIANTS = {
  color: { '--ki-c': 'initial', '--ki-e': 'initial', '--ki-w': 'initial', '--ki-l': 'initial' },
  soft: {
    '--ki-c': 'color-mix(in srgb,currentColor 90%,#fff)',
    '--ki-e': 'color-mix(in srgb,currentColor 84%,#000)',
    '--ki-w': 'color-mix(in srgb,currentColor 65%,#000)',
    '--ki-l': '72%'
  }
};

/** Resolved colours for one accent and variant, for static export. */
export function palette(accent = '#5393FF', variant = 'color') {
  if (variant === 'soft') return { c: soften(accent, 0.1), e: mute(accent, 0, 0.16), deep: true };
  return { c: accent, e: accent, deep: false };
}
