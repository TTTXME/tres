#!/usr/bin/env python3
"""P668: the worker leaves without GL calls after the GPU driver's exit handlers.

Usage: check_worker_exit.py <worker built with tools/check_worker_exit_wrap.cpp and -Wl,--wrap=eglInitialize,--wrap=eglMakeCurrent>

A kept-ready worker (the app's standby, «--scam-capture» with a «wait-go» pipe) whose shot never comes - the camera closed, the
standby expired - reads EOF on its go pipe and stops («STOP: SCAM burst was not delivered by the app»). Its EGL warm-up thread had
made the fast route's context meanwhile. On the Xiaomi 12X (Adreno 650, build 31266) every such exit died in eglMakeCurrent under
__cxa_finalize (a tombstone at each camera close). The wrapped build aborts on any eglMakeCurrent after the driver's exit handler
(registered at its first eglInitialize, as a vendor driver's), so both exits are checked: the default one (_exit after the STOP
line) and SCAM_NO_FAST_EXIT's (the static destructors run: the untaken warm-up context must not be torn down there).
"""
import os
import signal
import subprocess
import sys
import tempfile
import time

FAILURES = []


def check(cond, msg):
    print(('ok   ' if cond else 'FAIL ') + msg)
    if not cond:
        FAILURES.append(msg)


def standby(worker, extra_env, label):
    with tempfile.TemporaryDirectory() as job:
        with open(os.path.join(job, 'hybrid_tuning.txt'), 'w') as f:
            f.write('fastMerge 1\n')
        r, w = os.pipe()
        with open(os.path.join(job, 'wait-go'), 'w') as f:
            f.write('%d\n' % r)
        env = dict(os.environ, LIBGL_ALWAYS_SOFTWARE='1', EGL_PLATFORM=os.environ.get('EGL_PLATFORM', 'surfaceless'), **extra_env)
        p = subprocess.Popen([worker, '--scam-capture', job, os.path.join(job, 'burst'), os.path.join(job, 'out')],
                             pass_fds=(r,), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        os.close(r)
        time.sleep(1.5)  # the warm-up thread loads the driver and makes the fast route's context while the worker waits
        os.close(w)      # the app closes the go pipe without a byte: the shot never came
        out, err = p.communicate(timeout=120)
        code = p.returncode
    lines = out + err
    print('     %s: exit %s; %s' % (label, code if code >= 0 else signal.Signals(-code).name,
                                    ' | '.join(l for l in lines.splitlines() if 'STOP' in l or 'EXIT CHECK' in l or 'prewarm' in l.lower())))
    check('STOP: SCAM burst was not delivered by the app' in lines, label + ': the standby stops at the closed go pipe')
    check('EXIT CHECK' not in lines, label + ': no eglMakeCurrent after the driver\'s exit handlers')
    check(code == 1, label + ': exit status 1, no signal (was %s)' % code)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    worker = sys.argv[1]
    standby(worker, {}, 'default exit')
    standby(worker, {'SCAM_NO_FAST_EXIT': '1'}, 'static destructors (SCAM_NO_FAST_EXIT)')
    print('ALL OK' if not FAILURES else 'FAILED: %d' % len(FAILURES))
    return 1 if FAILURES else 0


if __name__ == '__main__':
    sys.exit(main())
