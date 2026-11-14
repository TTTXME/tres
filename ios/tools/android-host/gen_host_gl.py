#!/usr/bin/env python3
"""Host GL for the Android post pipeline under Robolectric: shadows of android.opengl.GLES20 / 30 / 31 / 32 whose methods call
the host's GLES (Mesa) through JNI, generated from the android.jar signatures and the Khronos header. A Java overload maps onto
the C function when its arguments line up (an array + offset or a java.nio.Buffer for each pointer, an int for an offset into a
bound buffer); the few with String arguments / results are written by hand. Every other method throws, never a silent no-op.
  gen_host_gl.py <android.jar> <GLES3/gl32.h> <out dir>
"""
import re
import subprocess
import sys
from pathlib import Path

jar, header, out = sys.argv[1], sys.argv[2], Path(sys.argv[3])
CLASSES = ['GLES20', 'GLES30', 'GLES31', 'GLES32']
SCALAR = {'int': ('jint', 'I'), 'float': ('jfloat', 'F'), 'boolean': ('jboolean', 'Z'), 'long': ('jlong', 'J'),
          'byte': ('jbyte', 'B'), 'short': ('jshort', 'S')}
ARRAY = {'int[]': ('jintArray', 'Int', 'jint'), 'float[]': ('jfloatArray', 'Float', 'jfloat'), 'long[]': ('jlongArray', 'Long', 'jlong'),
         'boolean[]': ('jbooleanArray', 'Boolean', 'jboolean'), 'byte[]': ('jbyteArray', 'Byte', 'jbyte'),
         'short[]': ('jshortArray', 'Short', 'jshort')}
BUFFERS = {'java.nio.Buffer', 'java.nio.ByteBuffer', 'java.nio.IntBuffer', 'java.nio.FloatBuffer', 'java.nio.LongBuffer',
           'java.nio.ShortBuffer'}
HANDLE = {'GLsync'}  # pointer handles passed as a Java long


def c_prototypes():
    text = Path(header).read_text()
    protos = {}
    for m in re.finditer(r'GL_APICALL\s+(.*?)\s*GL_APIENTRY\s+(\w+)\s*\((.*?)\);', text, re.S):
        ret, name, params = ' '.join(m.group(1).split()), m.group(2), ' '.join(m.group(3).split())
        types = []
        if params != 'void':
            for p in params.split(','):
                p = p.strip()
                t = re.sub(r'\s*\b\w+$', '', p) if not p.endswith('*') else p
                types.append(t.strip())
        protos[name] = (ret, types)
    return protos


def java_methods(cls):
    text = subprocess.run(['javap', '-cp', jar, 'android.opengl.' + cls], capture_output=True, text=True, check=True).stdout
    out = []
    for line in text.splitlines():
        m = re.match(r'\s*public static (?:native )?(\S+) (\w+)\((.*)\);', line)
        if m:
            args = [a.strip().replace('$', '.') for a in m.group(3).split(',')] if m.group(3).strip() else []
            out.append((m.group(1), m.group(2), args))
    return out


def is_pointer(ctype):
    return '*' in ctype


