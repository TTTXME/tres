#!/usr/bin/env python3
"""The Android settings screen for the iOS app, generated from the Android sources so both stay one to one:
- ios/Resources/android_settings.json: the tree of res/xml/preferences.xml (screens, categories, switches, lists, sliders,
  text fields and the custom rows by name) with every title, summary and list entry in English and Russian
  (res/values*, res/values-ru*) and the defaults resolved;
- ios/Resources/Icons.xcassets: the vector drawables the tree (and tools/ios_icons.txt) names, as SVG images.
--check: fail when the generated files are out of date."""
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res'
OUT_JSON = ROOT / 'ios/Resources/android_settings.json'
OUT_ICONS = ROOT / 'ios/Resources/Icons.xcassets'
EXTRA_ICONS = ROOT / 'ios/tools/ios_icons.txt'
A = '{http://schemas.android.com/apk/res/android}'
APP = '{http://schemas.android.com/apk/res-auto}'
CUSTOM = 'com.particlesdevs.photoncamera.ui.settings.custompreferences.'


def unescape(text):
    """An Android string resource's text as the app shows it."""
    if text is None:
        return ''
    t = text.strip()
    if len(t) >= 2 and t[0] == '"' and t[-1] == '"':
        t = t[1:-1]
    else:
        t = re.sub(r'\s+', ' ', t)
    out, i = [], 0
    while i < len(t):
        c = t[i]
        if c == '\\' and i + 1 < len(t):
            n = t[i + 1]
            out.append({'n': '\n', 't': '\t', "'": "'", '"': '"', '\\': '\\', '@': '@', '?': '?'}.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def inner_text(el):
    """The element's text with inline markup (<b>, <xliff:g>) reduced to its text."""
    parts = [el.text or '']
    for child in el:
        parts.append(inner_text(child))
        parts.append(child.tail or '')
    return ''.join(parts)


def load_values(folder):
    strings, arrays = {}, {}
    for f in sorted(folder.glob('*.xml')):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        for el in root:
            name = el.get('name')
            if el.tag == 'string' and name:
                strings[name] = unescape(inner_text(el))
            elif el.tag in ('string-array', 'array', 'integer-array') and name:
                arrays[name] = [unescape(inner_text(item)) for item in el.findall('item')]
            elif el.tag == 'integer' and name:
                strings['integer/' + name] = (el.text or '').strip()
            elif el.tag == 'bool' and name:
                strings['bool/' + name] = (el.text or '').strip()
    return strings, arrays


EN_S, EN_A = load_values(RES / 'values')
RU_S, RU_A = load_values(RES / 'values-ru')
used_strings, used_arrays, used_icons = set(), set(), set()


def ref(value, kind):
    m = re.fullmatch(r'@' + kind + r'/(\w+)', value or '')
    return m.group(1) if m else None


def text(value):
    """A title / summary: {"s": name} of a string resource, or the literal."""
    if value is None:
        return None
    name = ref(value, 'string')
    if name:
        if name not in EN_S:
            return None
        used_strings.add(name)
        return {'s': name}
    return {'t': unescape(value)}


def literal(value):
    """A default / key: a resource resolved to its (untranslated) value."""
    if value is None:
        return None
    for kind, table in (('string', EN_S), ('integer', None), ('bool', None)):
        name = ref(value, kind)
        if name:
            return EN_S.get(name if kind == 'string' else kind + '/' + name)
    return value


def array(value):
    name = ref(value, 'array')
    if not name or name not in EN_A:
        return None
    used_arrays.add(name)
    return name


def node(el):
    tag = el.tag
    kind = {'PreferenceScreen': 'screen', 'PreferenceCategory': 'category', 'SwitchPreferenceCompat': 'switch',
            'SwitchPreference': 'switch', 'CheckBoxPreference': 'switch', 'ListPreference': 'list',
            'EditTextPreference': 'text', 'Preference': 'row'}.get(tag)
    if tag.startswith(CUSTOM):
        name = tag[len(CUSTOM):]
        kind = {'UniversalSeekBarPreference': 'slider', 'SegmentedListPreference': 'list',
                'ManagedSwitchPreference': 'switch'}.get(name, 'custom:' + name)
    if kind is None:
        return None
    n = {'type': kind}
    key = literal(el.get(A + 'key'))
    if key:
        n['key'] = key
    for attr, field in (('title', 'title'), ('summary', 'summary')):
        v = text(el.get(A + attr))
        if v:
            n[field] = v
    d = literal(el.get(A + 'defaultValue'))
    if d is not None:
        n['default'] = d
    dep = el.get(A + 'dependency')
    if dep:
        n['dependency'] = literal(dep)
    icon = ref(el.get(A + 'icon'), 'drawable')
    if icon and (RES / 'drawable' / (icon + '.xml')).exists():
        used_icons.add(icon)
        n['icon'] = icon
    if kind == 'list':
        n['entries'] = array(el.get(A + 'entries'))
        n['values'] = array(el.get(A + 'entryValues'))
        if tag.endswith('SegmentedListPreference'):
            n['segmented'] = True
    if kind == 'slider':
        n['min'] = float(literal(el.get(APP + 'minValue')) or 0)
        n['max'] = float(literal(el.get(APP + 'maxValue')) or 100)
        n['step'] = float(literal(el.get(APP + 'stepPerUnit')) or 1)
        n['float'] = (literal(el.get(APP + 'isFloat')) or 'false') == 'true'
    if kind == 'switch' and 'default' in n:
        n['default'] = n['default'] == 'true'
    children = [c for c in (node(child) for child in el) if c]
    if children:
        n['children'] = children
    intent = el.find('intent')
    if intent is not None:
        n['intent'] = intent.get(A + 'targetClass') or intent.get(A + 'action')
        if intent.get(A + 'data'):
            n['url'] = intent.get(A + 'data')
    return n


def color(argb):
    """#AARRGGBB / #RRGGBB -> (css colour, opacity)."""
    v = (argb or '').lstrip('#')
    if len(v) == 8:
        return '#' + v[2:], int(v[:2], 16) / 255
    if len(v) == 6:
        return '#' + v, 1.0
    return 'none', 0


def svg(name):
    root = ET.parse(RES / 'drawable' / (name + '.xml')).getroot()
    if root.tag != 'vector':
        return None
    w, h = root.get(A + 'viewportWidth'), root.get(A + 'viewportHeight')
    out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%s" height="%s" viewBox="0 0 %s %s">'
           % (root.get(A + 'width', '24dp').replace('dp', ''), root.get(A + 'height', '24dp').replace('dp', ''), w, h)]

    def emit(el, indent):
        for c in el:
            if c.tag == 'group':
                t = []
                tx, ty = c.get(A + 'translateX'), c.get(A + 'translateY')
                if tx or ty:
                    t.append('translate(%s %s)' % (tx or 0, ty or 0))
                rot = c.get(A + 'rotation')
                if rot:
                    t.append('rotate(%s %s %s)' % (rot, c.get(A + 'pivotX', 0), c.get(A + 'pivotY', 0)))
                sx, sy = c.get(A + 'scaleX'), c.get(A + 'scaleY')
                if sx or sy:
                    t.append('scale(%s %s)' % (sx or 1, sy or 1))
                out.append('<g transform="%s">' % ' '.join(t))
                emit(c, indent + 1)
                out.append('</g>')
            elif c.tag == 'path':
                fill, fo = color(c.get(A + 'fillColor'))
                stroke, so = color(c.get(A + 'strokeColor'))
                attrs = ['d="%s"' % c.get(A + 'pathData', '')]
                attrs.append('fill="%s"' % (fill if fo > 0 else 'none'))
                if fo > 0 and fo < 1:
                    attrs.append('fill-opacity="%.3f"' % fo)
                if (c.get(A + 'fillType') or '') == 'evenOdd':
                    attrs.append('fill-rule="evenodd"')
                if so > 0:
                    attrs.append('stroke="%s"' % stroke)
                    if so < 1:
                        attrs.append('stroke-opacity="%.3f"' % so)
                    attrs.append('stroke-width="%s"' % c.get(A + 'strokeWidth', '1'))
                    if c.get(A + 'strokeLineCap'):
                        attrs.append('stroke-linecap="%s"' % c.get(A + 'strokeLineCap'))
                    if c.get(A + 'strokeLineJoin'):
                        attrs.append('stroke-linejoin="%s"' % c.get(A + 'strokeLineJoin'))
                alpha = c.get(A + 'fillAlpha')
                if alpha:
                    attrs.append('opacity="%s"' % alpha)
                out.append('<path %s/>' % ' '.join(attrs))
    emit(root, 1)
    out.append('</svg>')
    return '\n'.join(out) + '\n'


