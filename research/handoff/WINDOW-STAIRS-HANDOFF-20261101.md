# Window stair artifacts: handoff, 2026-11-01

## Latest owner scope: deliver without P424 or phone tests

Owner, 2026-11-01: finish the remaining work without a phone and push to main, excluding the window-stair fix.
P424 stays unresolved and is handed to the other AI; it no longer blocks delivery of the ready processing and speed work.
Host GPU PBO parity/fallback checks, film strict checks, AgX colour controls and Sport denoise checks pass. Real Android
driver PBO equivalence, phone press-to-file speed/memory and new phone photography remain unmeasured, not falsely completed.
Delivery/CI/artifact evidence: `SCAMERA-PC/evidence/processing-release-20261101` and `research/handoff/RELEASE-20261101.md`.

## Newer local integration: speed-p424

Owner explicitly requested cherry-pick of `6cf9b2e9` and `f4598e88` after this handoff. This has now been done in this same
worktree, preserving the processing changes first as checkpoint `ad970068`. Local cherry-picks are `ef18bef9` (worker) and
`264d4557` (app), with no conflicts. Subsequent validation/docs commits may follow them. The historical detached HEAD41430fc0
and no-new-commit statements below describe the window investigation before this integration, not the current Git tip.

Read `SCAMERA-PC/handoff-speed/HANDOFF_SPEED.md` and `SCAMERA-PC/evidence/speed-integration-20261101/REPORT.md` for exact
validation and remaining device boundaries. CI now includes the new strip-trailer check, FillFrameLevelsTest and TileBlitterTest.
Speed integration does not fix P424. No push or APK delivery is implied; do not reapply either speed commit to the current tree.

## Actual status and scope

**P424 is not fixed. No production window-AA/reconstruction change was integrated.** The owner asked for a prompt for another locally installed AI if the fix was unsuccessful. Do not label a lower contour statistic, RAW RMSE, weaker sharpening, or a smoother different Ark JPEG as completion.

The current task is the jagged/discontinuous window rim, including the lower-right curved corner in the owner's last screenshot, plus earlier distant windows and radios. FULLRES/ISZ/DCG and stock ZSL bracket are unrelated historical tasks; do not resume them.

The window investigation made no new APK/installation; the subsequent authorized speed integration added the local checkpoint/cherry-picks listed above. The latest owner scope above authorizes main delivery while leaving P424 unresolved. Do not discard the ready changes to film, Sport, AgX, or the explicitly requested P416/P418 removal.

## Exact workspace and safety constraints

- Root: `C:/Users/MECHREVO/Downloads/x200u/SCAMERA-PC`.
- **Active source: `wt-window-20261030/`**, detached HEAD; read its current `git log`. Local film commit `41430fc0103fea9d44c2948435cefcbe8d9e5474` is the earlier window-work base, not the tip after the speed integration.
- Last locally verified `origin/main`: `55c63db14c17cb7383b37aa39e29b0a9dffa0710`. Refresh before eventual delivery; never force-push.
- `project/` is another AI's migration worktree. It was not edited. Do not mistake it for the source of these processing changes.
- Read root/active `AGENTS.md`, active `.claude/skills/scamera/SKILL.md`, `C:/Users/MECHREVO/Desktop/HANDOFF.md`, active PLAN P424–P429, and root `COLLAB.md`. Current owner instructions and newer handoff override stale historical directions.
- **Never edit GLSL `kHybMergeMain1` in `app/src/main/cpp/scam-hybrid.h`.** Body SHA-256: `a43afbb4c03af969e37e6f35f218ef033021e0b76135c2d67d583c15ca7f41f1`.
- Preserve modified sources and tests; do not reset/stash/reapply archival patches. Do not weaken or disable tests.
- `tools/check_why_p404.py` has an unrelated mode-only 100755→100644 change. Leave it alone and exclude it from staging/snapshots. Do not change core.filemode globally.
- Never commit keys, local.properties, or app/version.properties. Do not read local-secrets.
- OPPO shell replay target: `fb27034c`, Find X7 Ultra PHY110, no root. The installed app, camera and settings were not altered. Phone/NDK leases are FREE; all owned remote experiment directories were removed after local SHA/size verification. No jobs remain running. Check fresh ADB/COLLAB before using it.

## Ready production portion, already in the active tree

`evidence/processing-local-20261031/README.txt` documents this portion and its exact snapshot. Do not reimplement it:

