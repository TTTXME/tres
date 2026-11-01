# GCam 6.1 speed investigation, round 2

Worktree `wt-gcam61-speed-20261101`, base `e8f33175`. Local evidence: `SCAMERA-PC/evidence/gcam61-speed-round2-20261101`. The other AI's `project/` is untouched. No proprietary APK/library, RAW, credential or version.properties change belongs to this delivery.

## Accepted candidate

The plain Bayer ultrashort check repeated the row/local measurements on an unchanged correction copy. When the check changed an ultrashort RAW or refused one, the speculative merge then repeated normal-frame homographies and F6 fields too.

- Reuse row measurements made on exactly the same RAW bytes before the copy. Reuse a local field only when no row correction was applied; after a row correction it is still measured on the corrected bytes. Burst coherence, daylight decisions, correction, gain validation and refusal policy remain the same.
- During that one restart, keep successful homography futures and completed CPU-owned F6 results for unchanged normal RAWs. Validate the base pointer, geometry, black/white, base noise and all local-alignment parameters; per-frame RAW, gain, frame index, homography and field mode must match. Changed ultrashort frames are estimated again.
- The old GPU worker is destroyed normally. No EGL context survives across the restart: a fresh worker imports completed fields before its first add/estimate. Changed jobs invalidate those fields; failures retain existing fallback paths.
- `SCAM_NO_US_MEASURE_REUSE=1` and `SCAM_NO_US_ALIGN_REUSE=1` restore the two old paths for comparison. No user-facing no-op setting is added.

No GLSL body, protected `kHybMergeMain1`, frame/exposure policy, RAW size, rejection equation or sharpening setting is changed. This is reuse of existing results, not a claim that Google's alignment was copied bit-for-bit.

## Real-burst quality proof

All eight full-size series in `D:/burst/Штрихи`, plus Moving `172902`, were replayed on OPPO PHY110 / Android16 / Adreno750. The original NCH v12 active frames were compacted without cropping or changing their order; every copied frame was compared byte-for-byte to its original. Original per-shot tuning was extracted from each archive's `native-report.txt`, including highlight/shadow options and scene EV.

`final-burst-checks.json`: old source, new source with both reuse paths disabled, and enabled. All nine cases retain identical complete 100663296-byte half-RGB/effective-count/clip-flag outputs. Every repeat in `warm-ab.json` matches its case's hash too. This proves equality for these replays, not a universal cross-driver equivalence theorem or improved detail.

## Warm alternating timing

Three runs per side after a warm-up, original source versus candidate, unchanged input/tuning, program cache hits verified. Worker completion only: excludes capture, app post-processing and JPEG save.

| Burst | Old median ms | New median ms | Change |
|---|---:|---:|---:|
| 20261101-062956, 36 frames | 4841 | 3554 | -26.6% |
| 20261101-063029, 36 frames | 4736 | 3541 | -25.2% |
| 20261031-233810, 36 input / 35 merged | 3755 | 3523 | -6.2% |
| 20261101-013638, no active ultrashort correction | 1969 | 2031 | +3.1% |
| Moving172902, 8 frames | 1012 | 1005 | -0.7% |

The last two are negative controls where the reuse does not apply: ranges overlap (1949–2104/1960–2119 ms and1008–1019/948–1034 ms). Three samples cannot establish a small speed gain or statistical non-regression there. One cold new-scene run was much slower because of shader compilation and is excluded from this warm table, not hidden in the raw receipts.

GCam's decoded 30-frame MergeShot1065ms/Finish295ms remains faster. Its five-second capture, all-normal exposure sequence and stage accounting differ; these worker replays must not be presented as equal tap-to-JPEG timings.

## Other candidates tested and left out

- Fused LK dispatches, register/shared base-window caches and KLT textureGather: full output matches the original on lamp, Moving8 and full window36, but no dependable useful speed win; caches can be slower.
- Recovered GCam L1-style SAD search plus two fine LK iterations, convergence stopping, and32RAW-pixel field spacing: tested on real full bursts, numerical differences and fixed-tone green-channel crops saved in `burst-matrix/`. Changed pixels or worse synthetic shift error alone are not labelled a proven photographic degradation. None is enabled: their gain does not justify changing alignment/quality in this delivery. Inner Google L2/DFT kernels are not fully recovered.
- Earlier cell-buffer texture/packing/variance experiments remain local evidence. Texture-buffer non-profile output mismatch and cell-image GL1282 exclude those prototypes.

## Checks and delivery boundary

New `check_la_gpu_reuse.cpp` exercises complete-field import/export, stale RAW invalidation, malformed/out-of-range/duplicate/active/closed-worker refusals on the real GPU. `check_us_measure_reuse.cpp` compares corrected RAWs, decisions and reports on unchanged, row-flickering and local-flickering scenes; an intentionally stale pre-row local field must be ignored. Native device checks pass.

NDK AddressSanitizer cannot run on this phone: even the independent hello-world exits SIGILL before main. This is not a sanitizer pass. Linux ASan/UBSan and GPU TSan are added to CI and remain delivery gates, together with existing checks. Protected merge-source/GL compilation and diagnostic-decoder checks pass locally. Final Actions APK, checksum, signature, installed-byte verification and Photo smoke receipts are recorded outside Git after delivery.

A local test APK replacing only the two worker entries saved three decoded4096x3072 JPEGs in the actual app. This live scene did not invoke reuse; cold shader preparation and different thermal states confound timings, so it is a smoke test only. Official31196 was restored and its installed base.apk SHA verified. Developer settings remain exactly `ettr 1\n`. Existing GPU-error/memory/fallback and pending-check-unwind native checks pass on the phone too.
