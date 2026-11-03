# Bayer merge semantics investigation

Research only. No merge change is accepted by this report and the processing
plus saving delivery gate remains open. Measurements prioritize 12MP Bayer;
native 50MP Quad2x2 without hardware remosaic is a separate validation route.

## Reproduction

On rootless OPPO PHY110, Adreno 750 / GLES driver V@0762.39, the original
marked-site shader and equivalent single-site shaders produce substantially
different merge-stage values on identical captured inputs. The legacy strict
identity test still fails; its assertions and tolerances have not been changed.

The authoritative capture is
`D:/burst/scamera-research/merge-qsite-diagnostic-v4-20261102/phone/20261102-195137-3a032864/receipt.json`.
All actual input buffers, the frame UBO and default uniforms are captured, with
buffer-update visibility barriers and completion before CPU reads. The RGB,
coverage and flag output buffer objects are distinct and remain unchanged across
both dispatch routes. Independent source/bounds checks found no active-path
out-of-range read, overlapping output write or uninitialized accumulator. The
underlying implementation cause is still undetermined.

At output (0,0), every donor has zero robustness; the only red base sample is
1023 with black64/white1023, is not an outlier and exceeds the clipping threshold.
The source therefore requires red1 and the red-clipped flag. The original GPU
program returns red0.110393338 and loses that flag. This difference cannot be
explained by ordinary FP32 rounding.

## Independent semantic checks

The new point oracle is separate from the unchanged legacy identity tests.
It uses independently implemented source formulas, hand-checkable controls and
outward FP32 operation intervals based on ESSL precision requirements. Its bounds
are not fitted to observed GPU values. Complete output files are retained.

Physical run:
`D:/burst/scamera-research/q-semantic-renderer-20261102/runs/20261102-195303-916529a6/semantic-result.json`.
Four CFA layouts, marked/clipped/outlier sites, exposure scaling, per-frame
covariance, dezipper, frame roles and window offsets cover 14 derived fixtures.

| Shader variant | Passing gated points |
| --- | --- |
| Original four-site loop | 24/89 |
| Original loop with constant normal role | 24/79 eligible |
| Original loop with equivalent clip/else branch | 24/89 |
| Four separately compiled constant-site programs | 73/89 |
| One program, uniform site selected in four dispatches | 89/89 |
| One program, workgroup-Z site selected in one dispatch | 89/89 |

Five exact-window-boundary points remain diagnostic gaps. Fourteen rim flag
quantization ties admit adjacent bins under the specification's operation
bounds, so full flag bit identity is not claimed for those points. All other
75 gated flag values are checked exactly. A further three forced red-clipping
fixtures independently observe normal, long and ultrashort role behavior:
`runs/20261102-195503-dd7ac56e/semantic-result.json` under the same renderer folder.
The uniform-site program passes all three; the original and clip/else
programs fail all three. The constant-site variant passes one. The subsequent
workgroup-Z program also passes all three in its separate physical run.

The input manifests, numerical checker and negative controls are under
`../evidence/vf-processing-20261102/qsplit-point-oracle/`. These are explicit
derived fixtures, not additional camera captures or full-image quality proof.

## Remaining gates

Both runtime-site candidates have now been replayed on complete real 12MP
30-normal detail and eight-normal Moving bursts. Frame plans and alignment stay
unchanged. The detail output is completely identical to the original; the Moving
output is completely identical between the two runtime-site candidates and
repeatable, but differs from the original. Paired Android final postprocessing
uses each output's own coverage scale and clipping map: mean RGB byte delta is
0.0571, maximum15, with757 components differing by more than4; alpha is exact.
No new visible ghost or loss of finger/cloth detail was found in the examined
crops. This is one scene, not a general image-quality acceptance.

The workgroup-Z variant's balanced eight-repeats-per-route matrix reports
2507.0 versus2514.5ms on detail30, and1021.0 versus997.5ms on Moving8. The wide
overlapping timing ranges do not establish a dependable acceleration. These are
whole-worker timings, excluding application postprocessing and JPEG. Raw outputs
and receipts are at
`D:/burst/scamera-research/merge-q-workgroup-z-20261102/semantic-runs/20261102-202149-38aab18f/`;
the paired final post run is
`D:/burst/scamera-research/q-uniform-final-post-20261102/runs/20261102-200920-5369b6e4/`.

The initial independent92-point checks compile without LOCAL_ALIGN. A subsequent
actual Moving8 pre-chroma/rim capture also checks that branch: the workgroup-Z
program passes21/21 independent bounded points, including eight with admitted
nonzero LaCell16 offsets, with all21 complete flags unambiguous. The original
passes4/21. Its input buffers and UBO remain unchanged, repeated workgroup-Z
dispatches agree exactly, and the complete instrumented output equals the
uninstrumented Moving file. This capture is at
`D:/burst/scamera-research/qz-real-la-capture-v2-20261102/runs/20261102-203937-e7d0f1d7/`;
the independent extension is in
`../evidence/vf-processing-20261102/qsplit-point-oracle/local-align-v1/`.
Its instrumented time is not a performance measurement. This closes the
specified21-point LOCAL_ALIGN gap, without removing the earlier five exact-window
or14 rim-quantization limits or establishing full-image mathematical identity.
Integration and a dependable speed gain remain unaccepted. Four dispatches or parallel site groups
do not imply an acceleration. The older one-pixel dispatch experiment
was byte-exact on a real 30-frame detail burst but slower and remains rejected.
No performance or correctness result here establishes Mali, native Quad/Tetra,
X200 Ultra, Nubia or Xiaomi device behavior.
