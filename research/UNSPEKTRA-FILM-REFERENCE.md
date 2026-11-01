# Unspektrawesome film implementation reference

Owner authorizes using 1.2.7 instead of the initially supplied 1.2.4.
Author: JohnTheFarm3r. Release:
https://github.com/JohnTheFarm3r/Unspektrawesome-Releases/releases/tag/v1.2.7.

## Identity

- 1.2.7 APK: 35,441,793 bytes, SHA-256
  `4d77df0c72d5323be3d3734f925ff398ca4be8a746bf50829ff68f53c0022d66`,
  matches the release asset's published digest.
- Native renderer SHA-256:
  `9442b9ee5860dc0d83d2267ccfcc57b81422f8990e64929fe1bfef34392ec972`.
- Tag/source archive commit: `32a4c36ebfd8456fbc975f530d1dbfb22e09aa2e`.
  The archive contains README.md only. It is not application source code.
- 188 embedded SPIR-V modules validated and converted to readable GLSL;
  133 are byte-identical to modules in 1.2.4. The core film exposure,
  development, print/scan and meter modules are among the identical modules.

## Independent reference execution

The native renderer runs as a research-only shell CLI on OPPO PHY110/Adreno750,
without installing an APK, accessing the camera or modifying SCAMERA data.
JNI's startup package/signature validation requires an installed application
identity. The research copy replaces only that validation function's first
eight bytes with an immediate successful return. Processing code, shaders,
profiles and parameters remain untouched. The original binary is preserved;
neither binary is added to SCAMERA or Git.

The exact 648-byte parameter packet is reconstructed from the original DEX
constructor and serializer. 1.2.4/1.2.7 default packets are identical.

Seven 512x384 input/settings cases execute twice in both native versions:
core colour, DIR, halation, Pro-Mist grades 0.5/2, production grain, and all
effects combined. Film: Portra160; paper: PortraEndura. All output FP16 hashes
match between versions and between repeated executions. This is a bounded
version-equivalence check, not a full-resolution performance or SCAMERA proof.

A Vulkan call recorder captures original table uploads, descriptors, push
constants and dispatches. The original three colour stages and format
conversion are replayed through OpenGL with read-only tables packed into a
single buffer. On desktop GPU the 96x64 HDR colour fixture differs by at most
one FP16 step (0.00048828125). On the same Adreno750, the GLES3.1 replay matches
all 24,576 FP16 components exactly, SHA-256
`35a2bb59bd848290e7578664f11686f60e56f00ee4173139674e57ca195fe163`.

This validates the colour-stage ABI adaptation, not the complete film port.
The GLES replay is outside the application and currently consumes captured
profile tables. Production must build its data from the attributed source
profiles, integrate effects/settings and preserve output/HDR contracts.

## Readable upstream source

The film engine's readable upstream source is available separately:
https://github.com/chaert-s/spektrafilm-ofx, pinned to
`86476afc5b077de77e2278e3658d1ba9309892a1`.
Selected source, shaders, headers, generator and GPL license are preserved
under the research directory with per-file hashes. Original Unspektrawesome
execution remains the comparison oracle; upstream source is not assumed to
be identical to its Android fork.

One concrete existing-model difference: Unspektrawesome defaults to Hanatos2026
RGB-to-film conversion; SCAMERA's existing FilmSpectral uses Hanatos2025.
Do not compensate this with fitted saturation or exposure corrections.

The initial upstream executable used the older local spektrafilm0.3.4 data
and differed from1.2.7 by up to0.0466 on the512x384 fixture. Replacing it with
the upstream commit's own attributed profile JSONs reduced the seven effect
comparisons to at most one FP16 step (0.00048828125), mean error below4.2e-8.
All effects are included: core, DIR, halation, Pro-Mist0.5/2, production grain
and their combination. This is independently compiled source, not the
reference application's native library shipped in SCAMERA.

A bounded-source prototype uploads only each tile's source window. The
upstream half-output dither used tile-local pixel indices; retaining full-frame
indices restores exact full/tiled source output on48-tile core/grain fixtures.
Twelve-megapixel bounded core completes with finite output, about1.19s warm,
without the full-frame source GPU allocation. Complete spatial large-frame
parity, memory/performance and production integration remain open.

The first12MP original research CLI exited137. Mapping its input/output instead
of constructing large managed arrays fixes this research harness: the same
original native engine completes the12MP core at267.98ms warm, four passes,
about1.21GB scratch. The initial failure is not evidence that the original app
cannot process12MP. Cold shader compilation remains separate from warm timing.

## Evidence

