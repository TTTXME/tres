#!/usr/bin/env python3
"""The Android settings screen for the iOS app, generated from the Android sources so both stay one to one:
- ios/Resources/android_settings.json: the tree of res/xml/preferences.xml (screens, categories, switches, lists, sliders,
  text fields and the custom rows by name) with every title, summary and list entry in English and Russian
  (res/values*, res/values-ru*) and the defaults resolved;
- ios/Resources/Icons.xcassets: the vector drawables the tree (and tools/ios_icons.txt) names, as SVG images;
- "develop" in the json (P665): the gallery developer's groups (DevelopCatalog.GROUPS: the parts of the tree each takes), the rows
  fixed at the shot (ScamLabFragment.AT_SHOT, BURST_FRAMES) and the merge's tuning keys (PreferenceKeys.TUNING_KEYS);
- "help" (P319 / P592 SettingHelp): a row's help text by its key (EXACT) or key family (FAMILIES) and the rows whose summary is
  their state (STATE_KEYS); the texts of res/values*/strings_help.xml go into the en / ru tables;
- "devKeys": the «Для разработчика» page's scam_dev.txt lines (settings/DevKeys.java: group, key, kind, default, meaning).
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
JAVA = ROOT / 'app/src/main/java/com/scamera/app'
A = '{http://schemas.android.com/apk/res/android}'
APP = '{http://schemas.android.com/apk/res-auto}'
CUSTOM = 'com.scamera.app.ui.settings.custompreferences.'
# the camera screen's Pro panel glyphs live in the manual-controls library (circularbarlib)
DRAWABLE_DIRS = [RES / 'drawable', ROOT / 'circularbarlib/src/main/res/drawable']


def drawable(name):
    for d in DRAWABLE_DIRS:
        if (d / (name + '.xml')).exists():
            return d / (name + '.xml')
    return RES / 'drawable' / (name + '.xml')


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
    root = ET.parse(drawable(name)).getroot()
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


def java_constants(path):
    """`NAME = "value"` string constants of a Java file"""
    return dict(re.findall(r'\b([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"', path.read_text()))


def java_block(text, start):
    """the text between `start` and the matching closing bracket of the first `(` or `{` after it"""
    i = text.index(start) + len(start)
    while text[i] not in '({':
        i += 1
    depth, j = 0, i
    while True:
        if text[j] in '({':
            depth += 1
        elif text[j] in ')}':
            depth -= 1
            if depth == 0:
                return text[i + 1:j]
        j += 1


def develop():
    """DevelopCatalog's groups, ScamLabFragment's AT_SHOT / BURST_FRAMES and PreferenceKeys.TUNING_KEYS (the merge's keys)"""
    catalog = (JAVA / 'ui/settings/DevelopCatalog.java').read_text()
    names = java_constants(JAVA / 'ui/settings/DevelopCatalog.java')
    named = {'FilmLut': java_constants(JAVA / 'processing/color/FilmLut.java'),
             'LensFilters': java_constants(JAVA / 'processing/color/LensFilters.java')}
    groups = []
    for g in re.findall(r'\{([^{}]*)\}', java_block(catalog, 'static final String[][] GROUPS =')):
        row = []
        for token in [t.strip() for t in g.split(',') if t.strip()]:
            if token.startswith('"'):
                row.append(token.strip('"'))
            elif '.' in token:
                cls, const = token.split('.')
                row.append(named[cls][const])
            else:
                row.append(names[token])
        groups.append(row)
    lab = (JAVA / 'ui/settings/ScamLabFragment.java').read_text()
    at_shot = re.findall(r'"(\w+)"', java_block(lab, 'AT_SHOT = new java.util.HashSet<>'))
    burst = re.findall(r'"(\w+)"', java_block(lab, 'BURST_FRAMES = new java.util.HashSet<>'))
    keys = (JAVA / 'settings/PreferenceKeys.java').read_text()
    tuning = re.findall(r'\{"\w+",\s*"(\w+)"\}', java_block(keys, 'private static final String[][] TUNING_KEYS ='))
    return {'groups': groups, 'atShot': sorted(at_shot), 'burstFrames': sorted(burst),
            'mergeTuning': sorted('pref_scam_hybrid_' + k for k in set(tuning))}


LITERAL = r'"(?:[^"\\]|\\.)*"'


def java_string(lit):
    """a Java string literal's text"""
    return re.sub(r'\\(u[0-9a-fA-F]{4}|.)', lambda m: chr(int(m.group(1)[1:], 16)) if m.group(1)[0] == 'u' and len(m.group(1)) == 5
                  else {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)), lit[1:-1])


def help_map():
    """SettingHelp.EXACT / FAMILIES / STATE_KEYS: the help string of a key (its strings go into the tables)"""
    text = (JAVA / 'ui/settings/SettingHelp.java').read_text()
    exact, families = {}, []
    for key, name in re.findall(r'EXACT\.put\("([^"]+)",\s*R\.string\.(\w+)\)', text):
        if name in EN_S:
            exact[key] = name
            used_strings.add(name)
    for pattern, name in re.findall(r'FAMILIES\.put\(Pattern\.compile\((' + LITERAL + r')\),\s*R\.string\.(\w+)\)', text):
        if name in EN_S:
            families.append([java_string(pattern), name])
            used_strings.add(name)
    state = re.findall(r'"(\w+)"', java_block(text, 'STATE_KEYS = new java.util.HashSet<>'))
    return {'exact': exact, 'families': families, 'stateKeys': sorted(state)}


