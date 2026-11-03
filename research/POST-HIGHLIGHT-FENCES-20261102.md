# Highlight landing without the diagnostic readback - 2026-11-02

## Change

`ScamRgb.opposedShare` previously read the RGBA32F coarse map back to the CPU, waited for the GPU and scanned every cell before running four coarse GPU passes. The scan only supplied a diagnostic summary and an early return for an empty map.

With `hybrid_highlight_landing_async=1` (new default) and file logging OFF, retain the map on the GPU and run the same original passes. An empty map becomes a zero RG16F map and is a no-op in WhyCombine. No shader, frame count, resolution, image settings or pixel calculation changes. The old readback, diagnostic statistics and empty-map return remain active when file logging is ON or the switch is 0. Temporary storage for an otherwise skipped empty 256x192 map increases by about 1.31 MiB; the filled-map peak is unchanged.

## Physical evidence

OPPO/Adreno complete PostPipeline replays used identical captured inputs and settings, two warmups and interleaved states. The first matrix has four timed calls/state; the reverse-order repeat has eight/state. All complete RGBA outputs are byte-identical and repeatable in all four first-matrix and six second-matrix cases. The SkyHDR gain map is also exact. All owner preferences, installed app/probe and scam_dev bytes were restored exactly.

| Active case | First median, old -> GPU-only | Second median, old -> GPU-only |
| --- | --- | --- |
| 12MP lamp | 740 -> 624 ms | 678 -> 627 ms |
| 12MP Moving8 | 764 -> 667 ms | 655 -> 662 ms |
| Derived active but empty | 774 -> 606 ms | 650 -> 604 ms |
| 50MP Sabre2x output | Not in first matrix | 2228 -> 2075 ms |

The 50MP case is an output from 12MP Bayer RAW, not native 50MP Quad RAW. Its four paired reverse-order block improvements are 86-211 ms. Moving8 is neutral in the repeat: the first result does not establish a universal 97 ms improvement. Lamp and empty-map repeats favor the new route but still contain timing noise. The window and SkyHDR controls do not enter opposed landing; their timing spread is not a gain or regression attributable to this change. This is a local postprocessing improvement, not completion of the full reference-speed delivery gate.

The explicit-logging matrix enables the actual backend before the timed loop and queries its state in every run. Both switch states retain the original diagnostic readback markers, no GPU-only marker, and exact RGBA. This proves the logging fallback, not the later Application-init lifecycle change. An earlier preference-only ON attempt did not initialize the old logger entry point; that failed proof is retained unchanged.

## Host and rollback

The existing `tools/check_highlight_landing.py` retains every previous numerical gate. It additionally runs 18 actual suncore MODE3 x/y -> MODE1 x/y chains with production RGBA32F/RG16F stores, odd sizes, several radii and finite/NaN/Inf diagnostic glow values. Empty weight yields exact finite positive-zero RG16F. A larger research grid has 40 such cases; the separate zero-map WhyCombine proof covers 27 fixtures. No tolerance was relaxed. The final focused JVM run passes 45 tests in 15 classes (ScamRgb, developer-key coverage and logging/lifecycle); original version.properties bytes are preserved.

Set `hybrid_highlight_landing_async 0` in scam_dev to restore the original readback. File logging ON also selects it automatically. Direct settings-entry Application initialization has its own tests and still requires final integrated phone validation.

## Receipts

Research root: `D:/burst/scamera-research/opposed-landing-no-readback-20261102/`.

- `runs/20261102-231021-5c038f4b/receipt.json`, `first-independent-audit.json`.
- `second-matrix/runs/20261102-231812-b445d071/receipt.json`, `second-independent-audit.json`.
- `explicit-logging/runs/20261102-233707-d63d6647/receipt.json`, `logging-on-independent-audit.json`.
- Failed preference-only ON: `second-matrix/runs/20261102-232506-2c2d5e47/receipt.json`.
- `zero-coarse-chain.json`, `zero-map-byte-check.json`, `accepted-production/` source snapshots.

No APK delivery or push. Native Quad/Tetra, physical Mali and whole processing-plus-saving timing remain separate final gates.
