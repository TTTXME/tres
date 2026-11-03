# Report-driven processing work

The owner supplied `sabre_and_gl_pipeline_optimizations_report.md` on 2026-11-02.
The later full-processing request reauthorized reference-APK investigation and
architectural changes. This work does not import the report's performance or
zero-loss claims.
Evidence is in `../evidence/vf-processing-20261102/`.

## Latest measured state and delivery gate

The latest owner gate is processing plus saving at most 500ms slower than
WhyCam, with the same actual accepted frame count, output dimensions and
comparable settings/conditions. Capture time is separate. No push or parity
claim is authorized by the present results. The 30-normal native worker is
still approximately 2.5s; the unmatched reference native snapshot is about
0.8s and has different post/calibration settings.

The combined rim-support certificate and FP32 scene-luma cache now pass
13 complete physical post cases, including 50MP Sabre2x, Moving, active
highlight/window edges, AgX, custom tone, HDR with actual sky and gain map,
and partial-strength film. Every final RGBA and HDR gain-map byte matches;
warm balanced medians improve by 99-201ms at 12MP and 396-488ms at 50MP.
Both are enabled by default, with capability/memory/error fallback and
independent rollback switches. Details: `TONE-CACHE-SUPPORT-20261102.md`.
Resolve wide-zero and
Gaussian uniform weights have mixed timings and remain off. The corrected
half-store fusions preserve the tested final pixels but have only mixed
small-percent gains. Earlier fusion pixel differences below describe the
superseded prototype, not the corrected RTZ path.

The tiny JPEG histogram sample passed 30 real-input configurations, but a
separate heterogeneous adversarial image enlarged the JPEG by 72.1%.
It is rejected despite exact decoded pixels. The replacement retains the
original full-strip DCT tokens, forms histograms from every token and encodes
them once. On one real 12MP image the warm encoder median is 132.54 to 78.99ms;
on the adversarial image it is 176.79 to 176.30ms, size ratio 1.000015.
These are encoder-only tests, not app save measurements. The final conservative
geometry/memory policy, actual current CMake JNI and full coefficient/pixel
matrices now pass; the retained policy is enabled by default.50MP declines
before DCT, avoiding the superseded prototype's late memory penalty. The safe
stats-only shortcut preserves JPEG bytes. See `JPEG-TOKENS-20261102.md`.

The current focused Gradle run passes 197 tests in 33 classes, with no skips,
failures or errors. The template and native JNI candidates build locally;
the owner-facing APK must still come from verified GitHub Actions output.

## Architecture mapping

- SCAMERA accumulates a complete donor loop in local FP32 shader variables. It
  does not repeatedly write the report's two FP32 blend targets. Donor covariance
  is already half-packed, and stores a precision matrix with signed cross terms
  and values above one; a direct RGB10_A2 replacement is invalid.
- Rejection/dilation use SSBOs and shared tiles, not the report's pair of R8 FBOs.
  RAW upload capacity and flow texture storage are already reused. Ordinary
  production passes do not enable the diagnostic glFinish profiler.
- Actual remaining post candidates are immutable FBO attachment reuse,
  optics-to-Oklab fusion and uniform-derived AgX coefficient preparation. They
  have independent rollback gates and are under device A/B evaluation.
- The report's grayscale Ultra HDR/JPEG path requires container/encoder/readback
  changes. It cannot accelerate SDR or explain the full 30-frame merge gap.

## Measured research decisions

Rootless OPPO PHY110 / Adreno 750. Worker experiments retain the same 30 normal
4096x3072 RAWs, tuning and dimensions. Capture time is excluded. These are
worker-through-output measurements, not complete saved-JPEG latency.

