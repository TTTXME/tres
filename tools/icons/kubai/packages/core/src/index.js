// kubai-icons: framework-free API. Icons are imported one by one so bundles only carry
// what a page uses:
//
//   import { folder, mail } from 'kubai-icons';
//   document.body.append(createIcon(folder, { size: 32 }));
import { toSvg } from './icon.js';
import { animate, bindTrigger } from './runtime.js';

export { toSvg, VIEWBOX, uid, badgeStyle } from './icon.js';
export { animate, bindTrigger, setVariant, VARIANTS } from './runtime.js';
export { portableShadow } from './color.js';

/**
 * Creates an <svg> element for an icon, animated on hover unless `trigger` is 'none'.
 * trigger: 'hover' (the icon itself), 'parent' (the closest [data-kubai-trigger], else the
 * parent element; attach the icon in the same task it is created in) or 'none'.
 */
export function createIcon(ic, options = {}) {
  const { trigger = 'hover', active, ...rest } = options;
  const tpl = document.createElement('template');
  tpl.innerHTML = toSvg(ic, rest);
  const svg = tpl.content.firstElementChild;
  if (trigger === 'none') return svg;
  const ctl = animate(svg, ic, { state: active ? { active: true } : {} });
  svg.kubai = ctl;
  let off = trigger === 'parent' ? null : bindTrigger(svg, ctl, ic);
  if (!off) queueMicrotask(() => { off = bindTrigger(svg.closest('[data-kubai-trigger]') || svg.parentElement || svg, ctl, ic); });
  const destroy = ctl.destroy;
  ctl.destroy = () => { off?.(); destroy(); };
  return svg;
}

/**
 * Replaces every element carrying `data-kubai="<name>"` inside `root` with its icon.
 * `icons` maps names to icon modules: { folder, mail } or the whole set.
 * Copies size, color, variant and trigger from data-size / data-color / data-variant /
 * data-trigger, and the element's class names.
 */
export function replaceIcons({ icons, root = document, attr = 'data-kubai' } = {}) {
  const byName = new Map(Object.values(icons || {}).map((ic) => [ic.name, ic]));
  for (const el of root.querySelectorAll(`[${attr}]`)) {
    const ic = byName.get(el.getAttribute(attr));
    if (!ic) continue;
    const d = el.dataset;
    el.replaceWith(createIcon(ic, {
      size: d.size ? Number(d.size) : undefined, color: d.color, variant: d.variant, badgeColor: d.badgeColor, trigger: d.trigger,
      title: el.getAttribute('aria-label') || undefined, className: el.getAttribute('class') || undefined
    }));
  }
}
