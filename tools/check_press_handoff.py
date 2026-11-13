#!/usr/bin/env python3
"""P554: every ImageSaver a shot is saved with gets the press's white balance routes (manual and Quad / Tetra) together."""
import pathlib, re, sys
src = (pathlib.Path(__file__).resolve().parent.parent
       / 'app/src/main/java/com/scamera/app/capture/CaptureController.java').read_text(encoding='utf-8')
fails = []
for m in re.finditer(r'(\w+)\.manualWbAtPress = mShutterManualWb;', src):
    var = m.group(1)
    window = src[max(0, m.start() - 1500):m.end() + 1500]
    if var + '.mosaicWbAtPress = mShutterMosaicWb;' not in window:
        fails.append('%s at offset %d: manualWbAtPress without mosaicWbAtPress' % (var, m.start()))
n = len(re.findall(r'\.manualWbAtPress = mShutterManualWb;', src))
for f in fails:
    print('FAIL ' + f)
print('press hand-off check %s (%d savers)' % ('FAIL' if fails or n < 2 else 'PASS', n))
sys.exit(1 if fails or n < 2 else 0)
