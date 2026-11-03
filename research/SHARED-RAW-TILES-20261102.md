# Shared RAW tiles and the Sabre 2x demosaic

Local P462 delivery, not yet published. The reference remains `origin/main`
5f990c7d / APK31204. All measurements below are worker processing on the same
OPPO PHY110 / Adreno 750; capture and Android postprocessing are excluded.

## Accepted changes

The Bayer base demosaic previously allocated an 81-float RAW neighbourhood per
invocation. An 8x8 workgroup now loads a shared 16x16 tile of the original FP32
values and their invalid/clip flags. The gradient, green interpolation, colour
differences, damping and final mixing formulas retain their evaluation order.
Every invocation loads the tile and reaches the barrier before any pixel returns.

The native Quad/Tetra base demosaic similarly shares a 20x20 tile of normalized
native sites and flags. The existing site gain field, black levels, clipping,
reflection and cell-phase calculation remain in `natSite`. Native grids below
the colour-block size retain the original shader.

The existing split Bayer pass also applies to the Sabre 2x grid for its original
eligible 2-4-frame cases. Its last eight sensor rows still use the original pass.
There is no new FP16 arithmetic, frame culling, output resize or filter reduction.

For four normal frames on the eligible Sabre 2x route, the main merge also keeps
five FP32 donor sums per strip pixel. The colour pass reuses those sums and
evaluates the base with its original weighting. Keeping the pre-base sums is
essential: the main and colour passes use different base dezipper conventions.
Only sparse-coverage pixels need stores. The cache requires SSBO binding 21,
fits both the storage-block limit and one eighth of available memory, and is
released before an original-path retry. `SCAM_NO_DONOR_SUMS` restores the same
split/tiled route without this cache.

`hybrid_bayer_split 0` restores the reference routes. Historical worker tuning
defaults remain unchanged. Compile failures retain the original shaders; a
runtime error retries the original merge with the same inputs. The retry resets
CA bands, streamed row consumers and effective/clip trailer counters, including
when earlier strips have already been consumed. Driver-crash classification
disables this optimization without disabling the whole GPU merge.

## Matched-input measurements

| Case | Reference | Shared/split | Full output |
| --- | ---: | ---: | --- |
| Actual detail, four N, 4096x3072 | 2.8-3.1 s | 0.95-1.13 s | Exact |
| Actual detail, two N, 4096x3072 | 2.77-2.82 s | 0.91-0.92 s | Exact |
| Actual detail, four N, 8192x6144 | 17.7-18.6 s | 2.85-3.10 s | Exact |
| Same four-N 50MP cases, donor reuse | 2.8-3.1 s | 2.6-2.9 s | Exact |
| Low-coverage Tetra fixture | 4.35-4.56 s | 2.16-2.23 s | Exact |
| Single-frame Tetra fixture | 3.48-3.63 s | 1.20-1.27 s | Exact |

The low-coverage Tetra rows are fixtures, not real phone speed measurements on
a Tetra-equipped camera. Actual static native multi-frame bursts, larger plain
series and Moving often have little improvement: they rarely enter this base
demosaic. Do not apply the short-series speed ratio to every shot.

## Validation and evidence

Evidence is in `../evidence/why220-after-delivery-20261102/`:

- `production-tile-nine-case.json`: 36 complete outputs, eight actual detail
  scenes and Moving, with unchanged frame decisions.
- `tile-extended.json`: 24 outputs covering 2/8/30 N and actual detail/Moving
  Sabre 2x. Complete RGB, effective-map and clip-flag hashes match.
- `wide-tile.json` and `wide-tile-extended.json`: Sabre 2x ABBA plus seven other
  actual detail scenes and 2/3-frame cases; complete outputs match.
- `integrated-native-tile-matrix.json`: all 18 existing native oracles match,
  including actual main, UW, tele, ISZ and 50MP inputs; `native-tile-abba.json`
  records warm/cold ordering rather than inferring speed from one run.
- `tile-faults.json` and `tile-faults-grid2.json`: Bayer/Tetra and Sabre 2x compile,
  dispatch and late-strip failures return the original full output exactly.
- `production-final-smoke.json`: the production build without fault injection
  matches the reference, including all bytes of its complete output.
- `donor-final-matrix.json`: the latest production build without fault macros
  reproduces all nine complete Sabre 2x reference files.
- `donor-integrated-matrix.json`: 36 full integrated-worker outputs on eight
  detail scenes and Moving, all exact, with identical frame decisions. Moving
  retains its original route. The earlier scalar-cache matrix adds 72 complete
  exact outputs; the integrated matrix verifies production routing separately.
- `donor-faults.json`: compile, dispatch and late-strip donor-cache failures
  retain the complete original 50MP result. Compile failure retains the existing
  split/tile acceleration; dispatch failure conservatively retries the original.
- Host geometry covers 349920 Bayer and 2574720 native site mappings. Twelve
  actual generated shader variants compile with glslc. The GPU fixture covers
  96 combinations of CFA phases, flags, scale, strip offsets and boundaries,
  with 3477760 components and zero changes. Eight full CFA/motion/clipping GPU fixtures
  additionally compare FP32 donor-cache results and trailers exactly.

The local eight requested colour/settings/shader checks pass. The full JVM run
has 2444 tests and five EditorViewModelTest failures on Windows; changed packet
and crash-recovery tests pass. No assertions or test selection were weakened.

## Remaining boundary

Physical Mali validation, the Nubia black-photo report, Xiaomi 12X timing and
general end-to-end reference-app speed parity are not established. A rootless
reference JNI replay processes the same four RAWs in about 330-341 ms, but uses
different post settings and no supplied lens-shading map; it is a stage-cost
comparison, not proof of equal algorithms or final image quality.

Several further candidates remain research-only. GPU chroma transfer,
on-demand Bayer/Tetra arrays, fixed phase constants, KLT/coarse-search variants
and Resolve specialization must not be treated as delivered improvements.
Resolve specialization repeats the same full Android output on all nine scenes
but is generally slower and has been removed from production source.
Further transfer/CPU-queue experiments and current profiling limits are recorded
in PROCESSING-LIMITS-20261102.md; none is silently enabled in production.
