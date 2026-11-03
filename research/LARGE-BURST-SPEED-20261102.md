# Large-burst processing latency

The owner's target is approaching the reference application's processing speed,
without reducing frame counts, resolution or quality. The accepted 2-4-frame
optimizations do not complete that target. P462 remains open; no delivery or push
is implied by this report.

## Repeated 30-frame baseline

Rootless OPPO PHY110, Adreno 750; same 30 normal 4096x3072 RAW planes from
the owner's 20261031-231613 detail burst, original black/white/noise metadata.
Capture time is excluded. Both engines retain all 30 frames. Original bursts
remain unchanged. Research files are in
`../evidence/why220-after-delivery-20261102/large-series/`.

| Measurement | Repeats | Time |
| --- | --- | --- |
| Current production-source SCAMERA worker | 3 | 2613 / 2518 / 2556 ms |
| Reference native snapshot and processing | 2 | 845.90 / 809.44 ms |
| Reference reported GPU pipeline | 2 | 819 / 782 ms |
| Reference reported merge including its frame preparation | 2 | 639 / 644 ms |
| Reference reported post chain | 2 | 84 / 67 ms |

All three SCAMERA complete files hash to
`89b26f8a0d693d4ded64f836a1b1036fcf43d21e843843c174b621c520cea38e`.
The reference logs explicitly report 30 selected, none dropped.

This is not an equal-quality end-to-end benchmark: SCAMERA worker timing excludes
the application's post chain and JPEG; reference snapshot/process excludes image
encoding, uses different post settings and lacks the supplied lens-shading map.
The reference's optional iterative sharpening is disabled in this harness.
Do not report its native duration as the final saved-JPEG latency or imply matching
final images. The test establishes that short-burst gains cannot close the measured
large-burst processing gap.

SCAMERA's uninstrumented stage logs show approximately 0.66-0.69 s for alignment,
0.05 s additional local-alignment wait and 1.76-1.79 s for reconstruction, including
overlapped CA/chroma. These stages are not directly equivalent to reference labels.

One explicit GPU-pass profiling replay preserves the complete output. It reports
guide 217, donor cells 463, rejection 875, dilation 152, merge 709, chroma 80 and
rim 29 ms. Profiling inserts glFinish and raises total worker time to 4238 ms;
these synchronized pass durations must not be summed into an uninstrumented
latency estimate. They identify substantial work outside accumulation.

## Research outcomes, not production changes

- Incremental FP32 SSBO accumulation in donor batches 1/2/4: 27.8-30.4 s,
  slower than the control. Output differs and requires separate numerical
  diagnosis; not retained. The runner was stopped before remaining variants.
- Fixed-function FP32 render accumulation with the same per-frame sample formula:
  about 2.76 s versus 2.53-2.66 s control. Maximum half-output error 0.00048828125;
  mean 7.43e-9, 16325 components and 126 trailer bytes differ. No quality regression
  is inferred solely from the hash; rejected for performance.
- The same render route with FP16 targets: 2.73-2.82 s. Wider 512-cell strips,
  FP16 or FP32: 2.95-3.01 s. No acceleration; not enabled.
- Constant normal roles/frame count/merge mode: 2.39-2.64 s versus 2.48-2.54 s
  control, changed output; one warm sample does not establish a repeatable gain.
- Nine-site enumeration of the same non-boundary Sabre window: 2.49-2.82 s
  versus 2.44-2.81 s control. No repeatable gain; not enabled. The green-phase
  summation order differs, so this is not claimed as bit-identical arithmetic.
- FP32 cached donor variance for the pooled rejection test: all complete outputs
  exact, but 2.88-2.97 s versus 2.52 s control. Extra pass/storage costs outweigh
  reuse on this device; rejected.
- Cross-donor FP32 base second-difference cache: 2.68-2.98 s versus 2.65-2.70 s,
  changed output. No demonstrated gain; not enabled.

Research source, NDK build recipes, complete outputs and logs are preserved outside
Git. None of these candidates changes the production source or frame count.

## Completion gate

Latest owner clarification: processing plus saving may be at most 500ms
slower than WhyCam at the same actual accepted frame count and output
dimensions, with comparable modes/settings/conditions. Equal or faster is
preferred. Current native-only comparisons do not meet that delivery gate.

