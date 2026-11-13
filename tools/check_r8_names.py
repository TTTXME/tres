#!/usr/bin/env python3
"""P662: the shipped APK is shrunk by R8 (app/proguard-rules.pro). Every class the app reaches by name must be in its dex:
manifest components, class names in compiled resource XML (views, preferences, fragments, navigation) and in resource values
(viewInflaterClass), the Java_* JNI entry points of the native libraries CMake builds, the su launcher's app_process main,
and the classes a library makes from their Class object (a constructor must be left).
usage: check_r8_names.py <apk> [<repository>]   (aapt2: $AAPT2, else $ANDROID_HOME/build-tools/36.0.0/aapt2)"""
import glob
import os
import re
import struct
import subprocess
import sys
import zipfile

PKG = 'com.scamera.app'
COMPONENTS = ('activity', 'service', 'receiver', 'provider', 'application', 'instrumentation')


def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7f) << s
        s += 7
        if x < 0x80:
            return r, o


def dex_classes(data, defined, methods):
    """The classes a dex defines and the names of their methods (with True for a native one)."""
    _, so, tz, to, _, _, _, _, _, mo, cz, co = struct.unpack_from('<12I', data, 56)

    def string(i):
        off = struct.unpack_from('<I', data, so + 4 * i)[0]
        _, o = uleb(data, off)
        return data[o:data.index(b'\0', o)].decode('utf-8', 'replace')

    types = [string(struct.unpack_from('<I', data, to + 4 * i)[0]) for i in range(tz)]
    for i in range(cz):
        cls, _, _, _, _, _, cdo, _ = struct.unpack_from('<8I', data, co + 32 * i)
        name = types[cls]
        defined.add(name)
        ms = methods.setdefault(name, {})
        if cdo == 0:
            continue
        sf, o = uleb(data, cdo)
        inf, o = uleb(data, o)
        dm, o = uleb(data, o)
        vm, o = uleb(data, o)
        for _ in range(sf + inf):
            _, o = uleb(data, o)
            _, o = uleb(data, o)
        idx = 0
        for k in range(dm + vm):
            if k == dm:
                idx = 0
            d, o = uleb(data, o)
            idx += d
            access, o = uleb(data, o)
            _, o = uleb(data, o)
            nm = struct.unpack_from('<I', data, mo + 8 * idx + 4)[0]
            ms.setdefault(string(nm), set()).add(bool(access & 0x100))


def desc(fqcn):
    return 'L' + fqcn.replace('.', '/') + ';'


def xml_names(aapt2, apk):
    """(class name, file, what) for every class name a compiled XML of the APK holds."""
    out = []
    with zipfile.ZipFile(apk) as z:
        files = [n for n in z.namelist() if n.endswith('.xml') and (n.startswith('res/') or n == 'AndroidManifest.xml')]
    res = [n for n in files if n != 'AndroidManifest.xml']
    # the resource XML in batches (one aapt2 run per 200 files; the class names are reported per batch)
    batches = [('AndroidManifest.xml', ['AndroidManifest.xml'])] + [
        ('resource XML %s..%s' % (res[i], res[min(i + 199, len(res) - 1)]), res[i:i + 200]) for i in range(0, len(res), 200)]
    for f, names in batches:
        cmd = [aapt2, 'dump', 'xmltree', apk]
        for n in names:
            cmd += ['--file', n]
        run = subprocess.run(cmd, capture_output=True, text=True)
        txt = run.stdout
        if run.returncode != 0:  # a plain (uncompiled) XML among them, e.g. res/raw: file by file, those skipped
            txt = ''.join(subprocess.run([aapt2, 'dump', 'xmltree', apk, '--file', n], capture_output=True, text=True).stdout
                          for n in names)
        if f == 'AndroidManifest.xml':
            element = None
            for line in txt.splitlines():
                e = re.search(r'E: ([\w-]+)', line)
                if e:
                    element = e.group(1)
                    continue
                a = re.search(r'A: [\w:/.]*:(name|targetActivity)\([^)]*\)="([\w.$]+)"', line)
                if a and element in COMPONENTS or a and a.group(1) == 'targetActivity':
                    v = a.group(2)
                    out.append((PKG + v if v.startswith('.') else v, f, element))
            continue
        for m in re.finditer(r'E: ([A-Za-z_][\w.$]*\.[A-Z][\w$]*)', txt):
            out.append((m.group(1), f, 'tag'))
        for m in re.finditer(r'A: (?:[\w:/.]*:)?(\w+)\([^)]*\)="([A-Za-z_][\w.$]*)"', txt):
            attr, v = m.groups()
            if attr in ('action', 'targetPackage', 'authorities', 'scheme', 'host', 'mimeType'):
                continue  # intents and URIs, not classes
            if re.fullmatch(r'([a-z_]\w*\.)+[A-Z][\w$]*', v):
                out.append((v, f, attr))
    return out


def resource_values(aapt2, apk):
    """Style items and strings whose value is a class name (viewInflaterClass, layout behaviours)."""
    txt = subprocess.run([aapt2, 'dump', 'resources', apk], capture_output=True, text=True, check=True).stdout
    return [(m.group(1), 'resources.arsc', 'value')
            for m in re.finditer(r'"((?:com|androidx)\.[\w.$]+\.[A-Z][\w$]*)"', txt)]