- P425 film100 isolates creative CCT/sharpen/detail/digital grain while retaining WB/calibration/technical DN; preview LUT/exposure and large-image detailRef ownership fixes.
- P426 Motion→Sport EN/RU, legacy keys preserved, shared shot-frozen low-SNR adaptive denoise. Capture and selection unchanged in this stage.
- P427/P429 AgX excess highlight saturation corrected, saturation0 neutral, monotonic chroma and skin-protection define parity. Legacy/sat1/film100 identities protected.
- Missing `clipHiUnflaggedU` upload corrected. Targets have this flag false; it does not fix their stairs.
- P428 explicitly removes P416 outer6px colour borrowing (also NLM) and P418 special edge rejection/dilation and their feature-specific keys/tests. P415/P417 retained. Do not restore the rejected edge hacks.

Checks: combined JVM 384 classes/2292 tests, 2288 pass and the same4 Windows Editor cache failures reproduced on the unchanged base; 0 errors/skips. Linux CI is still pending. AgX72 GL/144 glslc variants, 192 legacy identities, 36 define-flip bit identities; Sport/film/P417/removal/fp16 targeted checks pass. These are host/replay checks, not new image-quality validation by app photography.

Reports: `evidence/processing-release-20261031/{combined-jvm-results.json,final-review.md}` and `evidence/agx-saturation-final-20261031/REPORT.md`.

Snapshot: `evidence/processing-local-20261031/SCAMERA-processing-local.patch` plus `snapshot.json`. The patch is already applied to the active sources. Apply only to a clean exact base when transferring elsewhere. `save_snapshot.py` uses a temporary Git index, verifies the isolated apply roundtrip and preserves the real index and p404 mode change. The current snapshot hash is in snapshot.json; do not quote an older hash after editing PLAN.

That Oct31 snapshot predates speed integration. The newer combined code and transfer receipt are in `evidence/speed-integration-20261101`; use them when transferring the current complete tree.

## Target, source data, coordinate systems

Evidence root (E): `SCAMERA-PC/evidence/window-stair-review-20261030`.
Target root (T): `E/target111323-20261031`.
Paths below are relative to E or T as indicated.

Exact owner screenshot: `C:/Users/MECHREVO/AppData/Local/Temp/codex-clipboard-e371ae3b-6132-4168-81c4-d013f6b1dbf5.png`.
It matches **111323**, physical source ROI **[1792,1376,256,224]** in4096×3072. Canonical coordinates subtract(1,1). Saved original JPEG: `E/capture-audit/supplied/SCA_20261030_111323.jpg`.

Original burst: `D:/burst/Artifacts/scam_burst-20261030-111323.bin`. Lossless packed replay input: `E/replay-audit/jobs/111323-original-0-0-4096-3072/input.nch` (629149696 bytes, SHA `48258dd9bedeb49f227d5cc9f3d80c3ca191d4ade3c4f521c34360057d054e01`). 25 distinct frames:19N+3L+3ES. Plain Bayer BGGR CFA3/block1, black64/white1023, N ISO72/1.867801ms; original build31156, vivo X200U camera3.

Original native RGBfloat+flags output: same job's `output.f32`,163577856 bytes, SHA `ae9d89dd9a6b990e126f5474558f47332c1807ebe5c1c6a1c0886bfc45fd95be`.

All four relevant bursts are Oct30:111118,111305(native Quad),111323,111407. Owner said dates were approximate; do not keep seeking Oct29. Earlier target was distant windows111118/111305. Radios to the right in111118 are also requested. Source files/paired JPEGs are inventoried in E.

Ark111333 is a paired JPEG10seconds later at a different angle/exposure/tone. Ark111124 is also JPEG only; owner confirmed no Ark RAW. Use those for appearance, never as same-RAW processing ground truth.

## Critical distinction: two different contours in the screenshot

1. **Outer reflective thin metal glint**, outside the dark seal. On42curved ridge pixels±1 (126sites), actual N frame support F≥10.8, Lweight0, G is BIT-IDENTICAL afterMain1/Chroma/Rim/US/finalworker. Own N and ES G sites are unclipped. Vertical70sites have F≥12.23; a very small US delta exists there. This excludes tested late operations on the curved glint only. It does not prove Main1 has a bug or that all remaining contours are N-only.
2. **Inner white-glass→dark-seal edge**. Here curved G=.5 samples have N support median.1, Rim and ES strongly affect G, alphaUS median.725. Earlier N-only conclusions do NOT apply here. This was explicitly corrected in the latest investigation. Evaluate G=.3/.5/.7 and the visible outer glint, not just the best G=.5 statistic.

