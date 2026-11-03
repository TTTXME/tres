#!/usr/bin/env python3
"""Check the app's slide DIR packets against the pinned native profile data."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
java = (root / 'app/src/main/java/com/particlesdevs/photoncamera/processing/color/FilmReferenceParameters.java').read_text(encoding='utf-8')
native = (root / 'app/src/main/cpp/third_party/spektrafilm/SpektraGeneratedProfileCurves.cpp').read_text(encoding='utf-8')
packet = re.search(r'SLIDE_DIR=\{(.*?)\};', java, re.S).group(1)
rows = [[float(x.rstrip('f')) for x in row.split(',')] for row in re.findall(r'\{([^{}]+)\}', packet)]
stocks = ['kodak_ektachrome_100', 'kodak_kodachrome_64', 'fujifilm_velvia_100', 'fujifilm_provia_100f']
assert len(rows) == len(stocks)
for index, stock in enumerate(stocks, 16):
    expected = []
    for field in ['same_layer_rgb', 'r_to_gb', 'g_to_rb', 'b_to_rg']:
        name = f'film_{index}_{stock}_dir_gamma_{field}'
        values = re.search(r'float ' + name + r'\[\] = \{(.*?)\};', native, re.S).group(1)
        expected.extend(float(x.rstrip('f')) for x in values.replace(',', ' ').split())
    assert rows[index - 16] == expected, stock
assert 'p.real(220+4*i,SLIDE_DIR[film-16][i])' in java
print('Four slide DIR profiles match all 36 native coefficients')