| Candidate | Control / candidate, ms | Decision |
| --- | --- | --- |
| Streaming rejection arrays | 2474/2504 vs 2741/2828 | Slower; research only. |
| Render accumulation, ordered/static FBO, FP32 | Compute median 2494; old render 2684; new 2601 | Still slower than compute; research only. Same-precision old/new complete output exact. |
| Same render, FP16 targets | Old 2557.5; new 2622.5 | No gain; research only. FP16 versus FP32 is a separate numerical change. |
| Mediump exponent/weight, FP32 quadratic and accumulators | 2530/2460 vs 2742/2580 | No gain; research only. All 30 frames retained. |
| Variance fused into donor Cells, no extra SSBO/pass | 2455/2667 vs 2773/2938 | Slower; research only. Fallback/invalid gate reproduce the complete control exactly. |
| Single-donor packed RAW word tile | 2528/2433 vs 24968/24730 | All complete outputs exact, approximately ten times slower; rejected. |
| One output pixel per invocation, no shared memory or barriers | 2524/2494 vs 2630/2592 | Complete outputs exact, slower; rejected. |
| Explicit IEEE-RNE FP16 donor Cells storage | Median 2655 vs 2800.5 | Codec passes on Adreno; complete processing is 5.5% slower; rejected. |
| Certified early-zero rejection branch | 2477 vs 2660 (one measured pair) | Full-output exact gate failed; no speed gain. Original strict test retained, research only. |

The Cells-variance result changes 3419 RGB half components, maximum 0.00048828125,
and 85 trailer bytes. Rejection is for observed speed, not a claim that a changed
hash establishes worse photo quality. A diagnostic pass-cost investigation is
separate from uninstrumented throughput.

The report's fast cube-root variants were tested with 1, 24 and 48 distinct
evaluations per input. Accurate Halley/refined variants are slower than pow in
the ALU-heavy tests (approximately 1.27-1.86 times the control wall duration).
The report's sixfold improvement is not reproduced. Shader math remains unchanged.
EXT elapsed-query values are not validated here and do not rank candidates.

The Android half-codec test found 217617 differences from its strict IEEE-RNE
contract in native packHalf2x16: normal values truncate, half subnormals flush,
and overflow saturates. This includes ordinary positive Cells values. The
separate explicit integer codec passes the same unchanged exhaustive verifier.
Its 30-frame result changes 194984 half components (maximum 0.259765625 at a
highlight), 29837 effective-map bytes and 10 clip bytes. Reviewed identical-gain
detail/highlight crops show small local differences; no general lossless claim
is made. See `half-cells/codec-device-failure/DIAGNOSIS.md` and the RNE replay.

Early-zero research changes 958 RGB components, maximum 0.00048828125, and 11
trailer bytes on this Adreno despite exact desktop fixtures. The complete-output
gate stopped as designed. These results do not identify which compiler/math
operation caused the differences; the candidate is not enabled.

## Postprocessing quality boundary

Optics-to-Lab fusion was actually exercised on four complete real post inputs:
three 30-normal detail/night scenes and the eight-normal Moving burst. The first
complete final RGBA is exact. Other cases change 537, 2751 and 18533 RGB components
out of 37748736, maximum 3-6 byte levels; alpha is exact. Inspected detail/corner/
motion crops show no visible degradation. Timing improvements are mixed and
require balanced repeats before default acceptance.

AgX scalar preparation was exercised with AgX explicitly selected on three
complete real inputs. Differences are at most one byte level in 45, 163 and 425
RGB components; alpha is unchanged. Crop review shows no visible loss. Initial
whole-post timings are mixed; no established general acceleration is claimed.
Static-FBO rollback tests retain identical complete outputs and are still under
balanced timing evaluation. Because balanced measurements do not establish a
general speed improvement, static FBO reuse and AgX precomputation are now off
by default. Optics-to-Lab fusion also remains off. Retaining a research switch
does not count as an accepted acceleration.

Further local candidates are FP32 shared donor-Cells reads in pooled rejection,
cached neighbour Oklab for optical cleanup, and fused detail/reconstruction in
the multiscale pyramid. Each retains an independent reference route and needs
complete phone-output and throughput checks before default acceptance.

All current focused JVM tests pass: 174 tests in 26 classes; source/template
assembly passes. These are local QA builds, not release artifacts. Physical Mali,
X200 Ultra RAW preview FPS, and unavailable older devices remain separate checks.

## Noise-profile boundary

On the preserved ISO100 Nubia burst, independent temporal ES estimates disagree
with both HAL slope and offset. Dividing only offset by white or white squared
does not reproduce the measured model. The newer log has no matching RAW and
cannot establish a universal calibration. No heuristic offset normalization is
enabled; see `report-hal-noise/` for input identities, measurements and limits.

## Delivery gate

The full-size 30-frame processing target remains open. No commit or push is
authorized by these research results. The latest gate above and real-burst
quality checks, including Bayer, Quad and Tetra, apply. Further reference-APK
investigation is permitted by the latest owner instruction.