Actual read-only stage/F/gate passes reproduce all original RGB+flags/stage bytes. The first intrusive diagnostic variant that changed output is rejected evidence; use later parity-verified runs.

## Results already tried; do not repeat as blind parameter sweeps

All are evidence-only, not production fixes:

- Disabling ES/Bento loses HDR. Disabling L does not remove radio/window defects. Native fill tolerance and half-quantization bypass do not fix the target.
- SigmaAcross floor.25 and real Sabre2x→linear2x2area mean soften/widen the glint but leave breaks. Floor.25 regresses many known-GT cases.
- Axis eigenvector snap repair, common base covariance (mode6), sharpness guards, official SMAA1x, directional/morphological AA, contour dejitter and tangent Ya filters either leave breaks or erase legitimate fine geometry/text/PSF. Amplitude-only control is geometry-safer but visually ineffective.
-19N have6–8of8 fractional phase bins. CFA/centres/indexing/normalization audits found no concrete bug. Green-phase calibration on15257smooth patches found only~.1DN/+0.30% residual, far below~298DN glint range; no speculative correction added.
- Scene-fitted local δ improves heldout RAW prediction; **never ship scene-fitted geometry**. Exact packed-cell δ native replay still has target breaks.
- General extra full-resolution own-G LK improves local heldout RAW RMSE~34.5% but the exact native/fixed post still has breaks.384physical GT cases reveal grating/PSF regressions; unconditional integration is unsafe. Memory cost is also substantial. Strictzero acceptance was corrected; use field SHA `b14511989f2f6cb08c14d361f3ffac2bfb1ab6c05c668baab531fd013aca0994`, not the rejected older field.
- Actual per-site P/O/r and PC/DCov weights reproduce GPU G aggregates within2.71e−7. F is accepted frame support, NOT per-colour effective independent sample count. Unregularized LP2 improves some fullG predictions but raises noise SD1.36–2.50× and can overshoot finite strokes. Sparse126pixel LP2 post controls retain breaks and have an artificial mask boundary. They are not full-ROI reconstruction.
- Quincunx per-frame cardinal interpolation preserves first moments but loses multi-frame thin detail:66heldN0G RMSE NW.01141→cubic.03325/bilinear.04813. Text sometimes improves; ridge/truewave regress. Do not replace primary reconstruction with this.
- Cubic B-spline inverse prototype `T/cloud-local-regression-review/spline_inverse.py`: trainN1–14/tune15–18/heldN0, fixed H/F6, isotropic coefficient smoothness. FullG66siteRMSE.00922 but whole-window breaks remain and clipped/unconstrained areas have severe overshoot. Rejected, not app code.
- Single source-supported existing `s61Fine1` green-site tensor control was run on the phone using original worker/tuning plus that key only: `T/es-core-review/gate-trace-runs/fine1/output.f32`, SHA `eaca7a9c34c5a41b02f30fbbb3e5677336acb4aa725cbb3ba57a8b9a95af6e85`. Fixedpost adds regular horizontal zipper and retains curve breaks; not a fix.

Read `T/primary-estimator-review/REPORT.md`, `T/cloud-local-regression-review/REPORT.md`, `T/quincunx-cardinal-review/REPORT.md`, plus their JSON receipts before reusing numeric conclusions. `T/AA-evaluation` and `E/post-audit/smaa1x-review` contain negative detail controls.

## Latest ES/Bento and actual confidence measurements

`T/es-core-review/REPORT.md` describes exact3ES22/23/24 source projection/reduction. Actual origins/localF6/weights/clip/alpha/gain are frozen. Literal current projection closes to actual GPU within9.54e−7, reducer/stage3 within6.90e−7. All accepted source ES samples are unclipped; do not confuse N clipping with ES clipping.

Four complete packed outputs replacing only the ES core with real ARK209 or Google11, fullRGB or scalarLuma, were rendered with identical post. All retain breaks; curved G50 shifts<.1px. Late native R/B chroma median was not rerun; G late-stage identity is separately proven. See their per-output manifests.

Alpha1 control and a single predeclared smooth remap `a'=a+a*a*(1-a)` do not fix the photograph. Alpha1 worsens G30. Smooth curved G30 p95|Δ²x|.2143→.2451(worse), G50.3301→.3167, G70.2582→.2462; the outer curved glint is bit-identical. Smooth remap also has no unconditional noise guarantee. Both source-semantic flags and frozen-flags controls were kept. Do not choose a curve from one favorable statistic.

