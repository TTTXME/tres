#!/usr/bin/env python3
"""Compile the shader programs exported by check_bayer_tile.cpp."""
from pathlib import Path
import subprocess
import sys
from check_vf_fused import find_glslc

folder = Path(sys.argv[1])
compiler = find_glslc()
if compiler is None:
    raise RuntimeError('glslc is required for Bayer tile validation')
shaders = sorted(folder.glob('bayer-*.comp')) + sorted(folder.glob('native-*.comp')) + sorted(folder.glob('donor-*.comp'))
assert len(shaders) == 12, shaders
for shader in shaders:
    subprocess.run([compiler, '--target-env=opengl', '-fauto-map-locations',
                    '-fauto-bind-uniforms', str(shader), '-o', str(shader.with_suffix('.spv'))], check=True)
print('Twelve actual Bayer/Quad/Tetra and donor-sum shader programs compile')
