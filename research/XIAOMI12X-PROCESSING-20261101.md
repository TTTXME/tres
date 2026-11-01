# Xiaomi 12X processing evidence

Owner supplied three logs for Xiaomi 2112123AG / psyche, Android13, Adreno650.
Preserved originals and SHA256 receipts are outside Git in
`evidence/gcam61-deep-20261101/xiaomi12x-logs/`. The long log spans builds
31195/e18df894,31199/b01602a1 and31200/9fc12cbf; do not combine these as a
single build benchmark. Filenames'112347 do not identify every shot time.

## Current31200 successful five-frame example

The separate debug log records the21:51:19 shot,4096x3072, five accepted normals:

| Stage | Time |
| --- | ---: |
| Native alignment |382ms |
| Native local alignment |145ms |
| Native GPU merge stage |7357ms |
| Total native stages |7889ms |
| Java worker interval |7938ms |
| Complete postprocessing |3291ms |
| Final readback, included in post |1821ms |
| Save |391ms |
| Summary total, including232ms acquisition hold |11919ms |

GPU program cache hits14/14; init6ms. It is not a shader compilation stall.
Thermal status is none at press/merge end/save; this does not measure hardware
clocks, but no reported thermal policy transition explains the delay. WHY uses
active Airy/Gaussian RL, so comparisons against a reference with RL disabled
must retain this distinction. Final readback includes pending GPU work from
earlier asynchronous stages; its1786ms is not proven to be pure transfer cost.

The short timings file is a different21:45:58 shot: worker7230ms, post3321ms,
save257ms,total11082ms. Both successful examples have substantial merge cost.
Other31200 successful shots vary from7159 to12386ms; these are different
captures and cannot establish an old/new quality or performance comparison.

## Two32-36second fallback shots

At13:53 and13:57 on31200 the GPU readback fails atslot3,offset0,12582912bytes.
The worker retries without local alignment, then the Java conservative path
starts another worker attempt. All fail; the existing CPU-single fallback
demosaics the base in1006/861ms. Total summaries35894/32192ms and merge=-1.
Do not report those as successful multi-frame GPU merges. Repeated attempts
consume most of their time and discard the intended multi-frame result.

The failed mapping alone does not identify a GPU reset, OOM, mapping API bug
or a particular shader. No GL error code survives the old tryMap path. A GPU
reset is a hypothesis, not a verified cause. No12X RAW burst or live12X is
currently available; only OPPO is connected.

## Readback correctness fixes

The active worker contained two independently verifiable API/correctness bugs:

- Read mappings used MAP_READ_BIT together with MAP_UNSYNCHRONIZED_BIT in
  the fenced path and one fallback. This combination is invalid even after
  waiting for a fence. Use normal read maps; preserve the waited fence and
  the existing memory barrier. [OpenGL ES3.1 specification](https://registry.khronos.org/OpenGL/specs/es/3.1/es_spec_3.1.pdf).
- The last fresh-buffer fallback ignored glUnmapBuffer returning false. It
  now releases the temporary buffer and rejects invalidated storage, matching
  the other mapping methods instead of accepting potentially corrupt pixels.

Failed maps now preserve a nonzero GL error for the failing readback chunk,
with the error state reset for each new chunk. This is evidence collection,
not a claim that the supplied failures were caused by either fixed bug.

`tools/check_gpu_readback.py` extracts the actual production readback methods
and tests legal mapping flags, exact successful transfer, invalidation refusal,
temporary-buffer cleanup, error reporting/reset and the fenced path using
controlled GL responses. It runs on a host C++17 compiler in CI; the same test
also ran as an Android native executable on OPPO. This is not a driver test.

The full actual Nubia-input OPPO ABBA replay after these fixes retains the
entire RGB/effective/clip output and frame decisions exactly. Worker times
9404/9479/9301/9492ms do not show a dependable speed gain. Do not advertise
this correctness fix as a measured12X acceleration or resolved driver failure.

Frame counts, resolution, alignment and rejection thresholds are unchanged.
No retries are removed merely from a generic readback failure: the current
logs do not prove that a less expensive retry could never recover that shot.
Remaining gate: current12X execution with preserved GL error evidence and
an identical-input burst comparison, rather than attributing every long shot
to logging or reducing merge quality to make a faster timing.
