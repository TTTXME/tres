# Guarded Bayer demosaic split

The owner requested enabling the experiment after the initial research review.
This delivery enables `hybrid_bayer_split 1` in the app. `0` selects the original
shader. Historical worker tuning defaults to `bayerSplit 0`, so archived replays
retain their original interpretation.

## Scope and recovery

The split applies to plain Bayer, sensor output grid 1, 2-4 accepted frames,
the compatible coverage mode, nonzero `widenBelow`, base demosaic and chroma
correction, with the base enabled. DCG, native Quad/Tetra, Sabre 2x and larger series
retain their original route. There is no vendor-based GPU exclusion. Physical
Mali validation remains pending; this is an enabled experiment, not a claim of
measured improvement on every driver.

Two lazily compiled shaders perform the existing colour-difference calculation
and base RAW demosaic sequentially in the original FP32 buffer. No additional
image buffer, intermediate FP16 store, frame selection or rejection changes are
introduced. The original shader/workgroups process the last eight sensor rows;
this recomputes real pixels rather than copying neighbouring colours.

Compilation failure keeps the original shader. A failed split-enabled merge
restarts the entire merge using the original shader and unchanged inputs, before
generic recovery can reduce features. Driver crashes are classified as a
same-result step: the app learns to disable this experiment while preserving
GPU processing. Both shader programs are released with the context.

## Validation

- Integrated worker, nine real 12 MP cases, ABBA (36 complete native outputs):
  RGB, effective coverage, clip flags and frame/merge decisions match exactly.
- Eight detail cases: original worker approximately 5.0-5.3 s, split 2.8-3.1 s.
  These are native-worker elapsed times, not isolated shader timings or total
  photo latency. Capture time is excluded.
- The movement case has `widenBelow=0`: the original path is selected, avoiding
  the overhead seen in the unconditional prototype.
- Complete supplied Nubia 22 MP burst, replayed on OPPO: 9467/5867/5781/9390 ms
  in ABBA order. All outputs retain SHA-256
  `200926592829bacb5253d28daaaf76410482014fd51a0351719aa774212d253e`.
  This measures OPPO / Adreno 750 processing Nubia RAW, not Nubia device speed.
- Two-frame real input: original, split, injected compile failure and injected
  dispatch failure all preserve complete output and frame decisions exactly.
  Fault injection is excluded from ordinary builds.
- 65 focused JVM tests pass, including crash classification and recovery.
- Native helper/readback checks and ten required host checks pass. Full suite
  history still includes the previously documented Windows Editor cache cleanup
  failures; no tests or thresholds were disabled or relaxed.

## Independent FP16 downsample compatibility fix

Actions run 41340897228 stopped on the unchanged `shadow colour mean drift`
assertion. The failure was reproduced locally with Mesa llvmpipe; NVIDIA passed.
Explicit half packing/unpacking of downsample outputs avoids accumulation from
driver-dependent RGBA16F store conversion. Only three downsample passes change;
the existing image buffers and filter equations remain.

The unchanged Resolve/pyramid check passes on both NVIDIA and Mesa after this
fix. Nine real complete Android postprocessing cases, each repeated twice per
version, remain byte-identical on OPPO with and without it. No tolerance was
increased and no test was removed. This compatibility fix is separate from the
native Bayer split.

## Evidence boundary

GCam 6.1 latency parity, physical Mali behaviour, and resolution of the reported
Nubia Z17 black-photo / Xiaomi 12X stalls are not established. The owner does not
have these phones available; those checks require external testers of the CI
APK. Existing reports and diagnostic hooks are retained.