def java_args(text):
    """the top-level comma-separated arguments of a call's text"""
    args, depth, cur, i = [], 0, '', 0
    while i < len(text):
        c = text[i]
        if c == '"':
            j = i + 1
            while text[j] != '"':
                j += 2 if text[j] == '\\' else 1
            cur += text[i:j + 1]
            i = j + 1
            continue
        if c in '({':
            depth += 1
        elif c in ')}':
            depth -= 1
        if c == ',' and depth == 0:
            args.append(cur.strip())
            cur = ''
        else:
            cur += c
        i += 1
    if cur.strip():
        args.append(cur.strip())
    return args


def java_text(expr):
    """a string expression of literals joined by +; None for anything else (null, a constant)"""
    lits = re.findall(LITERAL, expr)
    if not lits or re.sub(LITERAL + r'|\+|\s', '', expr):
        return None
    return ''.join(java_string(x) for x in lits)


def lang(expr, names):
    """Lang.t("ru", "en"), a literal or a group variable as {"ru", "en"}"""
    expr = expr.strip()
    if expr in names:
        return names[expr]
    m = re.fullmatch(r'Lang\.t\((.*)\)', expr, re.S)
    if m:
        a = java_args(m.group(1))
        if len(a) == 2 and java_text(a[0]) is not None and java_text(a[1]) is not None:
            return {'ru': java_text(a[0]), 'en': java_text(a[1])}
        return None
    t = java_text(expr)
    return {'ru': t, 'en': t} if t is not None else None


def dev_keys():
    """DevKeys.all(): every k.add(s / sAuto / n (...)) with its group's names"""
    text = (JAVA / 'settings/DevKeys.java').read_text()
    body = java_block(text, 'public static synchronized List<Key> all()')
    names = {}
    for fn, ru, en in re.findall(r'private static String (\w+)\(\) \{ return Lang\.t\("([^"]*)", "([^"]*)"\); \}', text):
        names[fn + '()'] = {'ru': ru, 'en': en}
    for var, expr in re.findall(r'final String (\w+) = ([^;]+);', body):
        v = lang(expr, names)
        if v:
            names[var] = v
    out, at = [], 0
    while True:
        at = body.find('k.add(', at)
        if at < 0:
            break
        call = java_block(body[at:], 'k.add')
        at += len(call) + 6
        m = re.match(r'\s*(sAuto|s|n)\((.*)\)\s*$', call, re.S)
        if not m:
            continue
        kind, a = m.group(1), java_args(m.group(2))
        group, key = lang(a[0], names), java_text(a[1])
        if group is None or key is None or len(a) < 3:
            continue
        if kind == 'sAuto':
            default, meaning = None, lang(a[2], names)
        elif kind == 's':
            default, meaning = '1' if a[2] == 'true' else '0', lang(a[3], names)
        else:
            default, meaning = java_text(a[2]), lang(a[3], names)
        if meaning is None:
            continue
        out.append({'g': group, 'k': key, 'n': kind == 'n', 'd': default, 't': meaning})
    return out


def render():
    tree = node(ET.parse(RES / 'xml/preferences.xml').getroot())
    helps = help_map()
    # the module cards' texts (ModuleRows / ModuleActions build their rows in code)
    for name in EN_S:
        if name.startswith(('module_kind_', 'module_state_', 'module_row_')):
            used_strings.add(name)
    if EXTRA_ICONS.exists():
        for line in EXTRA_ICONS.read_text().split():
            if drawable(line).exists():
                used_icons.add(line)

    def table(strings, arrays, fallback_s, fallback_a):
        return {'strings': {k: strings.get(k, fallback_s[k]) for k in sorted(used_strings)},
                'arrays': {k: arrays.get(k, fallback_a[k]) if len(arrays.get(k, [])) == len(fallback_a[k]) else fallback_a[k]
                           for k in sorted(used_arrays)}}
    doc = {'generated': 'ios/tools/gen_settings_ui.py from app/src/main/res: do not edit', 'tree': tree,
           'en': table(EN_S, EN_A, EN_S, EN_A), 'ru': table(RU_S, RU_A, EN_S, EN_A), 'develop': develop(),
           'help': helps, 'devKeys': dev_keys()}
    text = json.dumps(doc, ensure_ascii=False, indent=1, sort_keys=True) + '\n'
    # the iOS port never names these (the iPhone's own gallery is «Photos»)
    for old, new in (('галерея Google Photos', 'приложение «Фото»'), ('Google Photos', 'Photos')):
        text = text.replace(old, new)
    for word in ('Google', 'GCam', 'HDR+'):
        if word in text:
            sys.exit('a settings text names ' + word + ': add its replacement to render()')
    files = {OUT_JSON: text}
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