def built_jni_sources(repo):
    """The C++ files CMakeLists.txt compiles (a source left out of every target - scam-probe.cpp - is not in the APK)."""
    cmake = open(os.path.join(repo, 'app/src/main/cpp/CMakeLists.txt'), errors='replace').read()
    cmake = re.sub(r'#.*', '', cmake)
    return [f for f in glob.glob(os.path.join(repo, 'app/src/main/cpp/*.cpp')) if os.path.basename(f) in cmake]


def jni_symbols(repo):
    out = []
    for f in built_jni_sources(repo):
        src = open(f, errors='replace').read()
        names = [m.group(1) for m in re.finditer(r'\bJava_((?:[A-Za-z0-9]|_1)+(?:_(?:[A-Za-z0-9]|_1)+)+)\s*\(', src)]
        # entry points made by a macro: #define X(type, name) ... Java_<class>_##name, then X(jint, method)(...)
        for d in re.finditer(r'#define (\w+)\(\s*\w+\s*,\s*(\w+)\s*\)[^\n]*\bJava_(\w+?)_##\2', src):
            names += [d.group(3) + '_' + u.group(1) for u in re.finditer(r'\b' + d.group(1) + r'\(\s*[^,()]+,\s*(\w+)\s*\)', src)]
        for name in names:
            parts = [p.replace('\x01', '_') for p in name.replace('_1', '\x01').split('_')]
            out.append(('.'.join(parts[:-1]), parts[-1], os.path.basename(f)))
    return out


def reflective_constructions(repo):
    """Our classes a library instantiates from their Class object."""
    out = []
    pattern = re.compile(r'(?:setRegionDecoderClass|setBitmapDecoderClass|CompatDecoderFactory\s*(?:<[^>]*>)?\s*\(|'
                         r'getDeclaredConstructor|newInstance)\s*\(?\s*(\w+)\.class')
    for f in glob.glob(os.path.join(repo, 'app/src/main/java/**/*.java'), recursive=True):
        for m in pattern.finditer(open(f, errors='replace').read()):
            out.append((m.group(1), os.path.relpath(f, repo)))
    return out


def check(apk, repo, aapt2):
    defined, methods = set(), {}
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if re.fullmatch(r'classes\d*\.dex', n):
                dex_classes(z.read(n), defined, methods)
    problems, warnings, checked = [], [], 0
    aliases = set()
    manifest = subprocess.run([aapt2, 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml'], capture_output=True, text=True,
                              check=True).stdout
    for block in manifest.split('E: activity-alias')[1:]:
        m = re.search(r':name\([^)]*\)="([\w.$]+)"', block)
        if m:
            aliases.add(PKG + m.group(1) if m.group(1).startswith('.') else m.group(1))
    for name, where, what in xml_names(aapt2, apk) + resource_values(aapt2, apk):
        if name in aliases or name.startswith('android.') and not name.startswith('android.support.'):
            continue  # an activity-alias, a framework class
        checked += 1
        if desc(name) not in defined:
            problems.append('%s: %s (%s) is not in the dex' % (where, name, what))
    for cls in ('com.scamera.app.processing.opengl.postpipeline.ScamNeuralWorker',):
        checked += 1
        if 'main' not in methods.get(desc(cls), {}):
            problems.append('su launcher: %s.main is not in the dex (app_process starts it by name)' % cls)
    for simple, where in reflective_constructions(repo):
        checked += 1
        found = [c for c in defined if c.startswith('Lcom/scamera/') and (c.endswith('/' + simple + ';') or c.endswith('$' + simple + ';'))]
        if not any('<init>' in methods.get(c, {}) for c in found):
            problems.append('%s: %s is made from its Class but has no constructor in the dex' % (where, simple))
    # every native method the dex keeps must have its Java_* entry point in a built library (R8 must not rename or move it)
    jni = {(c, m) for c, m, _ in jni_symbols(repo)}
    for c, ms in methods.items():
        if not c.startswith('Lcom/scamera/'):
            continue
        fq = c[1:-1].replace('/', '.')
        for m, flags in ms.items():
            if True in flags:
                checked += 1
                if (fq, m) not in jni:  # (a native whose library is gone: unbound before R8 too, an error only if called)
                    warnings.append('JNI: native %s.%s has no Java_ entry point in a built library' % (fq, m))
    return checked, problems, warnings


def main():
    apk = sys.argv[1]
    repo = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
    aapt2 = os.environ.get('AAPT2') or os.path.join(os.environ.get('ANDROID_HOME', '/opt/android-sdk'), 'build-tools/36.0.0/aapt2')
    checked, problems, warnings = check(apk, repo, aapt2)
    for w in warnings:
        print('R8 NAMES WARN:', w)
    for p in problems:
        print('R8 NAMES:', p)
    print('R8 names %s: %d checked, %d missing' % ('PASS' if not problems else 'FAIL', checked, len(problems)))
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()
