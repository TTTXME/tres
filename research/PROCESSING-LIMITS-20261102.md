# Remaining processing-speed limits

This records P462 research after the accepted shared RAW tiles, Sabre 2x split
and FP32 donor-sum reuse. It does not establish end-to-end speed parity.
All phone measurements are rootless OPPO PHY110 / Adreno 750 replays; capture
time is excluded. Inputs, tuning, frame counts and output dimensions are fixed.

## Accepted integration

The final production worker without fault macros reproduces all nine complete
Sabre 2x reference files (`donor-final-matrix.json`). The integrated ABBA matrix
has 36 exact complete files; the preceding scalar-cache matrix has 72.
The production worker SHA-256 is recorded in `worker-production-donor-build.json`.
The twelve actual shaders compile offline; 96 actual GPU fixtures compare
3477760 components without a single rounding difference. Eight end-to-end
donor-cache GPU fixtures have zero FP32 error and exact trailers. Sixteen focused
film/crash JVM tests pass. Fault injection preserves the complete 50MP result,
including failures after earlier strips have reached the consumers.

These changes retain FP32 accumulation, all input frames, reconstruction size,
the original CA/chroma processing, tone and sharpening. The supplied slide-film
coefficient correction remains a separate P463 fix.

## Additional candidates kept outside production

| Candidate | Evidence and decision |
| --- | --- |
| Fully resident GPU CA and chroma | 1.32-1.37 s versus 1.07-1.11 s in the 12MP control. One-half-step differences. Slower; rejected. |
| Strip GPU CA with overlapped CPU chroma | No useful whole-worker improvement at 12MP or 50MP. Maximum half-output difference 0.00048828125. Rejected for performance, not because a changed hash alone proves worse quality. |
| Batched pass flushes | 28 complete outputs exact. Several cases 3-7% slower; rejected. |
| Packed RAW texture buffers | The earlier tile test incorrectly retried the original path because the research uniform copier rejected sampler-buffer type 0x8DD8. After correcting that research copier, all 40 complete files are exact. Warm changes range from about -1% to +3%; no dependable gain, rejected. |
| NEON CA interpolation | All 19045440 FP32 fixture components and 32 real full files exact. The CA stage becomes cheaper, but whole-worker gains are inconsistent; rejected. |
| Certified zero/one soft-sign exits | 439200 special-value/random components and 32 real full files exact. Whole-worker gain not dependable; rejected. |
| Asynchronous CPU CA queue | 72 feed/cancel cases, 16153920 exact FP32 components and consumer snapshots; 32 real full files exact. Small mixed timing changes; not enabled. |
| Persistent pool for all CPU row passes | 32 real files exact; some series faster, Moving slower. Not enabled. |
| Persistent pool for 2x CA only | 19045440 exact FP32 components and 36 exact real files. Several scenes improve 4-5%, others slow by 1-3%; not enabled pending a reliable policy and stronger measurements. |
| Wider merge strips | First 50MP comparison changes 6618 half components, maximum 0.0009765625, and 2826 trailer bytes. Differences concentrate near strip boundaries. One timing pair does not prove a useful gain or an image-quality regression. Further investigation required; not enabled. |

## Profiling boundary

Simpleperf recorded one current four-N 12MP worker with `cpu-cycles:u`, 1000 Hz
and DWARF callchains: 5073 samples, none lost. The principal self-cost entries
are CA resampling (26.48%), chroma soft-sign rows (12.48%) and CA measurement
(11.02%). These are CPU-cycle shares from one instrumented replay, not wall-time
shares or evidence that removing a stage would save the same fraction of latency.
The median stream is already overlapped with the GPU merge.

The attempted asynchronous GLES timer queries return zero stage durations even
with `disjoint=0`; those values cannot be used as GPU measurements. Off-CPU
Simpleperf recording cannot open `sched:sched_switch` on this rootless target.
Neither limitation justifies changing system profiling permissions or inventing
a measured GPU/CPU split.

A public AHardwareBuffer BLOB/SSBO cross-process probe transferred 150994944 bytes
between separate rootless shell processes without bit errors. It is not an app-UID
or SCAMERA transport integration and does not yet establish a pipeline speed gain.

The reference JNI replay's approximately 330-341 ms uses different post settings
and lacks the supplied lens-shading calibration. It is useful for stage-cost
investigation, not an equal-quality latency oracle. Physical Mali, Nubia's black
output and Xiaomi 12X timing remain unverified. P462 remains open; no later push
or release APK is implied by these local results.

Detailed scripts, logs, rejected headers and binaries are retained outside Git in
`../evidence/why220-after-delivery-20261102/`.