Outside Git:
`../evidence/unspektrawesome-127-20261101/{download-identity.json,reference-version-matrix.json,shader-version-comparison.json,ofx-source-identity.json}`;
`../evidence/unspektrawesome-124-20261101/{native-reference-build.json,film-param-schema.json,native-capture-replay.json,gles-native-replay-numeric.json}`.

The user's JPEG pair uses different exposure, paper, film format, diffusion
grade and halation settings. It does not isolate a processing discrepancy.
No proprietary reference binary is included in the application.

## Production photo integration and final checks

The attributed upstream source and its own generated profile data are now
integrated as `scameraFilm`. The photo pipeline feeds scene-linear sRGB after
the separate noise stages and external lens filter. At full simulation
strength it bypasses WHY exposure/fusion/tone/sharpening and legacy film
effects, avoiding double grain, halation and contrast. Partial strength mixes
with the normal photo; user LUT and Display P3 conversion retain their domains.
Explicit settings and the film snapshot taken at capture are preserved.

All 20 measured colour films and eight papers are mapped by their source IDs.
Existing monochrome models remain separate because the source has no matching
measured profiles. Unsupported Vulkan devices or memory-budget refusal retain
the existing photo route and log that fallback; that output is not claimed to
be the new reference simulation. The existing viewfinder's LUT/effect preview
is not an exact full-resolution spectral-render proof.

Nine lifetime pools reduce peak GPU scratch. Whole-graph tiling was rejected:
it repeated the spatial graph 192 times and took about 237 seconds on a real
12MP input. Increasing its fixed halo also cannot preserve the broadest
diffusion tails within the device's advertised storage-buffer range.

The accepted large-frame scheduler uses five global float32 milestones with
separate halos. Camera diffusion applies the same upstream GPU operations on
the complete scale-1/2/4/8 grids, preserving component order and long tails;
the literal box-downsample addition order is retained. Stage support includes
the sum of sequential blur radii. No intermediate half rounding is added.
CPU milestones/reduced caches and GPU buffers are budgeted before allocation.
Small/core-only renders retain the simpler original scheduler.

On Adreno750, all seven 512x384 effect cases remain bit-exact to the full
upstream source. A real 2048x1536 crop and an odd 2111x1501 crop with 8mm,
strong halation/diffusion and scanner blur also match the conformant single-
tile source bit-for-bit. The full 4096x3072 output is identical under 1.25GiB
and 3GiB budgets (SHA256
`0ec529cfaad1afbefa9360548644ab6546d86f31a0e8c08172e8177951e6cd68`).
The earlier over-range 12MP one-tile experiment returned zero output and is
invalid; it is excluded from every quality claim.

Actual Android post-processing with full effects completed in 9.56/8.66s,
including readback and a decodable JPEG; the two outputs repeat exactly.
Core-only P3 at 50% strength completed in 3.92/1.73s. At 100%, the core output
is independent of deliberately changed WHY exposure/tone controls. A forced
32MiB rejection returns a byte-identical legacy output without losing the
photo. The original application/source comparison still has its measured
one-FP16-step bound: this is not universal bit identity to every reference
device or RAW-development path. Physical Mali, native FULLRES film timing and
all-profile exhaustive visual equivalence remain unverified.

Final local evidence includes `film-stages-matrix.json`,
`film-real-input/crop*-global-reference.json`,
`film-real-input/global-final-budgets.json`, `film-final-effects-post.json`,
`film-final-p3-half-post.json` and `film-final-fallback-post.json` in
`../evidence/gcam61-deep-20261101`. These are local test receipts, not an
Actions artifact or a completed delivery.

The final scheduler also combines component accumulation per RAW tile. It
retains the same component order and reduced grids while avoiding repeated
full accumulator transfers. Seven small cases, both large/odd crops, maximum
UI halation/softness and the complete 12MP output remain bit-exact. The
1.25GiB-budget full output retains the same hash. The reduced component cache
is charged separately before allocation; CPU upload windows are bounded too.
The original/global accumulator remains available for comparison.

In the final application, the full-effects post replay completes in
7.94/6.07s (cold/warm), excluding capture and merge. Its complete RGBA8 output
and JPEG repeat exactly and retain the previous scheduler's hash. The final
device probe also passes all 65,536 half-bit patterns, JNI repetition,
overlap refusal, tiny-budget refusal and recovery. Availability is checked
before full image readback/allocation, so unsupported Vulkan devices do not
pay that cost just to return to the existing film route.
Evidence: `film-batch-matrix.json`, `film-batch-large-matrix.json`,
`film-batch-budget-extreme.json`, `film-final-batched-post.json` and
`film-device-probe-final.txt` in the same local evidence folder.
