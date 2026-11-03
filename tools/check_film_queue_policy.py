import argparse
import ctypes
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--ndk', type=Path)
args = parser.parse_args()
source = ROOT / 'tools/check_film_queue_policy.cpp'
with tempfile.TemporaryDirectory(prefix='scamera-film-queue-') as folder:
    output = Path(folder)
    if os.name == 'nt':
        if not args.ndk:
            parser.error('--ndk is required on Windows')
        llvm = args.ndk / 'toolchains/llvm/prebuilt/windows-x86_64'
        obj = output / 'queue.obj'
        binary = output / 'queue.dll'
        subprocess.run([str(llvm / 'bin/clang++.exe'), '--target=x86_64-pc-windows-msvc',
                        '-std=c++17', '-O2', '-ffreestanding', '-fno-stack-protector',
                        '-fno-exceptions', '-fno-rtti', '-nostdlib', '-Wall', '-Wextra', '-Werror',
                        '-idirafter', str(llvm / 'sysroot/usr/include'), '-c', str(source), '-o', str(obj)], check=True)
        subprocess.run([str(llvm / 'bin/ld.lld.exe'), '-flavor', 'link', '/dll', '/noentry',
                        '/out:' + str(binary), str(obj)], check=True)
        native = ctypes.CDLL(str(binary))
        try:
            native.filmQueueCase.argtypes = [ctypes.c_int]
            native.filmQueueCase.restype = ctypes.c_int
            for case in range(24):
                line = native.filmQueueCase(case)
                if line:
                    raise AssertionError(f'film queue case {case} failed at line {line}')
        finally:
            ctypes.windll.kernel32.FreeLibrary.argtypes = [ctypes.c_void_p]
            ctypes.windll.kernel32.FreeLibrary.restype = ctypes.c_int
            ctypes.windll.kernel32.FreeLibrary(native._handle)
    else:
        cxx = shutil.which('c++') or shutil.which('clang++')
        if not cxx:
            parser.error('A C++ compiler is required')
        binary = output / 'queue'
        subprocess.run([cxx, '-std=c++17', '-O2', '-Wall', '-Wextra', '-Werror',
                        str(source), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
print('PASS 24 native film queue selection/fallback/cache cases')
