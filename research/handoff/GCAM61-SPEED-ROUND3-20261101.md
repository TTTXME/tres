# Exact Bayer, Quad and Tetra speed work, 2026-11-01

Base: main b01602a1. Local evidence: `../evidence/gcam61-speed-round3-20261101/` from the checkout's parent. Work is isolated from the owner's `project/` and other working trees. No Google or vendor binary is part of this change.

## Native Quad and Tetra

The native fast merge skips a 2x2 colour block only when conservative lower bounds on its quadratic kernel distance prove that all four original weights are below the existing 0.002 cutoff. It does not narrow the kernel, reduce the window, omit contributing samples, change accumulation order or change base/ultrashort handling. The Gershgorin bound and two completed-square bounds cover the whole continuous block. The positive-definite guard and downward margins avoid cancellation near singular matrices; exp2(-10) + the allowed floor remains below 0.002. `SCAM_NO_NAT_CULL=1` retains the original shader route.

The production GLSL branch and its original four tap weights are checked independently on the GPU for 1,048,576 rotated, anisotropic, indefinite and out-of-range-floor cases: 553,870 blocks culled, zero contributing taps lost on Adreno750. CI runs the same check under Mesa with ASan/UBSan on the harness.

## Rejected alignment cache

An exact reference-patch/Hessian cache was tested on all 30 cases, including its additional 1,511,424-byte memory guard and GPU failures. Its Bayer worker median initially improved 3513 to 3438ms, then changed 3759 to 3777ms in the final balanced run. Moving improved 1093 to 1059ms, but this does not establish a dependable general gain. The prototype is archived locally and excluded from delivery. The existing P443 alignment reuse and original tracker remain unchanged.

## Validation

- 51 experimental worker runs: eight latest full-resolution Штрихи series, Moving8, paired Quad/Tetra synthetic, moving, bright/dark, one/eight-frame, main/ultrawide/tele FULLRES, ISZ, 50MP Quad Sabre2x and native Tetra cases. Full half-RGB, effective map and clip-flag output SHA-256 match the original. All 30 old/new frame-role, weight, merge-factor, dropped-frame and Bento decisions match.
- The combined prototype's default-enabled worker was independently replayed on the same 30 cases, full outputs and frame decisions exact. The final culling-only source retains the already validated native threshold-10 branch; its final merged-source validation is recorded separately. Original per-shot tuning is used for the recent detail/motion bursts; explicit parameter sweeps are separate cases.
- Actual Adreno fault, promise/slot, field reuse, memory guard, native widening and FULLRES2x checks pass with the cache enabled; error/reuse checks also pass with the original KLT route.
- `kHybMergeMain1` is unchanged, SHA-256 `707695005ec6fc4d7753a7791a6eb2e8377069d48ddfa2193f114da6ed2a41a2`. Tone mapping, sharpening, capture policy, frame budgets and resolution are unchanged.

Balanced culling-only receipt: `native-threshold-ab.json`, one warmup excluded and five alternating samples per variant: Quad50MP Sabre2x 5020 to4418ms; Tetra 9204 to7529ms, identical complete output hashes. The combined rejected-cache receipt is `final-ab.json`: Quad5073 to4512ms, Tetra9175 to7611ms. Timings are worker replay time through output writing, excluding Camera2 capture, Android post-processing and JPEG save. Do not report them as shutter-to-file or as GCam parity.

## GCam61 evidence and rejected work

The owner's installed original GCam61 library was loaded through its own installed APK classes in a shell-only diagnostic, without root, package replacement, camera access or changes to app preferences. Original JPEG static metadata deserialize and validate; the actual native engine creates successfully. This inspects native defaults/tuning, not an identical-burst GCam merge or a port of its proprietary kernel. The earlier GCam shot records 30/30 merged frames, MergeShot 1065ms, FinishShot 295ms and 5.24s capture; its postview timing is a separate boundary.

References: [Google Sabre paper](https://research.google/pubs/handheld-multi-frame-super-resolution/) and [HDR+ paper](https://research.google/pubs/burst-photography-for-high-dynamic-range-and-low-light-imaging-on-mobile-cameras/). Neither paper establishes the runtime of our pipeline or equivalence to the supplied GCam APK.

Rejected local prototypes include batched cells, early per-site tests, dynamic native bounds, vector/per-colour native kernels, rejection shared donor tiles, separate variance caching and rejection shared-array layouts. Several were exact but slower; other changed hashes were not treated alone as evidence of worse photo quality. None of those variants ships. The original and experimental sources, timings and complete hashes remain local.

No physical Mali was available. GLES3.1 portability and Linux checks are separate from live Mali quality or speed proof. No universal speedup, subsecond processing, or GCam61 latency parity is claimed.
