# Film profiles and preview colour, 2026-11-02

Base: 5f990c7d. Research receipts are outside Git under
`evidence/viewfinder-film-20261102` and `evidence/vf-processing-20261102`.

## Findings and accepted changes

- All 20 measured colour-film IDs, eight papers and 162 scalar packet fields
  map to their own native data. No common-stock alias was found. The four
  additional monochrome models remain separate legacy models, not a claimed
  native-reference port.
- The photo uses the source-pinned native spectral renderer. Camera and settings
  previews were still using the older Java spectral model. Previews now sample
  the photo renderer into a colour LUT off the UI/GL threads. The cache has six
  entries, coalesces pending requests, rejects obsolete requests and retains the
  selected stock's legacy fallback if native rendering fails. Late tables cannot
  replace the newly selected stock.
- The RAW reference-preview branch cancels WHY AE and input-scale multiplication
  before sampling its scene table, matching the photo's unit-exposure scene feed.
  Partial-strength/custom-LUT composition follows the photo's ordering.
- The reference application's stock-selection action changes only the film ID;
  it preserves the remaining recipe. Its initial paper is Supra Endura and its
  initial DIR/grain/halation/scanner switches are off. Previously SCAMERA used a
  stock-paired paper and enabled these effects by default. Only unset values now
  follow the reference defaults. Explicit saved choices and legacy DIR recipes
  are retained. No colour difference is manufactured by adding saturation or
  arbitrary stock-specific exposure.
- The original camera's quick stock dropdown explicitly excludes Ektachrome,
  Kodachrome, Velvia and Provia. Its advanced editor lists them, with a separate
  process-mode control. A fresh original constructor plus Stock callback retains
  `Print simulation`; that synthetic slide recipe produces an inverted image.
  SCAMERA intentionally retains the valid reversal scan route and the P463 slide
  DIR correction. Copying that inversion is not a default-alignment goal.
- Photo diagnostics now include the actual stock, paper, strength, film/print EV
  and DIR amount. Older native timing lines cannot establish which stock was used.

## Numerical and device evidence

- Original Unspektrawesome 1.2.7 native engine versus current renderer: 60 cases
  (20 stocks, core/local-DIR/all-effects) on one HDR log-colour grid, and 20 stocks
  on the same real scene. Maximum component difference is 0.00048828125, one FP16
  output step at this magnitude. No nonfinite output or identical stock outputs.
- Repeated the original/current comparison for all 20 final default recipes on
  the real scene and for all 20 DISPLAY33 and SCENE41 preview grids. The same
  maximum component difference holds. Packets were exported from the actual Java
  settings code; manifests record source and input hashes.
- Production SCENE41 interpolation was compared against direct native processing
  of that same real RGB scene for all 20 stocks. See
  `preview-scene41-interpolation.json` for per-stock mean, percentile and maximum
  error in 8-bit display levels; this measures LUT interpolation, not capture or
  ISP inversion.
- An independent ART export invoked the original APK's initial constructor,
  actual Stock callback and packet serializer for all 20 colour stocks. All 20
  packets matched the independently parsed source. On the real scene, the 16
  original negative initial recipes match current defaults within 0.00048828125;
  identical original packets match both renderers within that bound for all 20.
  The four original process-0 slide recipes differ intentionally from SCAMERA's
  process-1 reversal recipes. These exports exercise the real callback, not a
  full live Compose UI session. See `original-ui-recipe-comparison-scene.json`.
- Focused Gradle tests and source template build pass. The reference-library and
  RAW-worker bytes in the local QA APK are unchanged from verified official
  CI31204. OPPO/Adreno750 logs confirm reference DISPLAY LUT generation and native
  film execution with the intended stock/paper/strength/exposure settings.

## Boundaries

The native renderer comparisons isolate the film operation. Different RAW
development, white balance, capture exposure, chosen paper and custom LUT can
still change the final photo. On a mostly neutral real scene, Portra 400 and
Gold 200 are close even in the original native engine; that is not evidence of
stock aliasing. The supplied JPEG pair lacks the native film packet metadata.

The colour cube excludes spatial grain, halation, scanner blur and DIR diffusion;
local DIR remains active when selected. Existing spatial preview effects are
approximate. An ISP preview's unknown tone mapping cannot be perfectly inverted.
Unsupported Vulkan devices retain their fallback. No complete pixel identity
between a live ISP preview and a multi-frame developed photo is claimed, and no
physical Mali validation was performed in this continuation.

The original Java state-store and copied-model writers show no stock-triggered
process reset in the reviewed graph. Saved or imported recipes can set process
and DIR explicitly. All-profile parity requires matching the actual process,
paper and complete recipe in the original; a stock label alone is insufficient.

Viewfinder responsiveness during heavy film processing remains a separate open
task. The faster no-effect default recipe is not counted as an equal-recipe
processing-speed improvement.
