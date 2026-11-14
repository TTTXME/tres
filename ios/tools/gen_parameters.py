#!/usr/bin/env python3
"""ios/Engine/post/Parameters.hpp from the Android Parameters.java: its instance fields with their defaults and the lab's
ScamLabParams.fromJson / toJson (the burst json's "params", the develop record's). The fields ScamLabParams skips and the buffers
are left out.
  gen_parameters.py           write the header
  gen_parameters.py --check   exit 1 when the header is not what the Android source gives
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/java/com/scamera/app/processing/render/Parameters.java'
OUT = ROOT / 'ios/Engine/post/Parameters.hpp'
LAB = ROOT / 'app/src/main/java/com/scamera/app/processing/opengl/postpipeline/ScamLabParams.java'


def stored_keys():
    """ScamLabParams.STORED_KEYS: the json key (and the C++ field name) of each field renamed since the format was made"""
    src = LAB.read_text()
    body = src[src.index('STORED_KEYS = storedKeys('):]
    body = body[:body.index(');')]
    names = re.findall(r'"(\w+)"', body)
    return dict(zip(names[0::2], names[1::2]))

# ScamLabParams.SKIPPED and the types fromJson does not decode
SKIPPED = {'scamRgb', 'scamRgbOwned', 'hotSites', 'dngColour', 'manualWbAtPress', 'mosaicWbAtPress', 'rawCrop', 'sabreCrop',
           'dngNoiseProfile', 'dngBaselineExposure', 'blackFloorSource'}

TYPES = {
    'boolean': 'bool', 'int': 'int', 'long': 'int64_t', 'byte': 'int', 'short': 'int', 'float': 'float', 'double': 'double',
    'String': 'std::string', 'Integer': 'std::optional<int>',
    'float[]': 'std::optional<std::vector<float>>', 'double[]': 'std::optional<std::vector<double>>',
    'int[]': 'std::optional<std::vector<int>>', 'float[][]': 'std::vector<std::optional<std::vector<float>>>',
    'Point': 'std::optional<Point>', 'android.graphics.Point': 'std::optional<Point>', 'android.graphics.PointF': 'std::optional<PointF>',
    'Rect': 'std::optional<Rect>', 'SizeF': 'std::optional<SizeF>',
    'NoiseModeler': 'std::optional<NoiseModel>', 'ColorCorrectionTransform': 'std::optional<ColorCorrection>',
}
DECODE = {
    'bool': 'v.boolean()', 'int': 'v.integer()', 'int64_t': 'int64_t(v.number())', 'float': 'float(v.number())',
    'double': 'v.number()', 'std::string': 'v.text()', 'std::optional<int>': 'v.integer()',
    'std::optional<std::vector<float>>': 'floats(v)', 'std::optional<std::vector<double>>': 'doubles(v)',
    'std::optional<std::vector<int>>': 'ints(v)', 'std::vector<std::optional<std::vector<float>>>': 'floatRows(v)',
    'std::optional<Point>': 'Point{v["x"].integer(), v["y"].integer()}', 'std::optional<PointF>': 'PointF{float(v["x"].number()), float(v["y"].number())}',
    'std::optional<Rect>': 'Rect{v["l"].integer(), v["t"].integer(), v["r"].integer(), v["b"].integer()}',
    'std::optional<SizeF>': 'SizeF{float(v["w"].number()), float(v["h"].number())}',
    'std::optional<NoiseModel>': 'noiseModel(v)', 'std::optional<ColorCorrection>': 'ColorCorrection{floats(v["matrix"])}',
}

# ScamLabParams.encode of each type (an unset optional is left out, as a null field)
ENCODE = {
    'bool': 'o.put("{k}", {k});', 'int': 'o.put("{k}", {k});', 'int64_t': 'o.put("{k}", {k});', 'float': 'o.put("{k}", {k});',
    'double': 'o.put("{k}", {k});', 'std::string': 'o.put("{k}", {k});', 'std::optional<int>': 'o.put("{k}", {k});',
    'std::optional<std::vector<float>>': 'o.put("{k}", {k});', 'std::optional<std::vector<double>>': 'o.put("{k}", {k});',
    'std::optional<std::vector<int>>': 'o.put("{k}", {k});', 'std::vector<std::optional<std::vector<float>>>': 'o.raw("{k}", rowsJson({k}));',
    'std::optional<Point>': 'if ({k}) o.raw("{k}", JsonOut().put("x", {k}->x).put("y", {k}->y).text());',
    'std::optional<PointF>': 'if ({k}) o.raw("{k}", JsonOut().put("x", {k}->x).put("y", {k}->y).text());',
    'std::optional<Rect>': 'if ({k}) o.raw("{k}", JsonOut().put("l", {k}->left).put("t", {k}->top).put("r", {k}->right).put("b", {k}->bottom).text());',
    'std::optional<SizeF>': 'if ({k}) o.raw("{k}", JsonOut().put("w", {k}->width).put("h", {k}->height).text());',
    'std::optional<NoiseModel>': 'if ({k}) o.raw("{k}", noiseJson(*{k}));',
    'std::optional<ColorCorrection>': 'if ({k}) o.raw("{k}", JsonOut().put("matrix", {k}->matrix).text());',
}


def fields():
    src = SRC.read_text()
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    src = re.sub(r'"(?:\\.|[^"\\])*"', '""', src)
    src = re.sub(r'@[\w.]+(\s*\([^)]*\))?', '', src)
    body = src[src.index('{', src.index('class Parameters')) + 1:]
    depth, buf, stmts = 0, '', []
    for ch in body:
        if ch == '{':
            depth += 1
            if depth == 1:
                buf = ''
            continue
        if ch == '}':
            depth -= 1
            buf = ''
            continue
        if depth == 0:
            if ch == ';':
                stmts.append(' '.join(buf.split()))
                buf = ''
            else:
                buf += ch
    out = []
    keys = stored_keys()
    for s in stmts:
        if not s or '(' in s.split('=')[0]:
            continue
        m = re.match(r'((?:public|private|protected|static|final|transient|volatile)\s+)*([\w.<>\[\]?]+?)\s+(\w+\s*(?:[=,].*)?)$', s)
        if not m:
            raise SystemExit('unparsed field: ' + s)
        if any(x in s[:m.start(2)] for x in ('static', 'final', 'transient')):
            continue
        for decl in declarators(m.group(3)):
            name, _, init = decl.partition('=')
            name = name.strip()
            if name in SKIPPED:
                continue
            t = m.group(2)
            if t not in TYPES:
                raise SystemExit('field %s: type %s is not mapped' % (name, t))
            out.append((TYPES[t], keys.get(name, name), init.strip()))
    return out


def declarators(text):
    """`a = x, b = new float[9]` -> ['a = x', 'b = new float[9]'] (commas inside brackets / parentheses kept)"""
    out, depth, buf = [], 0, ''
    for ch in text:
        if ch in '([':
            depth += 1
        elif ch in ')]':
            depth -= 1
        if ch == ',' and depth == 0:
            out.append(buf)
            buf = ''
        else:
            buf += ch
    return out + [buf]


def default(ctype, init):
    if not init:
        return '' if ctype.startswith('std::') else '{}'
    init = init.replace('Float.NaN', 'NAN').replace('Double.NaN', 'NAN').replace('Float.MAX_VALUE', 'FLT_MAX')
    m = re.fullmatch(r'new (float|double|int)\[(\d+)\]', init)
    if m:
        return '{std::vector<%s>(%s)}' % (m.group(1), m.group(2))
    m = re.fullmatch(r'new float\[(\d+)\]\[\]', init)
    if m:
        return '{std::vector<std::optional<std::vector<float>>>(%s)}' % m.group(1)
    m = re.fullmatch(r'new Point\((-?\d+), (-?\d+)\)', init)
    if m:
        return '{Point{%s, %s}}' % m.groups()
    if init == '""':
        return '{}'
    if init.startswith('ScameraApp.'):
        return '{}'  # the camera id of the settings: the json always has it
    init = re.sub(r'(\d+(?:\.\d+)?)f\b', lambda m: m.group(1) + ('f' if '.' in m.group(1) else '.f'), init)
    return '{%s}' % init


def render():
    fs = fields()
    lines = ['// Generated by ios/tools/gen_parameters.py from Parameters.java - do not edit.',
             '// The post pipeline\'s shot parameters with the Android defaults, and ScamLabParams.fromJson / toJson.',
             '#pragma once', '', '#include "Json.hpp"', '#include "JsonOut.hpp"', '#include "GLCore.hpp"', '',
             '#include <cfloat>', '#include <cmath>', '#include <cstdint>', '#include <optional>', '#include <string>',
             '#include <utility>', '#include <vector>', '', 'namespace scam_post {', '',
             'struct PointF { float x = 0, y = 0; };',
             'struct Rect { int left = 0, top = 0, right = 0, bottom = 0; };',
             'struct SizeF { float width = 0, height = 0; };',
             'struct ColorCorrection { std::vector<float> matrix = std::vector<float>(9); };',
             '// NoiseModeler as the lab restores it: its base / compute (S, O) pairs per channel and the ISOs',
             'struct NoiseModel {',
             '    std::vector<std::pair<double, double>> baseModel, computeModel;',
             '    /// the profile as the camera reported it (NoiseModeler.reportedModel: SENSOR_NOISE_PROFILE after sanitizedOffsets; the lab',
             '    /// restores it as its base pairs); unset: none (reportedMean takes its fallback)',
             '    std::optional<std::vector<std::pair<double, double>>> reportedModel;',
             '    int AnalogueISO = 0, SensivityISO = 0;',
             '    float isoScale = 1;',
             '};', '', 'struct Parameters {']
    for ctype, name, init in fs:
        lines.append('    %s %s%s;' % (ctype, name, default(ctype, init)))
    lines += ['',
              '    static Parameters fromJson(const Json& in) {',
              '        Parameters p;']
    for ctype, name, _ in fs:
        lines.append('        if (const Json& v = in["%s"]; !v.isNull()) p.%s = %s;' % (name, name, DECODE[ctype]))
    lines += ['        return p;', '    }', '',
              '    /// ScamLabParams.toJson: every field in its stored form (an unset optional left out)',
              '    std::string toJson() const {',
              '        JsonOut o;']
    for ctype, name, _ in fs:
        lines.append('        ' + ENCODE[ctype].replace('{k}', name))
    lines += ['        return o.text();', '    }', '',
              'private:',
              '    static std::vector<float> floats(const Json& v) {',
              '        std::vector<float> out(v.size());',
              '        for (size_t i = 0; i < out.size(); ++i) out[i] = float(v[i].number());',
              '        return out;',
              '    }',
              '    static std::vector<double> doubles(const Json& v) {',
              '        std::vector<double> out(v.size());',
              '        for (size_t i = 0; i < out.size(); ++i) out[i] = v[i].number();',
              '        return out;',
              '    }',
              '    static std::vector<int> ints(const Json& v) {',
              '        std::vector<int> out(v.size());',
              '        for (size_t i = 0; i < out.size(); ++i) out[i] = v[i].integer();',
              '        return out;',
              '    }',
              '    static std::vector<std::optional<std::vector<float>>> floatRows(const Json& v) {',
              '        std::vector<std::optional<std::vector<float>>> out(v.size());',
              '        for (size_t i = 0; i < out.size(); ++i) if (!v[i].isNull()) out[i] = floats(v[i]);',
              '        return out;',
              '    }',
              '    static std::vector<std::pair<double, double>> pairs(const Json& v) {',
              '        std::vector<std::pair<double, double>> out(v.size());',
              '        for (size_t i = 0; i < out.size(); ++i) out[i] = {v[i][0].number(), v[i][1].number()};',
              '        return out;',
              '    }',
              '    static NoiseModel noiseModel(const Json& v) {',
              '        NoiseModel n;',
              '        n.baseModel = pairs(v["base"]);',
              '        n.reportedModel = n.baseModel; // ScamLabParams: new NoiseModeler(base, ...)',
              '        n.computeModel = pairs(v["compute"]);',
              '        n.AnalogueISO = v["analog"].integer();',
              '        n.SensivityISO = v["iso"].integer();',
              '        return n;',
              '    }',
              '    static std::string pairsJson(const std::vector<std::pair<double, double>>& model) {',
              '        JsonOut a(true);',
              '        for (const auto& q : model) a.add(JsonOut(true).add(JsonText::number(q.first)).add(JsonText::number(q.second)).text());',
              '        return a.text();',
              '    }',
              '    static std::string noiseJson(const NoiseModel& n) {',
              '        return JsonOut().raw("base", pairsJson(n.baseModel)).raw("compute", pairsJson(n.computeModel)).put("analog", n.AnalogueISO)',
              '            .put("iso", n.SensivityISO).text();',
              '    }',
              '    static std::string rowsJson(const std::vector<std::optional<std::vector<float>>>& rows) {',
              '        JsonOut a(true);',
              '        for (const auto& r : rows) a.add(r ? JsonOut::arrayText(*r) : std::string("null"));',
              '        return a.text();',
              '    }',
              '};', '', '} // namespace scam_post', '']
    return '\n'.join(lines)


if __name__ == '__main__':
    text = render()
    if '--check' in sys.argv:
        if not OUT.exists() or OUT.read_text() != text:
            print('ios/Engine/post/Parameters.hpp is out of date: run ios/tools/gen_parameters.py')
            sys.exit(1)
        print('Parameters.hpp up to date')
    else:
        OUT.write_text(text)
        print(OUT, len(text.splitlines()), 'lines')
