# P441-P443 integration and exact native culling

Base: `origin/main b01602a130c6990fbf798679fe0e41798817c171`, fetched again before finalization.
Owner package: `SCAMERA-PC/handoff-p441/README_P441.md`. Donors, in order:
`bc46922cc889e60410f70f2bb631ecc1a1741dd4`, `ca25449d9b7e075c02873ee2f27053bad88c5de5`, `b1b2649b4a46a3c39ddff530c46b4cbee0ea87e4`.
All three applied without conflicts in a separate worktree. `project/` and `wt-hl` were not changed.
Delivery is one local commit over the base; the latest integration request requires a separate owner command to push.

## Included

- P441: false sharpener lattice cap, continuous highlight-rim protection, lamp-core dark-detail fade, three worker ultrashort edge keys written by the app.
- P442: night-only exposure/shadow policy, daytime exposure rule unchanged.
- P443: Resolve denoise by default, previous SCAM9.6 selectable, night detail, simplified menu, JPEG adaptive quantization off by default in the core/parallel implementations and JNI setting.
- Exact native Quad/Tetra culling (P446). No experimental KLT patch cache; see `GCAM61-SPEED-ROUND3-20261101.md`.
- Device-discovered cache crash fixed: snapshot timestamps before sorting, rather than reading concurrently changing mtimes inside TimSort's comparator.
- Device-discovered near-black colour mottle reduced by a wider luma-guided chroma filter in Resolve's final pass, gated by local SNR and level. It leaves luminance unchanged. Ineffective small-footprint/dark-fade prototypes do not ship.

## Evidence and reproduction

Local directory: `SCAMERA-PC/evidence/p441-speed-integration-20261101/`, including reproducible scripts, original inputs, full outputs, logs and SHA receipts. Preserve original per-shot settings, frame budgets and full sizes. Portrait outputs are **3072x4096**, not 4096x3072. Receipts explicitly named `invalid-portrait-layout` are excluded.