Subsequent outside-production architecture checks retain the same 30 normals:
prefiltered guide/rejection is neutral at 2496.5 versus 2488ms and changes the
result substantially; packed RAW Cells atlas fails the unchanged moving/strip
fixture and is about 21s versus 2.5s. Neither is integrated. A shared-context
immutable RAW cache passes all 30 complete RAW buffers and overlapping marked
strip ownership, including producer/consumer failure cleanup. That validates
transport/lifetime only; full worker performance is not yet established.

Measure the complete app processing path with matched post settings and record
alignment/merge, transfer, denoise, tone, sharpening and encoding separately.
Judge large-burst architectural changes by repeated whole-path timing and actual
detail/motion/highlight crops, not by a short-burst result or kernel-only timing.
Validate Bayer, Quad and Tetra independently. Physical Mali and the unavailable
Nubia/Xiaomi devices remain separate validation gaps.

## Complete processing and JPEG control, subsequent research

The controlled Android instrumentation now uses the actual installed application,
its original lens shading/WB/CCM, 30 unchanged RAW planes, 4096x3072 output and
JPEG98/4:4:4. Film, custom LUT and Ultra HDR are off for this comparison. The
reference uses its authentic 1024-sample interpolation of the owner's 17-point
curve through its real native setter. Without the setter, the verified native
consumer falls back to an identity curve; that older reference result is not
the matched tonal control.

The SCAMERA fixture now adopts an existing ShotArena memfd, as capture does,
and forwards the original burst transport with lab stage exports disabled.
ScamLabBurst's diagnostic stage dumps otherwise add readback/export work that
ordinary capture does not perform. This harness correction is not a production
optimization. All three resulting SCAMERA JPEGs match the prior diagnostic
replay byte for byte, including every decoded RGB pixel.

| Complete capture-ready processing plus closed JPEG | Repeats, ms |
| --- | --- |
| Current accepted local SCAMERA QA | 4210.59 / 3749.51 / 3605.83 |
| Reference with the explicit authentic curve | 1042.42 / 1024.73 / 1026.10 |

Receipts are under
`D:/burst/scamera-research/why-complete30-harness-v4-20261102/packaged-runs/20261102-211409-25151804/`
and the corresponding V5 folder at `20261102-212559-f1bfeaf0`.
These are separate repeated sessions, not a balanced paired latency acceptance.
Actual reference logs confirm all30 retained, LSC17x13, nonzero RL/denoise/tone
work and full-size JPEG saving. Analogous named settings do not make different
merge/NR/tone implementations identical. Fixed unnormalized native-resolution
detail, dark-area and lamp crops, exact input/output hashes and curve activation
checks are at `D:/burst/scamera-research/complete30-curve-quality-20261102/`.
Residual tonal/NR differences remain; no equal-quality speed-parity claim is made.

For the last two SCAMERA repeats, merge plus client transport averages2887.5ms,
post726.2ms and JPEG64.0ms. Worker merge intervals are1796/1899ms and alignment
696/631ms. These intervals include overlap/tails and must not be added to a
separately synchronized pass profile. Existing capture prespawn is not used by
this fixture; measuring it faithfully is a harness lifecycle follow-up, not a
new production optimization. The +500ms delivery gate remains unmet.

A cooperative global KLT prototype preserves complete tracked/status/guard
buffers in40 physical fixtures and complete real output/alignment/frame plans,
including compile, seed-copy, run and readback failure recovery. Its final
early-break variant is slower: detail30 median2521.5 to2636.0ms; Moving8 median
981.0 to992.5ms. It remains outside production. The physical receipt is
`D:/burst/scamera-research/global-klt-cooperative-earlybreak-20261102/whole-runs/20261102-212208-882cc028/matrix.json`.

The independent KLT timer-load control also rejects asynchronous timestamps
and elapsed queries as stage measurements on this Adreno driver. Repeating the
same real KLT dispatch1/8/32 times changes CPU completion waits substantially,
while asynchronous timestamps stay near0.0025ms and elapsed queries remain
microseconds even when completion-bracketed timestamps grow to milliseconds.
All seven complete outputs are exact. No conversion factor or asynchronous
stage ranking is accepted. See
`D:/burst/scamera-research/klt-timer-audit-20261102/runs/20261102-212818-10f6ee58/receipt.json`.

## Compact integer RAW quads, production policy and remaining validation

The corrected workgroup-Z sampling reference and a four-fetch RGBA16UI quad
consumer both pass the unchanged113-point physical interval/oracle gate,
including nonzero local alignment. The original RAW words and flag bits are
written alongside donor Cells without a CPU repack; covariance initialization
must also be bound to this producer. The first prototype omitted that binding
and was never accepted for a whole-worker comparison. V2 fixes it explicitly.

