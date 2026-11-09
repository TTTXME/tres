#!/usr/bin/env node
// P570 (owner, 2026-11-09: «переделай все иконки внутри нашего приложения, сделай их красивыми, по возможности анимированными и
// интуитивно понятными»; «Сделай иконки между стилем MacOS и Material design»; «Стиль иконок сделай ближе к Material You»;
// «Сделай иконки более минималистичными»; the colourful palette): the app's icons from Kubai Icons definitions (the MIT library in
// tools/icons/kubai, our own camera objects in tools/icons/scamera) drawn as one-colour lines (kubai_vd.mjs, style 'line'), one
// colour each (Apple's system colours by meaning); an "on" still (a favourite's heart, a pinned pin) filled (style 'material'),
// the outlined / filled pair of SF Symbols and Material. Written over the drawables of the same names (layouts and code keep
// their references). Navigation and control glyphs (chevrons, close, check boxes, the media transport, notification and launcher
// icons) stay the plain glyphs they are.
//
//   node tools/icons/build.mjs [--only name,name] [--out app/src/main/res/drawable] [--preview dir]
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { convert } from './kubai_vd.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const args = process.argv.slice(2);
const opt = (k, d) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : d; };
const only = (opt('--only', '') || '').split(',').filter(Boolean);
const out = path.resolve(opt('--out', path.join(root, 'app/src/main/res/drawable')));
const preview = opt('--preview');

// Apple's system colours (dark appearance)
const BLUE = '#0A84FF', GREEN = '#30D158', ORANGE = '#FF9F0A', RED = '#FF453A', YELLOW = '#FFC800', PURPLE = '#BF5AF2',
  PINK = '#FF375F', TEAL = '#40C8E0', INDIGO = '#5E5CE6', GRAY = '#8E8E93', BROWN = '#AC8E68', MINT = '#00C7BE';

const mod = async (p) => (await import(pathToFileURL(path.join(here, p)).href)).default;
const lib = (n) => mod(`kubai/icons/${n}.js`);
const own = (n) => mod(`scamera/${n}.js`);
const { withSlash, withBadge } = await import(pathToFileURL(path.join(here, 'kubai/packages/core/src/compose.js')).href);

async function src(s) {
  if (typeof s === 'string') return s.startsWith('own:') ? own(s.slice(4)) : lib(s);
  if (s.slash) return withSlash(await src(s.slash), await mod('kubai/badges/_slash.js'));
  if (s.badge) return withBadge(await src(s.badge[0]), await mod(`kubai/badges/${s.badge[1]}.js`));
  if (s.pair) return pair(await src(s.pair[0]), await src(s.pair[1]));
  throw new Error('icon source ' + JSON.stringify(s));
}
const label = (s) => typeof s === 'string' ? s : s.slash ? `${label(s.slash)} (slashed)` : s.badge ? `${label(s.badge[0])} + ${s.badge[1]} badge`
  : `${label(s.pair[0])} + ${label(s.pair[1])}`;