**Latest exact gate/effB reader:** `T/es-core-review/gate-trace-runs/on/gate.{f32,json}` (64float/site,14.68MB), `gate-trace-analysis.json`, and REPORT. An independent read-only GPU program leaves all9primary shader bodies unchanged. OFF/ON complete outputs match baseline SHA above. All N/post/packedalpha/a0/partial trace fields also match the previous96Ftrace byte-for-byte. Gate recomputation error≤1.79e−7; effB update closure≤4.77e−7.

- Curved inner G50 effB beforeUS median2.177, range1.135–3.089; after median2.839.
- Partial multiplier e+f=1 (e≈1,f=0), all3ES valid. Reach=reachRaw=1, own clip mark≈0; aCell median.651 and combined brightness gate median.728. It is the prepartial gate, not missing ES availability, that limits alpha here.
- Outer42glint effB before/after identical, median6.608(range5.369–12.174); alpha below actual blend threshold.
- **effB is ΣG(cover·kernelW)+base wb, NOT framecount or Neff.** Basewb is separately added. A blind effB<4 threshold is unjustified. A calibrated confidence hook is technically feasible in UsBlend binding4 without touching Main1, but no confidence policy was validated/integrated.

Source code: `app/src/main/cpp/scam-hybrid.h`, `kHybUsBlend` regionStage alpha preparation near3230 and stage3 mix/effB update near3600 (line numbers can drift). Locate symbols, don't rely solely on these numbers.

## Reproduce a controlled final comparison

Run from SCAMERA-PC root using `python` (Windows python3 alias is unreliable):

```powershell
python evidence/window-stair-review-20261030/post-audit/111323-sigma025-review/run_control.py <new-native-output.f32> --output-dir evidence/window-stair-review-20261030/post-audit/111323-NAME-review
python evidence/window-stair-review-20261030/post-audit/111323-corner-review/show_native_control.py evidence/window-stair-review-20261030/post-audit/111323-NAME-review --label NAME
```

Output dir must be a sibling under post-audit and start `111323-`. It renders actual saved WB/LSC/AE/ChannelClip/HL/WHY fusion/sharp/tone with fixed parameters; film0/dither0. It intentionally omits original finishDN/Sky and uses original snapshot post shaders. This is not exact original31156 JPEG or app-proof of the ready AgX changes.

Baseline crop native origin(1728,1312), shape352×384; target atlocal[64:288,64:320]. Baseline array: `E/post-audit/111323-corner-review/post/render/111323-current_protected-display.npy`, SHA `f0987b6f10358439d35f9cc8fe1c1db0de06c60057592ea917928ed7cd3f4aa5`. Comparison PNGs use nearest3x, no warp. Do not warp away defects or judge quarter-resolution diagnostic PFM views as native aliasing proof.

Recent full post outputs: `111323-{fullgreen,cell-exact,mode6,fine1}-review`, `111323-ES-{ARK209_085,Google11_085}-{fullRGB,scalarLuma}-review`, `111323-{alpha1,smooth-alpha}-{source-stage3-flags,frozen-flags}-review`.

## Remaining ideas: explicitly not completed

1. Distinguish individual ES frame geometry from the3ES sum, with original α/K fixed; individualES control was NOT run. `T/cloud-local-regression-review/HANDOFF.md` records the edge review's proposed next step. Cross-exposure ES local alignment has not received the fullG refinement used only forN1–18.
2. A calibrated N/ES confidence gate must use actual contributions/noise and clipping, not equate F/effB/Neff. Avoid scene-fitted arbitrary alpha curves.
3. Regularized LP2 with geometry-only equivalent-weight/noise/negative-mass constraints reached a negative initial RAW check: fullG66heldN0G RMSE.011596 versusNW.011412; off/on are also worse. White-noise SD≤1.1NW; negative mass≤.01355 in the full predictor and≤.04422 in the separately recomputed heldN0 predictor. Actual heteroscedastic noise ratio reaches1.1027 and is not covered by the white-noise bound. `T/regularized-lp2-review/{REPORT.md,QUICK-ACTUAL.json,estimates-actual.npz,check_actual.py}` contains the prototype/receipt. Extended GT/noise code exists but was NOT run after the owner requested transfer. Full-ROI exact O/P/r export was **cancelled before build/run**. Existing exact probes cover126points only.
4. Any new candidate must visibly improve the exact screenshot corner and other window/radio regions under fixed processing, retain HDR and meaningful text/PSF/noise/motion/real-wave controls, and survive actual phone worker replay. Do not port a scene polynomial, post geometric straightening, or weakened tests as a solution.

No production fix follows from the negative controls. Continue from the existing files and evidence rather than redoing all experiments or reopening unrelated camera-HAL tasks.
