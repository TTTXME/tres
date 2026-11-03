# JPEG token reuse

The encoder retains each full strip's existing quantized DCT tokens, collects
their actual symbol counts, constructs shared Huffman tables and writes the
tokens once. Image geometry, strip boundaries, quality, 4:4:4 sampling,
quantization and the selected adaptive-quantization policy are unchanged.
This removes the discarded statistics JPEG and repeated DCT work.

Bundled jpegli internals are deliberately pinned to the same headers and static
library. The retained route validates streaming sequential-token state before
using the library's existing entropy writer. It is not a public jpegli API.
Do not replace headers or the archive independently.

## Physical evidence

Rootless OPPO PHY110 / Adreno750, complete 4096x3072 RGBA input, q98, eight
threads. Actual current CMake library was loaded through ART with a genuine
Bitmap and OutputStream. Two warmups and four balanced measured encodes per
adaptive-quantization state:

| Adaptive quantization | Original ms | Token reuse ms |
| --- | ---: | ---: |
| Off | 111.45 | 81.19 |
| On | 151.78 | 106.08 |

All decoded pixels, quantized coefficients and quantization tables match.
Rollback, repeatability, writer exception and subsequent recovery pass.
The final CMake JPEG bytes also match the previously checked manual JNI build
for each policy. These are encoder-only times: filesystem, EXIF, HDR packaging,
RAW merge and acquisition are excluded.

The preceding real detail/night/Moving/film/50MP matrix contains36 configurations
at q90/98/100 and AQ0/1. The final geometry policy was checked again on18 detail,
adversarial and50MP configurations, with exact decoded pixels and coefficients
and the unchanged10% file-size gate. The adversarial image has flat sampled
rows and random detail elsewhere. Tiny histogram sampling enlarged that file
by72.1% and is rejected. Full token statistics retain essentially the original
size; no tiny-sampling path remains in production.

## Memory and failure policy

The optional retention budget is384MiB. Known MemAvailable must be at least
1024MiB; unknown/low memory declines immediately. A conservative padded-geometry
bound covers token-array growth and the pinned input/AQ/bit-writer workspace
before any DCT is started. Typical12MP fits; tested50MP declines before DCT
and uses the original full sampled strips with the safe statistics shortcut.
No token-reuse acceleration is claimed at50MP.

Each jpegli instance also has a hard allocation limit. Failed preparation
releases all retained instances before falling back, before any destination
byte is written. Writer errors propagate without appending a retry to a partial
stream. Unknown/low memory, allocator failure, late finish failure, odd geometry,
both AQ modes, thread-creation failure and policy snapshot are covered.

The policy is enabled by default. Explicit rollback: `jpeg_fast_tables 0`.
The log says `token reuse allowed`; that describes policy, not proof that a
particular image passed the memory/geometry guard. AQ is captured once per
native encode, and every successfully created helper thread is joined even
if later creation fails.

Evidence is retained under
`../evidence/vf-processing-20261102/jpeg-save/retained/production/`:
`revision3/matrix-runs/20261102-165246-a2bff05d/`, final guards/matrix, and
`final/jni-20261102-170936/receipt.json`. The actual CMake artifacts and source
inventory are in `../evidence/vf-processing-20261102/cmake-native-current/`.
This local validation does not satisfy the full WhyCam+500ms push gate or
replace final-app/CI artifact verification.