- Final full JVM run: **2391 tests, 2387 pass, 4 failures, 0 errors/skips**, only `EditorViewModelTest`. Its production and test sources are unchanged from main. Baseline also fails; flaky failure counts differ. WorkerCompletion passes with Git's `sh` on PATH. Complete XML and summary: `final-unit-results.zip`, `final-unit-summary.json`. No weakened assertions.
- All eight requested host checks pass: colour output, fused viewfinder, WHY headroom/sharpening, UI language, settings model, noise quantile, Hybrid dezipper. HEIF-file subcheck skips because `pillow-heif` is absent; GL/shader/JPEG/WebP/HLG portions pass.
- Resolve glslc passes. New production-shader test `check_resolve_shadows.py`: shadow chroma RMS ratio 0.356 on its synthetic noise fixture; unchanged luminance, bright pixels, bypass and constant dark colour; finite near-black output. Added to CI.
- NDK27.2 builds succeed for the changed worker, JPEG core/parallel test and JNI library. Worker SHA-256 `570c1e34ce74c4fced49a77c3525da69489560cd7d5e649b147cab25b6268086`; JPEG JNI SHA-256 `ad30b0293fa112e7702cac124051581f91d20e050913b6a84020d65b18483a6f`.
- Actual OPPO PHY110 / Adreno750: **27** worker replays with original tuning, US keys absent, match the original full RGB/map/flag bytes. Includes recent detail/Moving, Bayer/Quad/Tetra, FULLRES main/UW/tele, ISZ and Quad50MP Sabre2x. `worker-matrix.json`.
- Three US keys enabled: 062956, 063029 and 230628, two runs each, each pair byte-identical. Actual logs list all keys. Outputs intentionally differ from keys-off. `us-on-*.json`, `us-post-inputs.json`.
- Actual Android full PostPipeline on all eight recent detail/night/day series plus Moving: original metadata and effective-frame maps, repeated full-size output identical within each variant. Three new keys-on outputs also pass full post and JPEG JNI encode/decode. `engine-ab-first-four.json`, `final-engine-ab-post.json`, `remaining-final-post.json`, `wide-chroma-ab-post.json`, `us-final-post.json`. Node logs measure submission/host time, not pure GPU time; complete post includes final bitmap synchronization.
- New default vs SCAM9.6, alternating eight-run trials, first sample of each variant excluded: complete-post medians **732->712ms** (balcony), **714->632ms** (deck), **714->669ms** (063029), **1010->823ms** (Moving). New tone shared by both variants; not equal-output or capture-to-JPEG/GCam comparisons.
- Original Resolve increased near-black colour mottle. Additional guide lowers fixed-mask 4-16px chroma-band RMS **0.4044->0.3150** (port), **0.3762->0.3287** (room); luma high-pass effectively unchanged. All nine final scene previews, automatically ranked edge crops and 8x brightened worst shadows were reviewed. Fine grain remains; no claim of zero colour noise in every tile. `wide-chroma-ab-quality-metrics.json`, `visual-review.json`. Extra guide timings are mixed/noisy; no universal acceleration claimed for it.
- Actual production grain hash: **3,145,728** coordinates, zero integer-wrap/float mismatches on Adreno. `check_resolve_grain_gpu.cpp` added to CI.
- Final source APK actual shutter smoke: capture and worker succeed, Resolve enabled with effective-frame map, JPEG4096x3072 / 5,490,316B saved and independently decoded with matching device/local SHA. Resolve node121ms submission, camera pipeline log449ms node sum, JPEG native93ms and full save160ms; not a balanced speed comparison or GPU-only timing. Local packaging initially compressed a sound resource, causing a launch failure; the diagnostic packager now preserves the source template's compression flags. No production sound code workaround was added.
- Actual `tools/jpeg-check`, three identical RGBA inputs, q98, eight threads, four alternating repeats: native decode/parity checks pass. AQ on->off sizes: port **4,755,893->5,510,030 B**, room **5,619,696->6,333,004 B**, day **3,636,053->3,926,807 B**. Warm median encode times AQ on/off: **153/152**, **146/145**, **129/143ms**. Size growth is intentional; AQ off is not universally faster. App `JpegliEncoder.compress` JNI encode/decode also passes on actual post outputs.

## Rollbacks

`scam_dev`: `hybrid_why_sharp_lattice_cap 0`, `hybrid_why_rim_smooth 0`, `hybrid_us_edges 0`, `hybrid_why_night_ev_hi 0`, `hybrid_denoise_engine 1`, `jpeg_aq 1`, `hybrid_why_night_detail 1`.
Extra shadow guide only: `hybrid_ark_dn_dark_chroma 0`. Worker culling reference: `SCAM_NO_NAT_CULL=1` environment.

## Boundaries

- No physical Mali device. Offline/host GPU and actual Adreno results do not establish Mali runtime, timing or quality.
- Quad50MP/Tetra native merge validated; new full Android post with matching original 50MP/Tetra metadata not established here. Existing 200MP memory-safe fallback remains.
- Linux sanitizer CI not run for this unpushed delivery; entries present. Local diagnostic APKs are not Actions artifacts or an official APK delivery.
- No GCam6.1 latency parity or universal speedup. Protected `kHybMergeMain1` unchanged: SHA-256 `707695005ec6fc4d7753a7791a6eb2e8377069d48ddfa2193f114da6ed2a41a2`.
- No root/radio/system-setting changes or original burst deletion. Developer file remains exactly `ettr 1\n` (7 bytes). No deliberate owner preference edits; original official app was not debuggable, so no bytewise before/after private-preference claim.
- Phone ends on HOME with the verified **local diagnostic31200** source APK. Android rejects restoring official31199 as a version downgrade, even with `-d`; app data were preserved rather than uninstalling. The next official CI APK must be installed as a normal same/newer-version update. Removed only own diagnostic RGBA duplicates after verifying retained local SHA copies (5.69GB), original bursts untouched.
