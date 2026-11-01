# Post-processing speed / ARK209 review (2026-11-01)

## Accepted change

`ScamHdrDenoise.estimateNoise` keeps the original block-statistics render and the
original percentile and scale factors. On GLES3.1 it can select the same quarter
on the GPU instead of reading all RGBA half/float blocks to the CPU. A 32768-bin
integer histogram uses the ordering of positive half-float codes; a second pass
selects element `count / 4`. Only four integers come back.

The fast path accepts a complete map of positive normal half values or positive
infinity. Zero, negative values, NaNs, subnormals, incomplete counts, GL errors,
compile/map failures and insufficient compute limits retain the original CPU
readback and selection. In particular, sampling and readback can flush half
subnormals differently: a representable subnormal is excluded from the fast path
even when the sampler preserves it. No device-name or GPU-vendor whitelist.

The histogram group is 8x8 and selection group is 128x1 with 512 bytes of shared
memory. The two SSBOs use 128 KiB plus 16 bytes. These avoid the earlier prototype's
256-invocation requirement. Developer switch `post_noise_quantile_gpu 0` restores
the CPU path; the old `post_ab` run always keeps it. No additional user processing
strength, frame count, resolution or sharpening change.

## Evidence

Local, uncommitted evidence: `SCAMERA-PC/evidence/gcam61-post-speed-20261101`.

- Actual Android PostPipeline replay on OPPO PHY110 / Adreno750: all eight recent
  `D:\burst\Штрихи` series plus `Moving/172902`, original per-shot parameters and
  full 12 MP worker RGB/effective-frame/clip maps. Each final Bitmap RGBA is
  byte-identical to the original application baseline, including repeated runs.
  `final-noise-post.json` records installed APK, merged input and metadata hashes.
- `noise-final-fixtures.txt`: 11 actual Android GPU/readback vs independent CPU
  sort controls, including every half code, collisions, NaNs, infinities, zero,
  signed subnormals and non-power-of-two geometry. Fast/decline decisions checked.
- `tools/check_noise_quantile.py`: the actual compute shader against an independent
  numpy sort, including all positive normal codes and explicit fallback fixtures;
  selected by Linux CI. Native estimate render and CPU fallback are unchanged.
- Matched, alternating `post_sync` runs: noiseEst warm medians 26->6 ms on230628,
  22->6 ms on062956. Motion's conservative fallback remains exact. These are stage
  measurements, not capture-to-JPEG latency or a global speedup percentage.
- A separate balanced nonsynchronized run (20 replays, four warmups discarded,
  eight samples per path) gave complete PostPipeline medians688->671/657->644ms
  on those two bursts. Input loading, capture, merge and JPEG encoding are outside
  this timer; `final-throughput-review.json` retains every sample and its scope.
- Offline main/test Java compilation and25 developer-key/denoise/Sport tests pass
  (`jvm/`);12 host GPU fixtures pass. No live Mali phone was available; compatibility
  uses shared GLES3.1 features and conservative runtime fallback, not Mali timing
  or image-quality proof.

## What ARK209 establishes

The owner's `arkcamv209.apk` SHA256 is
`28d72b6267860cd0d6fa93c78bb1254b834babb97d382df01559cdce34c5e3d1`.
93 of 94 supplied shader bodies are present byte-for-byte in its actual library;
the final tone body is at native file offset0x5b872. Evidence is in
`evidence/arkcam-review-20261030/apk209/shader-comparison.json`.

The final shader receives ready inset/outset matrices and samples its custom
curve from a texture. It still evaluates analytic AgX powers. Its separate RL
blur/divide/multiply/blend and USM inputs do not justify replacing our already
fused passes. Shader names alone do not establish native execution order, actual
working resolution or timing. No proprietary library/shader blob is added to Git.

## Evaluated, excluded candidates

Identical full real bursts were replayed before rejecting the candidates; changed
hashes alone were not called quality regressions. Evidence, full outputs and
detail/motion crops are preserved locally.

- Deferred per-pass flushing: identical output but slower on the phone.
- Custom curve texture, bicubic gather, RL mode specialization and paired sky
  readbacks: no stable advantage over the current implementation.
- Fused despeckle/YUV: changes final pixels (up to8 levels), no dependable gain.
- GPU-prepared AgX curve: active-tone comparison on all nine real bursts and32
  FP32 ramp profiles. Small tone-stage gain, but Motion has rare differences up
  to16 levels on coloured gamut boundaries; no production change is accepted.
  All inactive-custom-curve controls are explicitly separate from active AgX.

Only the exact noise-percentile optimization is delivered. Protected
`kHybMergeMain1`, capture, alignment, tone curves and sharpening remain unchanged.
Release commit, Actions URL, unmodified final APK checksum and phone smoke receipts
are saved outside Git after CI; a local test APK is not the delivery artifact.
