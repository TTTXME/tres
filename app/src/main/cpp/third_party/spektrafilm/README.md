# Spectral film engine

GPL-3.0 source by Aedan Diez, with film data by Andrea Volpato.
Upstream: https://github.com/chaert-s/spektrafilm-ofx
Pinned commit: 86476afc5b077de77e2278e3658d1ba9309892a1.

SCAMERA changes: tile-only source upload, global half-output dither coordinates,
non-overlapping scratch lifetime pools, an explicit pre-allocation memory budget
and budget-derived tile centres. Image/effect arithmetic remains upstream.
No Unspektrawesome or other proprietary native library is distributed here.

Large spatial renders use five float32 milestones: exposure, halation,
development/DIR, grain, and print/scan. Camera diffusion retains the original
GPU operations but assembles each Gaussian on its global downsample grid.
This preserves long tails instead of clipping them at a RAW tile boundary.
The reduced components are accumulated together per RAW tile, retaining their
original order and avoiding a full readback for each group. Both full-size
milestones, the reduced component cache and temporary uploads are budgeted.
Unsupported native-only routes retain the original scheduler.
`SPEKTRAFILM_SCAMERA_STAGED=0/1` selects the reference/staged scheduler for QA;
the application normally selects it automatically for large spatial renders.
`SPEKTRAFILM_SCAMERA_BATCH_OFF=1` retains the slower global accumulator for QA.

Generated profile curves use this commit's own Resources/data and its
`tools/generate_profile_curves.py`, including nine-significant-digit curve
quantization. See `tools/update_film_reference.py` for reproducible regeneration.
