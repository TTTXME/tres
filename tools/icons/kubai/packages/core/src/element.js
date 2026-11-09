// <kubai-icon name="folder"></kubai-icon>: a custom element that loads each icon module on
// demand from the same place as this script, so a page can use any icon from a CDN with
// one script tag. Attributes: name, size, color, variant, badge-color, trigger ('hover' | 'parent' |
// 'none'), label.
import { toSvg } from './icon.js';
import { animate, bindTrigger } from './runtime.js';

const cache = new Map();
const load = (name) => {
  if (!/^[a-z0-9-]+$/.test(name)) return Promise.reject(new Error(`Bad icon name: ${name}`));
  if (!cache.has(name)) cache.set(name, import(new URL(`./icons/${name}.js`, import.meta.url).href).then((m) => m.default, (e) => { cache.delete(name); throw e; }));
  return cache.get(name);
};

// Importing this module during server rendering is harmless: nothing registers there.
const Base = typeof HTMLElement === 'undefined' ? class {} : HTMLElement;

export class KubaiIconElement extends Base {
  static observedAttributes = ['name', 'size', 'color', 'variant', 'badge-color', 'trigger', 'label'];
  connectedCallback() { this.render(); }
  disconnectedCallback() { this.token = null; this.unbind(); }
  attributeChangedCallback() { if (this.isConnected) this.render(); }
  unbind() { this.cleanup?.(); this.cleanup = null; }
  async render() {
    const name = this.getAttribute('name');
    const token = (this.token = {});
    // The size is set before the icon loads, so the page does not shift when it arrives.
    const size = Number(this.getAttribute('size')) || 24;
    if (!this.style.display) this.style.display = 'inline-block';
    this.style.width = this.style.height = `${size}px`;
    this.style.lineHeight = '0';
    let ic = null;
    if (name) { try { ic = await load(name); } catch { /* an unknown name renders nothing */ } }
    if (token !== this.token) return;
    this.unbind();
    if (!ic) { this.textContent = ''; return; }
    this.innerHTML = toSvg(ic, { size, color: this.getAttribute('color') || undefined, variant: this.getAttribute('variant') || undefined, badgeColor: this.getAttribute('badge-color') || undefined, title: this.getAttribute('label') || undefined });
    const trigger = this.getAttribute('trigger') || 'hover';
    if (trigger === 'none') return;
    const ctl = animate(this.firstElementChild, ic);
    const host = trigger === 'parent' ? (this.closest('[data-kubai-trigger]') || this.parentElement || this) : this;
    const unbind = bindTrigger(host, ctl, ic);
    this.cleanup = () => { unbind(); ctl.destroy(); };
  }
}
if (typeof customElements !== 'undefined' && !customElements.get('kubai-icon')) customElements.define('kubai-icon', KubaiIconElement);