/** Base icon with a second one at half size on its lower right corner (a combined format: RAW + JPEG). */
function pair(base, small, at = [22, 18.4], scale = 0.56) {
  const pre = (svg) => svg.replace(/data-n="/g, 'data-n="b_');
  return {
    name: `${base.name}+${small.name}`,
    svg: (u) => {
      const u2 = Object.assign(Object.create(Object.getPrototypeOf(u)), u, { id: (n) => u.id('b' + n) });
      return base.svg(u) + `<g transform="translate(${at[0]} ${at[1]}) scale(${scale}) translate(-15.5 -12.5)">${pre(small.svg(u2))}</g>`;
    },
    target: (h, s) => ({ ...base.target(h, s), kb: small.target(h, s) }),
    draw(p, E, inst) {
      base.draw(p, E, inst);
      const E2 = {};
      for (const k in E) if (k.startsWith('b_')) E2[k.slice(2)] = E[k];
      small.draw(p.kb, E2, inst);
    }
  };
}

// resource name -> [source, accent, options]; pose: 1 = a still of the pressed pose (an "on" resource)
const MAP = {
  // ---- camera: flash, timers, grids, shade tiles
  ic_flash_on: ['own:flash', YELLOW], ic_flash_off: [{ slash: 'own:flash' }, GRAY],
  settings_ic_flash: ['own:flash', YELLOW], ui_ic_flash: ['own:flash', YELLOW],
  ic_torch: ['flashlight', YELLOW],
  ic_timer3s: ['own:timer-3', ORANGE], ic_timer10s: ['own:timer-10', ORANGE], ic_timeroff: [{ slash: 'timer' }, GRAY],
  ic_sheet_timer: ['timer', ORANGE], ui_ic_timer: ['timer', ORANGE],
  ic_grid_3x3: ['own:vf-grid-3', TEAL], ic_grid_4x4: ['own:vf-grid-4', TEAL], ic_grid_diagonal: ['own:vf-grid-diagonal', TEAL],
  ic_grid_golden: ['own:vf-grid-golden', TEAL], ic_sheet_grid_off: [{ slash: 'own:vf-grid-3' }, GRAY],
  settings_ic_grid: ['own:vf-grid-3', TEAL], ui_ic_grid: ['own:vf-grid-3', TEAL],
  ic_shade_focus: ['focus', GREEN], ic_shade_meter: ['target', ORANGE], ic_sheet_metering: ['target', ORANGE],
  ic_shade_plus: ['circle-plus', BLUE], ic_sheet_downsampler: ['shrink', INDIGO],
  ic_sheet_pin: ['pin', GRAY], ic_sheet_pin_on: ['pin', RED, { pose: 1 }],
  // formats: JPEG a photo, HEIC a leaf, AVIF a gem, WebP a globe, RAW a film strip; RAW + X: the film with X on its corner
  ic_shade_jpeg: ['image', BLUE], ic_shade_heic: ['leaf', GREEN], ic_shade_avif: ['gem', PURPLE], ic_shade_webp: ['globe', TEAL],
  ic_shade_raw: ['film', ORANGE],
  ic_shade_rawjpeg: [{ pair: ['film', 'image'] }, ORANGE], ic_shade_rawheic: [{ pair: ['film', 'leaf'] }, ORANGE],
  ic_shade_rawavif: [{ pair: ['film', 'gem'] }, ORANGE], ic_shade_rawwebp: [{ pair: ['film', 'globe'] }, ORANGE],
  ic_round_photo_camera_24: ['camera', GRAY], ic_check_circle: ['circle-check', GREEN], ic_info: ['info', BLUE],
  ic_curve_undo: ['undo', BLUE], ic_curve_redo: ['redo', BLUE],
  gallery_ic_histogram: ['chart-area', TEAL],
  // ---- settings
  settings_ic_aperture: ['aperture', INDIGO], settings_ic_arrange: ['layout-dashboard', BLUE], settings_ic_camera: ['camera', GRAY],
  settings_ic_chip: ['cpu', INDIGO], settings_ic_config: ['save', BLUE], settings_ic_copy: ['copy', BLUE],
  settings_ic_corner: ['square', MINT], settings_ic_crop: ['crop', GREEN], settings_ic_delete: ['trash', RED],
  settings_ic_diag: ['activity', PINK], settings_ic_edit: ['pencil', ORANGE], settings_ic_eye: ['eye', BLUE],
  settings_ic_eye_off: [{ slash: 'eye' }, GRAY], settings_ic_face: ['smile', YELLOW], settings_ic_film: ['film', ORANGE],
  settings_ic_focus: ['focus', GREEN], settings_ic_fps: ['gauge', RED], settings_ic_frames: ['images', PINK],
  settings_ic_gear: ['settings', GRAY], settings_ic_hdr: ['sunset', ORANGE], settings_ic_id: ['id-badge', BLUE],
  settings_ic_info: ['info', BLUE], settings_ic_key: ['key', YELLOW], settings_ic_layers: ['layers', INDIGO],
  settings_ic_lenses: ['camera-retro', BROWN], settings_ic_list: ['list', BLUE], settings_ic_lock: ['lock', GRAY],
  settings_ic_merge: ['merge', PURPLE], settings_ic_mosaic: ['blocks', TEAL], settings_ic_motion: ['person-running', GREEN],
  settings_ic_noise: ['waves', TEAL], settings_ic_palette: ['palette', PINK], settings_ic_panel: ['layout-dashboard', INDIGO],
  settings_ic_plus: ['circle-plus', GREEN], settings_ic_ratio: ['expand', MINT], settings_ic_reset: ['rotate-ccw', ORANGE],
  settings_ic_restore: ['import', BLUE], settings_ic_save: ['save', BLUE], settings_ic_search: ['search', GRAY],
  settings_ic_sensor: ['scan', INDIGO], settings_ic_sharp: ['glasses', BLUE], settings_ic_sliders: ['sliders', BLUE],
  settings_ic_sound: ['volume', PINK], settings_ic_sun: ['sun', YELLOW], settings_ic_tag: ['tag', ORANGE],
  settings_ic_toggle: ['own:toggle', GREEN], settings_ic_ui: ['app-window', BLUE], settings_ic_view: ['smartphone', GRAY],
  settings_ic_water: ['stamp', PURPLE], settings_ic_zoom: ['zoom-in', BLUE],
  // ---- gallery, viewer, editor, quick settings
  ui_ic_albums: ['album', ORANGE], ui_ic_apps: ['layout-dashboard', BLUE], ui_ic_aspect: ['expand', MINT],
  ui_ic_backup: ['history', BLUE], ui_ic_camera: ['camera', GRAY], ui_ic_cleanup: ['broom', ORANGE], ui_ic_clock: ['clock', BLUE],
  ui_ic_compare: ['split-view', INDIGO], ui_ic_crop: ['crop', GREEN], ui_ic_detail: ['gem', TEAL], ui_ic_edit: ['pencil', ORANGE],
  ui_ic_effects: ['sparkles', PURPLE], ui_ic_face: ['smile', YELLOW], ui_ic_filter: ['filter', INDIGO],
  ui_ic_flip: [{ badge: ['camera', 'sync'] }, GRAY], ui_ic_folder: ['folder', BLUE], ui_ic_folders: ['folders', BLUE],
  ui_ic_format: ['file-image', BLUE], ui_ic_gear: ['settings', GRAY], ui_ic_hdr: ['sunset', ORANGE],
  ui_ic_headphones: ['headphones', PINK], ui_ic_heart: ['heart', PINK], ui_ic_heart_on: ['heart', RED, { pose: 1 }],
  ui_ic_info: ['info', BLUE], ui_ic_lamp: ['lightbulb', YELLOW], ui_ic_layers: ['layers', INDIGO], ui_ic_lens: ['aperture', INDIGO],
  ui_ic_level: ['spirit-level', GREEN], ui_ic_link: ['link', BLUE], ui_ic_lock: ['lock', GRAY], ui_ic_looks: ['wand', PURPLE],
  ui_ic_mirror: ['mirror', TEAL], ui_ic_moon: ['moon', INDIGO], ui_ic_motion: ['person-running', GREEN], ui_ic_music: ['music', PINK],
  ui_ic_mute: [{ slash: 'volume' }, GRAY], ui_ic_open: ['external-link', BLUE], ui_ic_palette: ['palette', PINK],
  ui_ic_peak: ['mountain', GREEN], ui_ic_photos: ['images', PINK], ui_ic_pip: ['picture-in-picture', BLUE],
  ui_ic_raw: ['film', ORANGE], ui_ic_redo: ['redo', BLUE], ui_ic_restore: ['history', GREEN], ui_ic_rotate: ['rotate-cw', BLUE],
  ui_ic_screenshot: ['scan', TEAL], ui_ic_search: ['search', GRAY], ui_ic_select_all: ['list-checks', BLUE], ui_ic_share: ['share', BLUE],
  ui_ic_size: ['expand', MINT], ui_ic_sliders: ['sliders', BLUE], ui_ic_sort: ['sort', BLUE], ui_ic_sound: ['volume', PINK],
  ui_ic_styles: ['paintbrush', PURPLE], ui_ic_subtitles: ['subtitles', GRAY], ui_ic_sun: ['sun', YELLOW],
  ui_ic_swap: ['arrow-left-right', BLUE], ui_ic_target: ['target', ORANGE], ui_ic_text: ['type', GRAY], ui_ic_trash: ['trash', RED],
  ui_ic_undo: ['undo', BLUE], ui_ic_unlock: ['lock-open', GRAY], ui_ic_video: ['video', RED], ui_ic_wallpaper: ['photo-frame', TEAL],
  ui_ic_zoom_in: ['zoom-in', BLUE], ui_ic_zoom_out: ['zoom-out', BLUE]
};

const names = only.length ? only : Object.keys(MAP);
let n = 0, moving = 0;
const report = [];
for (const name of names) {
  const e = MAP[name];
  if (!e) throw new Error('not in the map: ' + name);
  const [s, accent, o = {}] = e;
  const def = await src(s);
  const r = await convert(def, name, accent, out, { animated: !o.pose, pose: o.pose || 0, preview, source: label(s), style: o.pose ? 'material' : 'line' });
  n++; if (r.moving) moving++;
  report.push(`${name}\t${label(s)}\t${accent}\t${r.moving ? 'animated' : 'static'}\t${r.layers}${r.warn.length ? '\tleft out: ' + r.warn.join(' ') : ''}`);
}
// the colour icons' names (ui/Icons: they keep their colours where the old glyphs were tinted); written with a full build only
if (!only.length && out.endsWith(path.join('res', 'drawable'))) fs.writeFileSync(path.join(out, '../values/kb_icons.xml'),
  '<?xml version="1.0" encoding="utf-8"?>\n<!-- P570: generated by tools/icons/build.mjs - the colour icons (ui/Icons) -->\n<resources>\n'
  + '    <string-array name="kb_icons" translatable="false">\n' + Object.keys(MAP).map((k) => `        <item>${k}</item>\n`).join('')
  + '    </string-array>\n    <item name="kb_colour" type="id" />\n</resources>\n');
fs.writeFileSync(path.join(here, 'icons.tsv'), 'resource\tsource\taccent\tmotion\tlayers\n' + report.join('\n') + '\n');
console.log(`${n} icons, ${moving} animated (tools/icons/icons.tsv)`);