def plan(name, jret, jargs, proto):
    """The C call of a Java overload: (java params, c args, prologue, epilogue) or None when it does not map."""
    cret, ctypes = proto
    jparams, cargs, pro, epi = [], [], [], []
    i = 0
    for k, ct in enumerate(ctypes):
        if i >= len(jargs):
            return None
        jt = jargs[i]
        if is_pointer(ct):
            if jt in ARRAY:
                if i + 1 >= len(jargs) or jargs[i + 1] != 'int':
                    return None
                jni, kind, elem = ARRAY[jt]
                a, o = 'a%d' % i, 'a%d' % (i + 1)
                jparams += [(jni, a, jt), ('jint', o, 'int')]
                pro.append('%s* %s_e = %s ? (*env)->Get%sArrayElements(env, %s, NULL) : NULL;' % (elem, a, a, kind, a))
                epi.append('if (%s) (*env)->Release%sArrayElements(env, %s, %s_e, 0);' % (a, kind, a, a))
                cargs.append('(%s)(%s_e ? %s_e + %s : NULL)' % (ct, a, a, o))
                i += 2
            elif jt in BUFFERS:
                a, o = 'a%d' % i, 'a%d_off' % i
                jparams += [('jobject', a, 'java.nio.Buffer'), ('jint', o, 'int')]
                cargs.append('(%s)(%s ? (char*)(*env)->GetDirectBufferAddress(env, %s) + %s : NULL)' % (ct, a, a, o))
                i += 1
            elif jt == 'int':
                a = 'a%d' % i
                jparams.append(('jint', a, jt))
                cargs.append('(%s)(intptr_t)%s' % (ct, a))
                i += 1
            elif jt == 'java.lang.String':
                a = 'a%d' % i
                jparams.append(('jstring', a, jt))
                pro.append('const char* %s_s = %s ? (*env)->GetStringUTFChars(env, %s, NULL) : NULL;' % (a, a, a))
                epi.append('if (%s) (*env)->ReleaseStringUTFChars(env, %s, %s_s);' % (a, a, a))
                cargs.append('(%s)%s_s' % (ct, a))
                i += 1
            else:
                return None
        else:
            if jt not in SCALAR:
                return None
            if ct in HANDLE and jt != 'long':
                return None
            a = 'a%d' % i
            jparams.append((SCALAR[jt][0], a, jt))
            cargs.append('(%s)(intptr_t)%s' % (ct, a) if ct in HANDLE else '(%s)%s' % (ct, a))
            i += 1
    if i != len(jargs):
        return None
    if jret == 'void':
        jniret, call = 'void', '%s(%s);' % (name, ', '.join(cargs))
        ret = ''
    elif jret in SCALAR:
        jniret = SCALAR[jret][0]
        expr = '%s(%s)' % (name, ', '.join(cargs))
        call = '%s r = (%s)%s;' % (jniret, jniret, '(intptr_t)' + expr if cret in HANDLE or is_pointer(cret) else expr)
        ret = 'return r;'
    elif jret == 'java.lang.String' and name == 'glGetString':
        jniret, call, ret = 'jstring', 'const GLubyte* s = %s(%s);' % (name, ', '.join(cargs)), 'return s ? (*env)->NewStringUTF(env, (const char*)s) : NULL;'
    elif jret == 'java.nio.Buffer' and name == 'glMapBufferRange':
        jniret = 'jobject'
        call = 'void* p = %s(%s);' % (name, ', '.join(cargs))
        ret = 'return p ? (*env)->NewDirectByteBuffer(env, p, (jlong)a2) : NULL;'
    else:
        return None
    return jparams, jniret, pro, call, epi, ret


HAND_JAVA = {
    ('glShaderSource', ('int', 'java.lang.String')): 'shaderSource',
    ('glGetShaderInfoLog', ('int',)): 'shaderInfoLog',
    ('glGetProgramInfoLog', ('int',)): 'programInfoLog',
}

HAND_C = r'''
JNIEXPORT void JNICALL Java_scam_host_HostGL_shaderSource(JNIEnv* env, jclass c, jint shader, jstring source) {
    const char* s = (*env)->GetStringUTFChars(env, source, NULL);
    glShaderSource((GLuint)shader, 1, &s, NULL);
    (*env)->ReleaseStringUTFChars(env, source, s);
}
static jstring infoLog(JNIEnv* env, GLuint id, int program) {
    GLint n = 0;
    if (program) glGetProgramiv(id, GL_INFO_LOG_LENGTH, &n); else glGetShaderiv(id, GL_INFO_LOG_LENGTH, &n);
    char* buf = (char*)calloc((size_t)(n > 0 ? n : 1) + 1, 1);
    if (n > 0) { if (program) glGetProgramInfoLog(id, n, NULL, buf); else glGetShaderInfoLog(id, n, NULL, buf); }
    jstring s = (*env)->NewStringUTF(env, buf);
    free(buf);
    return s;
}
JNIEXPORT jstring JNICALL Java_scam_host_HostGL_shaderInfoLog(JNIEnv* env, jclass c, jint id) { return infoLog(env, (GLuint)id, 0); }
JNIEXPORT jstring JNICALL Java_scam_host_HostGL_programInfoLog(JNIEnv* env, jclass c, jint id) { return infoLog(env, (GLuint)id, 1); }
'''