V2 balanced detail30 worker medians are2537 to2145ms, a15.45% decrease with
all30 inputs, frame decisions and alignment retained. Moving8 is1008.5 to999ms,
which is effectively neutral. Peak quad storage is78.15MiB and20.58MiB;
allocation/size/GL failures retain the reference path. No frame-count cutoff
is justified by one slow warm-up sample. See
`D:/burst/scamera-research/qz-nine-quads-v2-20261102/whole-runs/20261102-223929-77e1dd0c/matrix.json`.

Each output's own effective-frame map, flags, noise/median log and original
capture calibration were then replayed through the actual Android post chain,
twice per variant. Compared with the corrected sampling reference, the complete
12MP final RGBA changes by at most2 byte levels in both scenes, with no component
above4, alpha unchanged and repeated outputs exact. Mean byte differences
are0.002439(detail) and0.000721(Moving). The input to this comparison is the
corrected sampling reference, not the known failing marked-site loop. These initial
checks were followed by the production policy and failure tests below; native
Quad/Tetra output stays on the existing route. Final-post receipt:
`D:/burst/scamera-research/qz-quads-final-post-20261102/runs/20261102-224604-48d29795/receipt.json`.

The fixed-nine scalar RAW consumer passes the same113-point semantic gate but
is slower on complete bursts (+1.6% detail, +7.1% Moving); the R16UI stack is
also slower (+1.57% and+3.96%). Neither is retained. A shared-origin cache fails
20 captured semantic points despite exact host arithmetic; removing the cache
passes all89 base points, so workgroup-local q is not itself the failure.

The owner prefers the new signed-guide rejection on fingers. This is positive
quality feedback, not a reason to ignore cloth or small-object regressions.
High-pass/gradient reductions alone also measure reduced noise and do not prove
lost detail. The unconditional point guard is slower (+4.8% detail, +3% Moving)
and remains outside production. The adaptive fast/full-rejection worklist
selects 100% full work in both real scenes and is slower; it is rejected.


## Production integration and rejected controls, 3 November

The direct-literal V3 quad path is now integrated, default on only for ordinary
Bayer up to13MP with5..128 normal frames, grid1, no DCG and no donor sums.
Mixed-role windows,4N, native Quad/Tetra and unsupported geometry retain their
existing paths. The corrected sampling remains available when only the optional
quad cache is disabled. Each optional texture is capped at128MiB; transient old
plus new storage must fit one eighth of MemAvailable. Unknown/low memory or
GL allocation/compile/init failure releases optional resources and falls back.
The V2 production policy receipt is
`D:/burst/scamera-research/bayer-quads-production-v2-20261102/policy-phone/20261102-235240-b8110ea5/receipt.json`.
All8 whole-worker regression cases preserve frame/alignment decisions; mixed
window111118/111305 and night24 are unchanged. Three injected refusals match the
corrected uncached reference exactly. The second balanced detail30 run measures
2495 to2256.5ms (-9.56%); Moving8 is neutral. Across these two sessions the
30-normal-frame worker gain is9.6..15.5%, not a whole-app speed-parity claim.

The actual integrated source expands all8 variants exactly like the physically
checked V3;14 actual ESSL programs compile with glslc. The complete production
worker compiles with the Android NDK and has identical allocated ELF sections
to the policy-tested build. This does not replace a complete current-APK
processing-plus-save comparison. The last measured complete delivery gate above
remains unmet, so no commit or push is authorized by those isolated gains.

Two more alignment controls are not adopted: coarse-seeded matching is neutral
(detail2157.5 to2126ms), and selective quarter-only refinement is slower
(detail2086.5 to2174ms, Moving944.5 to970.5ms) with changed alignment/output.
The centre-only zero-texture rejection shortcut preserves complete bytes but
is neutral (detail2503.5 to2528.5ms, Moving1038 to1019.5ms). Eliding donor variance
fails the original exact oracle. A separate explicitly bounded experiment also
fails its unchanged hard-remap/guard gate; neither is accepted by relaxing tests.

The owner's parallel portrait changes from origin/main93a1b37d are preserved by
a three-way integration with raw before/base/theirs receipts. The workflow keeps
both test sets. Portrait GPU/CPU/scene oracles pass; source review finds no
semantic conflict with captured film settings, the asynchronous preview LUT or
nonblocking subject PBO. New on-device portrait capture remains a separate check.