def render():
    tree = node(ET.parse(RES / 'xml/preferences.xml').getroot())
    if EXTRA_ICONS.exists():
        for line in EXTRA_ICONS.read_text().split():
            if (RES / 'drawable' / (line + '.xml')).exists():
                used_icons.add(line)

    def table(strings, arrays, fallback_s, fallback_a):
        return {'strings': {k: strings.get(k, fallback_s[k]) for k in sorted(used_strings)},
                'arrays': {k: arrays.get(k, fallback_a[k]) if len(arrays.get(k, [])) == len(fallback_a[k]) else fallback_a[k]
                           for k in sorted(used_arrays)}}
    doc = {'generated': 'ios/tools/gen_settings_ui.py from app/src/main/res: do not edit', 'tree': tree,
           'en': table(EN_S, EN_A, EN_S, EN_A), 'ru': table(RU_S, RU_A, EN_S, EN_A)}
    files = {OUT_JSON: json.dumps(doc, ensure_ascii=False, indent=1, sort_keys=True) + '\n'}
    files[OUT_ICONS / 'Contents.json'] = json.dumps({'info': {'author': 'xcode', 'version': 1}}, indent=2) + '\n'
    for name in sorted(used_icons):
        s = svg(name)
        if s is None:
            continue
        folder = OUT_ICONS / (name + '.imageset')
        files[folder / (name + '.svg')] = s
        files[folder / 'Contents.json'] = json.dumps({
            'images': [{'filename': name + '.svg', 'idiom': 'universal'}], 'info': {'author': 'xcode', 'version': 1},
            'properties': {'preserves-vector-representation': True, 'template-rendering-intent': 'original'}}, indent=2) + '\n'
    return files


if __name__ == '__main__':
    files = render()
    if '--check' in sys.argv:
        stale = [str(p.relative_to(ROOT)) for p, t in files.items() if not p.exists() or p.read_text() != t]
        if stale:
            print('out of date (run ios/tools/gen_settings_ui.py):', ', '.join(stale[:10]))
            sys.exit(1)
        print('settings UI up to date:', len(files), 'files')
    else:
        for p, t in files.items():
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(t)
        print(len(files), 'files,', len(used_strings), 'strings,', len(used_arrays), 'arrays,', len(used_icons), 'icons')
