# Viewfinder GPU scheduling, 2026-11-02

Local P465 work over 5f990c7d; no delivery or push. Evidence is outside Git in
`../evidence/vf-processing-20261102/`. Device: rootless OPPO PHY110, Adreno 750.

## Cause and changes

The full same-run Perfetto trace has no overwritten data. During the heavy film
recipe, a 416 ms viewfinder draw spends 410 ms in SurfaceView dequeue; the GL
thread uses about 4 ms of CPU. The compositor/display path waits concurrently,
while RAW callbacks continue at approximately 30 FPS. This is GPU/display
backpressure, not an observed failure of RAW delivery.

The viewfinder now requests high EGL context priority with a checked ordinary
ES3 fallback. Subject PBO readback polls an explicit fence without waiting and
does not map a pending transfer. These changes alone did not remove the heavy
film stall.

Film rendering can now select an interactive queue through a per-call JNI
policy. On Qualcomm, a graphics+compute family with an advertised global-priority
extension requests LOW. Unsupported hardware, missing capabilities or rejected
creation restores the original family and ordinary device request. Other GPU
vendors retain their previous route. `film_vk_queue_policy 0` selects the legacy
route; the default is 1. Shader code, arithmetic, dispatches and passes are unchanged.

JNI renderer caching and the shared weak backend include the policy. Device-lost
recreation preserves it. The old JNI entry points retain legacy policy 0; new
entry points take the requested policy atomically under the existing native mutex.

## Matched phone observations

Every application variant used the same saved settings: Portra400, PortraEndura,
35 mm, DIR 1, grain 1, halation 1.01, softness 1.07, film/print EV 0, strength 1,
no diffusion. The 1.01/1.07 values are actual UI slider values, not rounded to 1
for reporting. All native executions report 224 passes and 22 tiles.

| Queue | Film-period viewfinder | Largest period gap | Warm native film |
| --- | --- | --- | --- |
| Legacy dedicated/default | 16.8-17.2 FPS | 443-460 ms | 3978 ms |
| General/default | 18.0-20.7 FPS | 442-447 ms | 3916 ms |
| Dedicated/LOW | about 30 FPS | 47-71 ms | 4366 ms |
| General/LOW research | about 30 FPS | 54-67 ms | 4065 ms |
| General/LOW production source | about 30 FPS | 47-56 ms | 4071 ms |

These are observations from two captures per installed variant, not a statistical
claim of a processing-speed gain. Capture/merge-period gaps are excluded from
the film-period column. Thermal pressure and native scratch budgets are in the
logs. The production application explicitly reports family 0, flags 415,
`globalPriority=low`, `lowResult=0`, `VK_KHR_global_priority`.

## Image and integration checks

- A single-process production JNI replay exercises old ABI 0 and new policies
  0,1,1,0, including renderer/backend replacement. Five complete 4096x3072 outputs
  match the original exactly, SHA-256
  `f8ac76d50d6d335311a8fbe713d7eef1867194a6294e7da208489cddac560b3f`,
  with no nonfinite components. Invalid policies fail without modifying output.
- The isolated packet for that replay uses all four effect amounts at exactly 1;
  it is a separate equal-input arithmetic check, not the application slider recipe.
- 24 actual-header host cases cover family selection, unsupported vendors,
  extension absence, rejected/poisoned handles, complete original fallback and
  renderer cache lifecycle. Focused Gradle tests: 121 tests, no failures/errors.
- JNI and renderer build for ARM64/API26. Native source build and installed QA
  APK receipts are local evidence; a local QA APK is not a release artifact.

## Remaining scope

Without film, an independent trace still observes approximately 28.7 GL callbacks/s
during the worker and 25.7/s during postprocessing, with 138-143 ms maximum gaps.
The GL thread mostly waits in dequeue/swap/display fences. A separate 94 ms Binder
wait occurs in externalstorage. An equal-input shader or CPU attribution is not
available from that trace. Lowering the existing post-context priority alone did
not establish a useful responsiveness improvement and is not enabled.

OPPO uses the ISP display branch. Its results do not establish X200 Ultra RAW
viewfinder pacing or physical Mali responsiveness.

The supplied X200 Ultra logs independently show a RAW-specific artificial ceiling:
the busy policy capped processing-time updates at 15 FPS while the tone target
remained 1440x1920. Devices already eligible for an ordinary RAW viewfinder now
use a 30 FPS busy default and a deterministic target of at most 750000 pixels,
starting with the first busy draw (1440x1920 becomes 750x1000). The size is derived
from the original physical viewport and crop; idle restores the original target.
Smaller existing targets are not enlarged. This reduces only temporary preview
detail, not saved images, RAW delivery, metering, ZSL or capture resolution.
Explicit frame-rate settings, SEVERE limits and older/unknown/low-memory device
policies remain in force.

The integrated focused suite passes 161 tests in 24 classes, including RAW
geometry, device policy, immediate busy/idle transitions and explicit/thermal
limits. Source and template builds pass. Only OPPO is connected: the X200-specific
change has host/code validation, not a live-device RAW FPS claim.

Whole-frame fenced batching, selective batching, shared Gaussian coefficients
and 16x4 film workgroups were kept outside production: exact outputs, but no
repeatable throughput benefit. Their receipts and source snapshots are preserved.
The separate large-burst processing target remains open and has no push approval
until the owner's no-slower-than-reference gate is met.