def main():
    protos = c_prototypes()
    (out / 'java/scam/host').mkdir(parents=True, exist_ok=True)
    natives, c_funcs, mapped, missing = [], [], 0, []
    shadows = {}
    n = 0
    for cls in CLASSES:
        body = []
        for jret, name, jargs in java_methods(cls):
            params = ', '.join('%s a%d' % (t, k) for k, t in enumerate(jargs))
            key = (name, tuple(jargs))
            sig = '%s %s(%s)' % (jret, name, params)
            if key in HAND_JAVA:
                fn = HAND_JAVA[key]
                natives.append('    static native %s %s(%s);' % (jret, fn, params))
                call = '%s(%s)' % (fn, ', '.join('a%d' % k for k in range(len(jargs))))
                body.append('    @Implementation protected static %s { %sHostGL.%s; }' % (sig, '' if jret == 'void' else 'return ', call))
                mapped += 1
                continue
            p = plan(name, jret, jargs, protos[name]) if name in protos else None
            if p is None:
                missing.append(cls + '.' + name + '(' + ', '.join(jargs) + ')')
                body.append('    @Implementation protected static %s { throw new UnsupportedOperationException("host GL: %s.%s(%s)"); }'
                            % (sig, cls, name, ', '.join(jargs)))
                continue
            jparams, jniret, pro, call, epi, ret = p
            fn = 'm%d' % n
            n += 1
            mapped += 1
            natives.append('    static native %s %s(%s);' % (jret, fn, ', '.join('%s %s' % (jt, a) for _, a, jt in jparams)))
            # Java side: a Buffer is passed as a direct buffer and its byte offset (a heap buffer through a direct copy)
            pre, args, post = [], [], []
            for k, t in enumerate(jargs):
                if t in BUFFERS:
                    pre.append('HostBuf b%d = HostBuf.of(a%d);' % (k, k))
                    args += ['b%d.direct' % k, 'b%d.offset' % k]
                    post.append('b%d.done();' % k)
                else:
                    args.append('a%d' % k)
            invoke = 'HostGL.%s(%s)' % (fn, ', '.join(args))
            if jret == 'void':
                inner = '%s;' % invoke
            else:
                inner = '%s r = %s;' % (jret, invoke)
            text = ' '.join(pre) + (' try { ' if post else ' ') + inner + (' } finally { ' + ' '.join(post) + ' }' if post else '')
            text += '' if jret == 'void' else ' return r;'
            body.append('    @Implementation protected static %s {%s }' % (sig, (' ' + text.strip()) if text.strip() else ''))
            cparams = ''.join(', %s %s' % (jni, a) for jni, a, _ in jparams)
            c_funcs.append('JNIEXPORT %s JNICALL Java_scam_host_HostGL_%s(JNIEnv* env, jclass c%s) {\n    %s\n}\n' % (
                jniret, fn, cparams, '\n    '.join(pro + [call] + epi + ([ret] if ret else []))))
        shadows[cls] = body
    for cls, body in shadows.items():
        (out / ('java/scam/host/Shadow%s.java' % cls)).write_text(
            '// Generated by ios/tools/android-host/gen_host_gl.py - do not edit.\npackage scam.host;\n\n'
            'import org.robolectric.annotation.Implementation;\nimport org.robolectric.annotation.Implements;\n\n'
            '@Implements(android.opengl.%s.class)\npublic class Shadow%s {\n%s\n}\n' % (cls, cls, '\n'.join(body)))
    (out / 'java/scam/host/HostGL.java').write_text(
        '// Generated by ios/tools/android-host/gen_host_gl.py - do not edit.\npackage scam.host;\n\nfinal class HostGL {\n'
        '    static { System.loadLibrary("scamhostgl"); }\n    private HostGL() {}\n%s\n}\n' % '\n'.join(natives))
    (out / 'host_gl.c').write_text(
        '// Generated by ios/tools/android-host/gen_host_gl.py - do not edit.\n#include <jni.h>\n#include <stdint.h>\n#include <stdlib.h>\n'
        '#include <GLES3/gl32.h>\n%s\n%s' % (HAND_C, '\n'.join(c_funcs)))
    (out / 'unmapped.txt').write_text('\n'.join(missing) + '\n')
    print('host GL: %d methods mapped, %d throw (unmapped.txt)' % (mapped, len(missing)))


main()
