# SCAMERA iOS: plan and findings

Separate iOS app in `ios/`, built by `.github/workflows/build-ios-ipa.yml` (XcodeGen → `xcodebuild archive` → IPA +
`SHA256SUMS.txt`, artifact `SCAMERA-iOS-<version>`, 7 days). Without the `IOS_*` secrets the IPA is unsigned
(`-unsigned.ipa`, re-sign with Sideloadly/AltStore): `-exportArchive` needs an identity, so the unsigned IPA is the
archived `.app` in `Payload/`.

## Architecture (Apple AVCam sample, `LICENSE-AVCam.txt`)
- `Capture/CaptureService` — `actor` on its own `DispatchSerialQueue` executor, the only owner of `AVCaptureSession`.
- `Capture/PhotoCapture` — `AVCapturePhotoOutput` + delegate → `async` via `withCheckedThrowingContinuation`.
  `capturePhoto` runs inside `SCAMObjC.catchException` (`Bridge/`): invalid settings raise `NSException`; the
  next candidate setting is tried and the refusal is logged (`capture … refused: …`).
- `Capture/PreviewView` — `UIView` with `AVCaptureVideoPreviewLayer`, connected through `PreviewSource/PreviewTarget`.
- `Capture/LensModules` — modules like Android `ModuleRegistry`: physical back lenses (ultra wide, wide, tele) +
  one ISZ module per `secondaryNativeResolutionZoomFactors` entry of the lens's largest-still format
  (`videoZoomFactor` = that factor), front lens. Labels from the field of view against the wide lens.
- `Gallery/MediaLibrary` — one `PHAssetCreationRequest`: HEIC/JPEG `.photo`, DNG `.alternatePhoto`
  (`shouldMoveFile`); add-only access.
- `Support/SessionLog` — `Documents/Log/log-YYYY-MM-DD.txt` (7 days, also visible in the Files app), header
  `I/ScameraBuild: SCAMERA iOS <version> (<build>) <sha> <iPhoneNN,N> <iOS>`; shared from the camera screen.
- Interface strings: `Resources/{en,ru}.lproj`, checked by `tools/check_strings.py` (also bans foreign brand names).
- Minimum iOS 17 (`RotationCoordinator`, `isResponsiveCaptureEnabled`, `DispatchSerialQueue` executor).
  Swift 5 language mode.

## Capture choices (stage 1)
- `.photo` preset; if another 420f format at ≥29 fps has larger stills, it becomes `activeFormat` (logged).
- `isAppleProRAWEnabled` is set before `startRunning` when supported.
- «Полное разрешение» on: `maxPhotoDimensions` = the largest of the active format; off: largest ≤12.7 MP.
- RAW: Bayer → `AVCapturePhotoSettings(rawPixelFormatType:processedFormat:)`, ProRAW as fallback, then HEIC only.
  RAW requests use `.balanced` priority, HEIC-only `.quality`.
- Bayer RAW with `videoZoomFactor ≠ 1` (ISZ modules) is reported to raise `NSException`; not assumed: the attempt
  is caught and logged, ProRAW is taken instead.

## Log lines to send from the phone
`Modules`, `Probe` (device/format/exposure/formats per module; photoOutput raw formats, maxPhoto, bracket max,
ZSL/responsive), `Capture` (module, format choice, deviceZoom), `Shot` (`resolved photo= raw=`, `raw at … buffer=WxH
<fourcc>`, `processed at …`, `saved in … ms, memory … MB`), `capture … refused`.
To fill per iPhone model: Bayer RAW and ProRAW size on every module and ISZ factor; whether ISZ is a true crop.

## Findings from the Android code (2026-11-13)
- There is **no CPU twin of the RAW merge**. The merge (alignment field, rejection, Sabre accumulation, Bento,
  Shasta, native mosaics) exists only as GLES 3.1 compute (`kHyb*` strings, `HybridGpu` in `scam-hybrid.h`,
  `FastGpu` in `scam-fast-gpu.h`). P531/P564 «CPU twins» are post passes on the merged RGB (`hybridAlongEdge`,
  `hybridLatticeNotch`, `mosaicChromaMedian`). So the Bayer merge on iOS = Metal port of `HybridGpu`'s passes,
  checked against the GLES output on llvmpipe (host checks) on identical inputs.
- GL-free and portable as is: `scam-yuv-merge.h` (`scam_yuv::mergeBurst`, RGB/YUV burst → linear RGB float),
  `oldstyle-tone-core.h` + `third_party/tone_port` + `tone_supplement` (ScamMaster 2.7/3.1), `scam-homography.h`,
  `scam-preprocess.h`, `scam-merge.h`, `scam-superres.h` (`superResolveReference`, old CPU reference merge),
  `scam-portable.h` (`portableDemosaic`), `scam-guide.h`, `scam-dcg-noise.h`, `scam-bayer-quads.h`.
- Not portable: `scam-crash.h` (Linux ucontext/prctl), `scam-fast-gpu.h` (EGL, AHardwareBuffer), the worker `main`
  (`scam-neural-worker.cpp`, QNN/CRE dlopen).
- Why tone V2 = `why/v2_clean → v2_stride (AE on CPU, WhyV2Ae) → WhyLumaSharpen (sharp_in/r1/box/compose/rl) →
  v2_bracket_dn/v2_hr_* (off by default) → v2_ef_prep/pyr_down/ef_coarsest/ef_collapse → v2_tonemap`; CPU parts
  `WhyV2Ae`, `WhyV2Curve` port to Swift; `tools/check_why_v2_tone.py` has a numpy model of the tonemap.

## GPU first (owner, 2026-11-13)
Owner: «Обработку лучше делать на гпу, чем на цпу». All image processing on the phone runs on Metal compute:
the RAW merge (port of `HybridGpu`), the RGB merge (`scam_yuv::mergeBurst` moved to Metal), Why V1/V2, denoise,
sharpening, colour. C++ CPU code (`scam_yuv`, `superResolveReference`, CPU twins) is built only into XCTest as the
reference the Metal passes are compared against on identical inputs. Exceptions stay on the CPU where Android has
them there too: AE histograms of `WhyV2Ae` / `WhyV2Curve` (small readback) and ScamMaster `tone_port` (owner's
C++ code, bit-exact hashes required).

## «Why» V2 on Metal (2026-11-13)
- `Engine/WhyV2.metal`: `why_v2_clean`, `why_v2_stride`, `why_v2_luma` (sharp_in REF_LUMA), `why_v2_ef_prep` (o_G and o_W
  in one pass), `why_v2_pyr_down`, `why_v2_ef_coarsest`, `why_v2_ef_collapse`, `why_v2_tonemap` — the GLSL one to one;
  `MTL_FAST_MATH = NO`. `Engine/WhyV2Ae.swift` = WhyV2Ae + WhyV2Curve (XCTest = Android WhyV2ToneTest cases).
- Input until the RAW merge is on Metal: the DNG through `CIRAWFilter` (GPU) with `boostAmount 0`, gamut mapping off,
  local tone map 0 → linear extended sRGB, white balanced (as v2_clean expects; matrices identity, lsc 1) in an
  IOSurface 64RGBAHalf buffer viewed by Metal without a copy. AE noise: the reference's fallback S 0.00015 / O 1e-5
  (no camera noise profile read yet). Clip ceiling 1 (no short frames).
- Not yet: WhyLumaSharpen (the detail transfer reads the unsharpened reference luma, `sharpen=off` in the log),
  film LUT, P3 output. Output 8-bit (QUANT8), HEIC q 0.92 with the camera's EXIF, orientation 1.
- Log: `Engine` lines `decode …`, `WHY V2 stats …`, `WHY V2 fusion …`, `WHY V2 tone …`, `encode HEIC … total ms= memory`.

## The merge on Metal: ScamGL (2026-11-13)
The SHDR merge is the Android C++ as is (`app/src/main/cpp/scam-*.h`): its GLES 3.1 compute runs on Metal through
`ios/ScamGL` (EGL / GLES subset over Metal command buffers; GLSL → glslang (Vulkan relaxed rules, shifted binding spaces)
→ SPIRV-Cross MSL at link time, fast math off; user functions prefixed, `level` & co. renamed). The only shared-C++
change: `scam-crash.h` keeps only the stage marks on Apple.
- Check: `ios/tools/hybrid_replay.cpp` (the worker's `hybridReconstruct` call, cre-off) on the 5 burst crops of
  `replay/p335`, Mesa llvmpipe vs ScamGL on the macOS runner's GPU (CI jobs merge-reference / merge-metal, limit 80 dB):
  d101423a 104.95 dB, d101423b 103.58, e193420 109.43, n124513a 92.66, n124513b 97.49 (max |Δ| ≤ 0.6 % of p99.9,
  mean ~1e-6) — float rounding. Before the `level` rename the GPU alignment did not build (fallback to the corner
  tracker): 27–37 dB, so the replay catches path differences.
- Full bursts: `ios/tools/full_bursts.txt` (owner's Drive, BURSTS_ALL) → `.github/workflows/ios-burst-replay.yml`.
- Not yet: render programs (the «Быстрый» route's fragment passes with blending), program binaries (always compiled).

## SHDR on the iPhone (2026-11-13)
- In the app: `Bridge/ScamMerge.mm` (`SCAMMerge`) runs `Engine/ScamMergeCore.hpp` on ScamGL; XCTest `MergeTests` merges the
  n124513b crop on the simulator's GPU: 97.49 dB against Mesa (CI bundles the crop and its Mesa merge).
- Capture (`Capture/BayerBurst.swift`): `AVCapturePhotoBracketSettings` of up to `maxBracketedCapturePhotoCount` Bayer
  frames at bias 0, repeated to N (default 8), then the ultrashort / bracketed frames of the plan (see «SHDR brackets and crop»
  below); frames copied from the 14-bit Bayer pixel buffers (CFA from the format).
- `Engine/NchWriter.swift`: the burst as NCH v12 (Android's transport, every plane on its own page, capture order) in
  Documents/Bursts/last.nch (Files app) with its capture facts (last.nch.json) — the same file replays on a PC against the
  Android merge. Noise: DNG NoiseProfile, else estimated from the first two N frames (green sites, 32 px tiles, lower half of
  the variances), at each frame's ISO.
- Colour after the merge (`Engine/DngColor.swift`, `Camera.metal`): AsShotNeutral white balance, ForwardMatrix (else
  inverted ColorMatrix + Bradford) interpolated by CCT → linear sRGB → «Why» V2 → HEIC with the sensor orientation.
  Not Android's colour chain yet (ScamRgb, CCT matrix, lens shading, highlight recovery): the next port.
- To check on the phone: log tag `SHDR` (burst timing per request, levels, noise source, merge lines, total ms, memory).

## Full pipeline parity with Android (owner, 2026-11-13: «итоговая обработка фул пайплайна совпадала с Android»)
- Merge: identical (the Android C++ on ScamGL; 92–109 dB vs Mesa, 100 dB on the full «Лента» burst).
- Post: the Android GLSL runs unchanged on ScamGL render programs (all 119 post shaders link on Mesa and on Metal,
  tools/post_shader_check.cpp; preview/ and editor/ are assembled at run time and checked with their ports). The Java
  orchestration (PostPipeline and its nodes, GLProg / GLTexture / GLFormat, Parameters, ShotLook, settings) is ported to
  C++ one to one (Engine/post/), so the same port runs on Mesa (host reference, CI) and on the iPhone (ScamGL).
  A Java → ObjC translation (J2ObjC) was checked and dropped: PostPipeline pulls 472 app files (UI, gallery) and ~40
  Android packages.
- Ground truth for the whole pipeline: the Drive bursts (.bin + .json with params / prefs) rendered on Android (Lab,
  ScamLabBurst) → JPEG; the same burst through the iOS port in CI → compared.
- Order: GLProg/GLTexture/GLFormat/Node/PostPipeline core → ScamRgb (+ hlrecovery, clipband) → ScamHdrDenoise /
  WhyResolveDenoise → WhyMsDenoise → Why V1 (WhyStats, WhyFusion, WhyLumaSharpen, WhyCombine; the default) → Why V2 →
  RotateWatermark → ShotExif + scamera-jpeg / HEIC → ScamMaster (tone_port) → film / lens filters → Ultra HDR.
- Then the Android camera screen and settings in SwiftUI with the same layout, icons (tools/icons) and strings
  (res/values, values-ru), the gallery and the editor.

## Post port progress (update as nodes land)
- 2026-11-13: the whole default SHDR photo route is in C++ (Engine/post) and in the app:
  ScamRgb → ScamHdrDenoise → WhyHlDezipper → WhyMsDenoise → WhyStats (early WhyLumaSharpen) → WhyFusion → WhyLumaSharpen →
  WhyCombine → RotateWatermark. On the Lenta burst (4096x3072, 20 N), against the Android app's own Java nodes and shaders on
  Mesa (tools/android-host, Robolectric): every node and the final photo 0 of 37748736 values differ.
  - Shot parameters: Engine/post/color/ShotColour.hpp (Parameters.ReCalcColor, DNG route: the neutral, P370/P580 fallbacks,
    DngColor white estimate, FM/CC chain to ProPhoto, placeholder / missing FM routes, «Профиль матрицы» / «Своя матрица»);
    Engine/post/DngShot.hpp (the iPhone DNG's tags: CM/FM/CC/illuminants/AsShotNeutral/NoiseProfile, OpcodeList2 GainMaps as
    LENS_SHADING_MAP, levels, CFA, orientation, EXIF). tools/shot_params_check: the colour of Lenta bit for bit, the DNG
    round trip (tools/make_test_dng.py) gives Android's Parameters.
  - Merge tuning: Engine/post/HybridTuning.hpp (PreferenceKeys.hybridTuningText: 115 lines identical on Lenta's prefs;
    ScamHybridBurst.tuningOverride for a plain Bayer burst: mosaicRemosaic, usSceneEv, wbR/wbB).
  - Engine/post/ShotRun.hpp + Bridge/ScamShot.mm: NCH header flags (clip flags, effective map), job markers (rgb-half,
    hl-mask), merge, worker output read as ScamNeuralClient, Parameters, PostPipeline.Run. BurstProcessor uses it; the
    shaders are bundled from app/src/main/assets/shaders.
  - CI: .github/workflows/ios-post-check.yml (Android host reference on Mesa, the port on Mesa bit for bit, the port on
    Metal within PSNR 40 dB, the app's whole shot on Mesa). Metal (ScamGL, the iPhone path) on Lenta: PSNR 53.9 dB against
    the Android photo, mean 0.24 / 255, 16130 of 37.7M values off by more than 2, max 15; AE 1.2247 vs 1.2261, sky 35.4 %.
    The app's whole shot on Mesa (tools/shot_run_check, Lenta + its tags as a DNG): merge 35 s + post 13 s (CPU Mesa),
    PSNR 42.8 dB to the lab photo - the shot's own tuning lines (usSceneEv, wbR / wbB) change the merge as on a live Android
    shot. ScamGL fixes found by it: fragment outputs padded to the target, glReadPixels missing components 0 / alpha 1,
    glClearBufferuiv / iv.
  - 2026-11-13: the tail of PostPipeline.tail and the Sabre output modes. nodes/ScamLocalContrast.hpp («Текстура»; GLUtils.hpp:
    createPyramid / gaussianDownsample / pyramiddiff, the 96x72 noise-floor read-back), nodes/HybridFinalResize.hpp (the four
    downsamplers), PostPipelineRun.hpp builds them as tail() does. HybridOutput.hpp: ScamHybridBurst.sabreSize / resizeTarget /
    megapixelSize / sabreWords / sabre2xFits / transportHalf, PreferenceKeys.hybridFinalSize and the constructor's output plan
    (outWidth / outHeight, finalWidth / finalHeight, strictTwoX); Settings.hpp: routeInternalValue, hybridOutputMode,
    hybridDownsampler, hybridResizeMode / Custom. ShotRun.hpp writes header flags 8 / 16 of the merged size, word 13 (grid) and
    words 15 / 16 (the 1.5x size), the burst's "grid 2 / auto2x 0" lines for 2x, the rgb-half marker of transportHalf, and
    Parameters.outputScale / hybridFinalSize as ScamProcessor sets them. Lenta variants against the Android reference on Mesa
    (post_node_check full): «Текстура» 0 of 37748736 values differ; «Уменьшить до» 8 MP (3264x2448) with lanczos / bicubic /
    area / bilinear each 0 of 23970816; Sabre 1.5x (6144x4608, «Текстура» on the 2x pyramid, e = 1) 0 of 84934656;
    Sabre 2x strict (8192x6144 merge, «Текстура» e = 1, «Уменьшить до» 12 MP: lanczos 8192x6144 -> 4096x3072) 0 of 37748736;
    Lenta itself still 0 of 37748736. The app's whole shot (shot_run_check, Sabre 1.5x + «Текстура»): header words 13 / 15 / 16
    = 2 / 6144 / 4608 as Android, photo 4608x6144, PSNR 42.0 dB to the lab photo (the shot's own tuning lines).
    Host harness: HostPostRender reads the worker output at header words 15 / 16 (as ScamNeuralClient reads
    ScamHybridBurst.outputWidth; ScamLabBurst takes width x grid, so the Android lab cannot replay a 1.5x burst), and
    ShadowTileBlitter takes the tile readback above 16 MP (Robolectric's legacy Canvas left those photos black). post_node_check
    takes words 15 / 16 too and the nodes ScamLocalContrast / FinalResize.
  - 2026-11-13: the «Why тон» V2 route (pref_scam_hybrid_tone_version "v2", grids up to 100 MP): why/WhyV2Ae.hpp (WhyV2Ae /
    WhyV2Curve maths, Java float semantics incl. String.format "%.4f" half up, WhyV2State), nodes/WhyV2Stats.hpp (v2_clean,
    v2_stride samples, settings incl. the merged V1 rows, NoiseModeler.reportedMean: Parameters NoiseModel.reportedModel),
    WhyV2Highlights.hpp (bracket_denoise, hr_extract / box / hr_apply), WhyV2Fusion.hpp (ef_prep, pyr_down, coarsest,
    collapse), WhyV2Tone.hpp (v2_tonemap, QUANT8; no P3_OUT / FILM_LUT, as WhyCombine's port), WhyLumaSharpen REF_LUMA guide;
    PostPipelineRun builds WhyV2Stats / WhyLumaSharpen / WhyV2Highlights / WhyV2Fusion / WhyV2Tone + tail(). Lenta against the
    Android reference on Mesa (post_node_check full): V2 0 of 37748736 values differ; V2 + «Текстура» 0; V2 with «Восст. цвета
    светов» 0.8, clarity 0.5, the own curve + lift -10 / contrast 20 0; V2 Uchimura + vibrance 0.3, skin hue 50, sRGB gamma 0;
    V2 + «Уменьшить до» 8 MP lanczos 0 of 23970816. Lenta / «Текстура» V1 still 0. Not exercised by Lenta: bracket_denoise (it
    runs only with merged short frames, the ceiling 8; Lenta's shorts were all rejected).
  - 2026-11-13: «Oldstyle» («ScamMaster Тон», pref_oldstyle_mode). oldstyle/OldstyleAe.hpp (OldstyleAe: meter, plan27 / plan31 /
    capCaptureRatio / fusionGains, the plan; OldstyleStyle; OldstyleMode / ShotLook.Oldstyle readers: version, V2 levels, the tone's
    rows with OldstyleMode.value's default / range rules, pref_oldstyle_style_*), nodes/OldstyleTone.hpp (OldstyleTone: v2_clean /
    v2_stride samples, the version by memory - os_proc_available_memory on the iPhone, oldstyle/linear RGBA16I read back in strips,
    the module, oldstyle/rgb8; OldstyleStyleNode: oldstyle/adjust). The tone itself is the owner's tone-port module unchanged:
    oldstyle/ToneNative.{hpp,cpp} = oldstyle-tone-jni.cpp's setThreads / render without the JNI on
    app/src/main/cpp/oldstyle-tone-core.h; project.yml compiles third_party/tone_port/src and adds its include path; host tools take
    oldstyle/ToneUnity.hpp (they need -Iapp/src/main/cpp -Iapp/src/main/cpp/third_party/tone_port/include; without them they build
    as before and an Oldstyle shot stops at its tone). tools/android-host/run.sh builds libscameraTone.so for the Android reference
    (OldstyleNative.load). Lenta against the Android reference on Mesa (post_node_check full): V1 neutral 0 of 37748736 values
    differ; V2 + style (tone 35, colour -40, exposure 20, grain 2, vignette 40, sharpness 30) 0; V1 + style (warmth, hue,
    highlights, shadows, aura, halo, boosts) + curve mid 0.3, out saturation 0.2, HDR on 0; V2 without HDR (oldstyle_hdr off), 8
    levels (postview), dehaze off, V2 saturation 0.2: 0. The app's whole shot (shot_run_check, Oldstyle V2 + style) runs: PSNR 44.6
    dB to the lab photo (the shot's own tuning lines). Not on iOS: the mode's capture (manual row, burst frames), its viewfinder
    tone and the style palette UI (the style rows are read when stored; unset = neutral).
  - 2026-11-13: Ultra HDR (pref_ultrahdr_key, the top row). UltraHdr.hpp: PostPipeline.RunHDRGainMap (ultrahdr/sceneluma with the
    lens shading, the render's luma weights and the sky ratio; GLHistogram medians; ultrahdr/gainmap; gainmax / gainquant on the
    GPU) and GainMapComputer (range, requantisation, CPU normalisation, Result: GainMapMin / Max, HDRCapacityMax);
    nodes/LinearExposure.hpp (the linear snapshot before the tone, at the final size by the "area" resize; halves on the CPU);
    WhyCombine's sky ratio (skyHdrRatio, why/skyratio). Run as ScamProcessor runs it after PostPipeline.Run; iOS takes the switch
    alone (Android also wants a JPEG: the iPhone writes the map into JPEG and HEIC). HostPostRender runs RunHDRGainMap +
    GainMapComputer when the shot's settings turn Ultra HDR on (gainmap.rgba, gainmap.txt) and post_node_check full compares map and
    metadata. Lenta on Mesa: V1 (sky 35 %, ratio 512x384) photo 0 of 37748736, gain map 0 of 50331648 values, min 0 / max 3.312 /
    capacity 3.312 as Android; «Why тон» V2 photo 0, map 0, max 3.048 as Android; Oldstyle V2 + «Уменьшить до» 8 MP lanczos (the
    snapshot by the area resize, 2448x3264) photo 0 of 23970816, map 0 of 31961088, max 2.664 as Android. shot_run_check with Ultra
    HDR prints the map (3072x4096, max 3.336 with the shot's own tuning lines). App path: ShotRun.hpp ShotOutput.gainMap,
    SCAMPhoto.gainMap (one byte a pixel) + size, min, max, capacity; ShotProcessor.encode writes it with
    CGImageDestinationAddAuxiliaryDataInfo(kCGImageAuxiliaryDataTypeISOGainMap) on iOS 18 (data + description: width, height, bytes
    per row, OneComponent8; metadata: the hdrgm XMP of UltraHdrContainer / IsoGainMap); iOS 17 saves SDR. Not checked on a device:
    whether ImageIO takes the ISO metadata from the hdrgm XMP of the auxiliary info.
  - 2026-11-13: film simulation, «Плёночный LUT» and lens filters. color/FilmSim.hpp (FilmSim: stocks / prints / formats,
    Settings, current(), lutKey, our B&W Model, FilmAdvanced's halation / LUT-token parts; FilmSpectral: the SFD1 arrays of
    assets/film, Film / Paper / Scan / Engine, double maths as the Java), color/FilmLut.hpp (FilmLut .cube parse + 3D RGB16F upload,
    FilmLooks, FilmSim.lut / lutScene / grainLut, FilmChain current / lut / scene / compose, FinishedLook), color/LensFilters.hpp
    (LensFilters ids / Tune / Choice.now, DiffusionModel, DiffusionGauss.plan), nodes/LensFilter.hpp (lens filter + FilmDiffusion),
    FilmHalation.hpp, FilmGrain.hpp, ReferenceFilm.hpp (ReferenceFilm, ReferenceFilmMix, FilmReferenceParameters, the engine as a
    hook); WhyCombine FILM_SIM / FILM_LUT / filmOnly gates / detailGain, WhyTone cctAmount, WhyLumaSharpen filmShare / neutral
    guide, WhyV2Tone FILM_LUT, ScamRgb landingDrawn, PostPipelineRun takeFilm / lightNodes / routes / tail grain / skipNode /
    gainMapWanted. GLProg.setTexture3D; ScamGL glTexImage3D / glTexSubImage3D and sampler3D binding (Metal, not run here).
    Lenta variants against the Android reference on Mesa (post_node_check full), each 0 of 37748736 values differ: Portra 400 with
    grain (grain_sf), halation 150 + softness (scatter), Ultra HDR asked (off with a film: no map on both); V2 chosen + Velvia 100
    at 60 % with «Кино 500» at 70 % on its print, 645, exposure / contrast / DIR (V1 used); Tri-X 400 (our B&W model, classic grain +
    MTF blur, 16 mm); Oldstyle V2 + Vision3 250D on Vision Premier 2393, format off, print EV / M / Y (the Why tone used); Black
    Pro-Mist 60 % with its tune alone; Gold 200 with its Pro-Mist 2.0 diffusion + CineBloom lens filter + halation (Super 35);
    «Тёплая 400» at 70 % on V1, «Слайд 100» on V2. Baselines still 0: lenta, texture, v2, os2, uhdr (map 0 of 50331648).
    The reference film route needs spektrafilm's Vulkan renderer (libscameraFilm): with android-host/run.sh SCAM_HOST_FILM=1
    (Mesa lavapipe, film_host_bridge.cpp) and post_node_check SCAM_FILM_LIB=<that library, built the same way>: Portra 400 at 100 % on V1 (grain,
    halation, softness; engine 180 passes / 22 tiles) 0 of 37748736; Velvia 100 at 70 % on V2 with «Кино 500» 60 % (reference_mix
    FILM_LUT) 0 of 37748736; without the engine the C++ differs (PSNR 24.9 dB), so the check discriminates.
    2026-11-13 the engine on the iPhone through MoltenVK (Vulkan on Metal): tools/build_moltenvk.sh builds MoltenVK v1.4.1 from
    source (fetchDependencies --ios --iossim; --macos for the check) and the host glslangValidator (glslang 15.1.0), cached on the
    script (build-ios-ipa.yml job moltenvk). The app compiles SpektraVulkanRenderer.cpp / SpektraGeneratedProfileCurves.cpp
    unchanged (fp-contract on, as the NDK), links libMoltenVK.a directly (no loader), and a build phase compiles the 11 .comp to
    SPIR-V (Vulkan 1.0) into the bundle's "spektrafilm" with the two float tables (read through SPEKTRAFILM_RESOURCE_DIR; Android
    embeds the same files). nodes/SpektraFilmEngine.hpp is the JNI's availableWithQueue / renderWithQueue (renderer cached by folder
    and queue policy, the probe = the engine's own init, FilmReferenceEngine::probe); Bridge/ScamFilmEngine.mm installs it at launch;
    ReferenceFilm's budget uses os_proc_available_memory as Android's availMem (memory headroom check included). No shared C++ change.
    Checks (ios-post-check.yml): Portra 400 V1 and Velvia 100 70 % V2 + «Кино 500» rendered by the Android pipeline with the engine
    on lavapipe; the C++ port with SpektraFilmEngine on lavapipe and the Android build's SPIR-V: 0 of 37748736 (both); with the
    glslangValidator SPIR-V the app bundles: 274 / 100 values differ by 1 (glslc -O's code vs unoptimised, PSNR 99.5 / 103.9 dB);
    the macOS job renders both on MoltenVK (Metal, the runner's Apple Paravirtual device, argument buffers) against the lavapipe
    reference: Portra 400 PSNR 63.1 dB (70 passes / 8 tiles: Metal's larger storage limit), Velvia 100 58.3 dB (the post port on
    ScamGL alone: 53.9 dB); bound 50 dB. CI: on lavapipe 0 of 37748736 for both (renderer compiled as run.sh's library; a 100 dB
    bound). The film's look on a real iPhone is not measured yet. Not ported: the editor's /
    viewfinder's reference LUT (FilmPreviewLut through the engine): the editor still shows the first-phase table.
    Settings: App/FilmSettings.swift (FilmSimPage: film / print / format / effects, FilmLutPage with «Импорт .cube» into
    Documents/LUT, LensFilterPage with the tune) behind SettingsRow's custom rows; Bridge/ScamFilm.{h,mm} (the catalogues, the LUT
    folder, the cards' CPU look). project.yml bundles app/src/main/assets/film as "film".
  - 2026-11-13: «Портрет» (pref_portrait_enabled / pref_portrait_skin_warmth; Parameters.portraitFaceDetected / portraitFaces).
    portrait/PortraitSkin.hpp (PortraitSkin.measure / shift / sceneNeutral / faceLuma / faceAwb, PortraitFaces.crop / append; Java
    float semantics, JDK toDegrees / toRadians), portrait/PortraitGpu.hpp (portrait/sample.glsl 192 px sample, RGBA32F read-back;
    measureScene with CAMERA_SOURCE for ReferenceFilm); WhyStats: portraitOffsets (AE target +0.05, dark pixels +0.5), the skin
    measure on whyLow (adjust zeroed without skin warmth), facePriority («Приоритет лиц в AE»); WhyCombine: PORTRAIT
    (portraitFacesU / AdjustU / MapU), «Маска лица» (why/facemask MODE 0 + two cross-bilateral passes, FACE_MASK) and Face AWB
    (FACE_AWB). V2 / Oldstyle read no portrait field (as Android). App: Engine/PortraitFaces.swift (Vision VNDetectFaceRectanglesRequest
    on the burst's processed HEIC at 1024 px, its EXIF orientation, frames of another aspect refused; fallback a grey of RAW frame 0;
    confidence >= 0.5, the five largest; boxes turned into the RAW buffer by the DNG orientation) → BurstProcessor (only with
    «Защита лиц» on) → SCAMShot faces / facesDetected → ShotRun ShotFaces → Parameters (PortraitFaces.crop to the frame).
    Lenta variants (faces in sensor orientation in meta.json params, ScamLabParams.fromJson reads them) against the Android
    reference on Mesa (post_node_check full), each 0 of 37748736 values differ, the skin logs identical: V1 three faces (soil
    mound: hue 8.5, turn 10, shift +0.0325; dry grass hue 65.5; a 3-sample face unmeasured) 0; skin warmth off 0 (67701 values
    differ from warmth on); «Маска лица» all seven sliders + portrait_awb 0.7 0; detected without boxes 0; one face with Face
    AWB -0.0110 / +0.0233, mask size 125 / skin 60 / shown, AE 1.4621 -> 2.9243 0; a yellow car (turn -4.66, AE priority 0.8:
    1.4621 -> 1.8035, Face AWB +0.0291 / -0.0074) 0; V2 with faces 0 (= V2); Ultra HDR + mask photo 0, gain map 0 of 50331648;
    Oldstyle V2 + faces 0. Baselines still 0: lenta, v2, os2, uhdr (map 0). Not ported: ReferenceFilm's skin warmth (the
    spectral film with a face and skin warmth still throws in ReferenceFilm.hpp; PortraitGpu::measureScene is its measure);
    Vision's boxes are not Camera2's (tighter, from the lens-corrected HEIC): not checked on a device.
  - Not ported yet (they throw): DCG noise zones, the spectral film's skin warmth (Portrait). Not on iOS: the film's EXIF recipe (Parameters.filmRecipe), the live
    viewfinder's film / LUT / lens filter.
    Transport: the ScamHybridBurst frame rules in C++ (Engine/post/HybridSelect.hpp, see «SHDR brackets and crop»), per-frame
    noise from the DNG profile at the frame's ISO.
  - The iOS settings screen writes the Android keys (Engine/PostSettings.swift, UserDefaults "scam.prefs"); unset keys take
    the Android defaults.

- Done: ScamGL render programs + format conversions; Engine/post/GLShaderSource.hpp (GLInterface.readProgram),
  Engine/post/GLCore.hpp (GLFormat, GLTexture, GLProg, TileSize rows); tools/post_shader_check (119 / 119 shaders on Mesa
  and Metal); tools/post_pass_check (Why V2 passes Mesa vs Metal in CI).
- Next: Engine/post/PostPipeline.hpp (GLBasePipeline main1/main2 ping-pong, Node, runNodes, final output readback),
  Parameters (the fields the nodes read: rawSize, whitePoint, sensorToProPhoto / proPhotoToSRGB, gainMap / mapSize,
  blackLevel, whiteLevel, noise, cameraID, outputScale, mosaicBlock, scamRgb), settings (PreferenceKeys.hybridValue /
  hybridSwitch / scamDevSwitch defaults from res/xml + the burst .json prefs), then ScamRgb.run (default paths:
  half RGB upload, channelClip, hlrecovery chanprep/reduce/opposed, purplebound, scamrgb, clipmask/clipband, suncore).
- Each node: a host check (Mesa) and the Metal run in CI on the same input; the Drive bursts for the whole chain.
- The burst's .json (ScamBurstDump) holds the Android Parameters of the shot (CCT.matrix, ColorMatrix1/2, ForwardTransform1/2,
  calibrationTransform1/2, sensorToProPhoto, proPhotoToSRGB, whitePoint, gainMap + mapSize, blackLevel, whiteLevel,
  noiseModeler, rawSize, cameraRotation, mosaicBlock, ...) and all its SharedPreferences (513 keys, ["s"|"b"|..., value]):
  the replay feeds the port exactly what the phone's PostPipeline read. On the iPhone the same Parameters are filled from
  the DNG / AVCapturePhoto metadata (the DNG model is Camera2's: CM, FM, CC, illuminants, neutral, noise profile).

## Stages (estimates are working sessions)
1. ✅ 0.1 skeleton, modules/ISZ/48 MP, RAW (Bayer/ProRAW) next to HEIC, probe log, IPA workflow.
   Next in stage 1 (2–3): Metal engine (MTLDevice, compute pipelines from `Engine/*.metal`, RGBA16F textures);
   Why V2 on Metal (v2_clean → v2_tonemap, AE in Swift) checked against `tools/check_why_v2_tone.py`; RGB merge on
   Metal with `scam_yuv::mergeBurst` as the XCTest reference; burst of N frames (`AVCapturePhotoBracketSettings`,
   same exposure); 48 MP module on the RGB merge with timing vs single ProRAW; Obj-C++ bridge for `tone_port`.
2. (3–5) Metal port of `HybridGpu` (Bayer RAW merge), ES/L bracket, post pipeline on Metal (denoise, sharpen,
   colour), manual row (ISO, shutter, focus, WB).
3. (2–3) ScamMaster AE and tone, front camera and live viewfinder tone (if confirmed), merge/denoise/lens settings.
4. Gallery and editor.

## Gallery and editor (2026-11-13)
- Editor render (Editor/*.hpp, header-only C++, Java float semantics, -ffp-contract=off): EditState (Android JSON), EditMath
  (uniforms, tone / point-curve tables, radii, overlap), CropMath, EditRenderer + EditGl (the Android shaders/editor GLSL
  unchanged: demosaic, 512 px proxy + guided filters, nr, develop, gauss2, main, finish), DngRaw (DngPreview's TIFF reader,
  Lj92Decoder, DngRaw with Quad / bin / gain maps / auto lift, DngColor), EditExport (EditSource.RawColour, the readers,
  EditExporter.Plan / region / tile loop, EditorView's canvas / finishFor / histogram). ScamGL gained glGenerateMipmap (blit).
- Check (tools/edit_check.cpp vs tools/android-host HostEditRender on Mesa): a 1280x960 crop of «Лента» (picture route) and two
  DNGs mosaicked from it (tools/make_edit_dng.py: a.dng uncompressed RGGB, orientation 6, ForwardMatrix, DefaultCrop, 4 GainMaps;
  b.dng LJ92 2-component, BGGR, ColorMatrix only, 2x2 black pattern) x 6 EditState cases (default; tone + NR + sharpen + grain +
  vignette; HSL mixer + point / parametric curves + grading + turn / straighten / crop; mono + B&W mix + negative dehaze + flip;
  negative texture / clarity, full NR; exposure + WB + 30° straighten), each as the 640x480 view frame, the crop tab's frame,
  the histogram render and the full-resolution copy (2 tiles at 1280; odd cases at outMax 1 MP): 72 frames, 0 differing values.
  Run: `make_edit_dng.py photo.rgba IN 1280 960`, cases.txt (one state JSON a line), `android-host/run.sh HostEditRender
  EDIT_IN=IN EDIT_OUT=REF`, `edit_check app/src/main/assets/shaders IN REF`.
- Bridge/ScamEdit.{h,mm} (SCAMEditSession): one photo's preview source and GL context; render the view frame / histogram /
  thumbnail, the RAW white in K, export at full size (picture or DNG). Syntax-checked with clang (Foundation stubbed); not built
  on a Mac here.
- SwiftUI (Gallery/): GalleryView (PhotoKit, the «SCAMERA» album or all photos, month sections, 4 columns), PhotoViewer (paging,
  zoom, favourite, share, edit / edit RAW, details sheet, delete), PhotoEditorView (top bar with undo / redo / «Ещё» / «Сохранить
  копию», «Оригинал» on long press, histogram, crop frame, tabs Кадр / Стили / Свет / Цвет / Эффекты / Детали with the Android
  sliders, ranges, defaults, curves, mixer, grading, B&W, styles), the copy saved as a new JPEG (q95, EXIF kept, orientation 1)
  into the album. MediaLibrary adds new shots to the album when the app has full library access.
- 2026-11-13 «LUT» tab (P619): Editor/EditLook.hpp (EditLook.film / key / grain / overlap / applied / blocking on
  Engine/post/color's FilmSim and FinishedLook), EditRenderer.renderLook (editor/look.glsl, then film/noise + film/blur +
  film/grain_sf or film/grain; mipmaps), EditExport tiles with the look and its overlap; ScamEdit applies the state's look; Swift:
  the tab after «Стили» (LUT / Плёнка segments, cards with the photo through each look (SCAMFilm.lookThumbnail), strength, print,
  format, grain, exposure, contrast, DIR, print exposure as the Android tab). Without the Vulkan engine a film is FinishedLook's
  first-phase table (FilmSim.lut), as on Android without it. edit_check against HostEditRender (now with the look; ScameraApp's
  context mocked so FilmSpectral reads the assets): the 6 old cases + «Тёплая 400» 70 %, Portra 400 80 % + grain 120 + «Кино 500»
  60 % on its print (16 mm, turned), HP5 grain 150 (Super 8), Velvia 100 format off + print EV / DIR, Vision3 500T on 2383 IMAX 70
  grain 60 x 3 sources: 132 frames, 0 differ.
- 2026-11-13 «Знак» tab (P633, EditWatermarkPanel): `Gallery/EditWatermarkPanel.swift` - «Как есть» / «Убрать» (greyed without
  the photo's record, a tap says why) / «Изменить» or «Добавить»; for a watermark drawn the camera's controls with the settings
  page's own strings (android_settings.json: style, position, colour, font, size, opacity, margin, the fields with this photo's
  EXIF values or «нет в EXIF» greyed, the own text, date format, «Как в настройках камеры»), long press = the setting's help.
  EditState.wm / wmRemove as Android's JSON (WatermarkSpec.toJson / fromJson ported: org.json key order, numbers, escapes).
  EditorModel: the record read from the file end on open (picture route, `SCAMEditSession.setWatermarkRecord`: fits = stored
  size and orientation 1, every piece's CRC), the EXIF facts, the live overlay (the plan for the copy's size - outputSize of the
  crop and outMax - drawn scaled into the picture rect; «Авто» from a 160 px thumbnail without the watermark, chooseInkScaled;
  CoreGraphics shadows scale with the context's CTM, unchanged at scale 1), the copy: the watermark drawn by PhotoWatermark with
  its record appended to the JPEG (EditExporter.writeCopy). ScamEdit: the clean preview (record painted into the preview,
  uploaded when the state has wmRemove: view, histogram, thumbnails, the export's proxy) and the export picture restored.
- Check (edit_check vs HostEditRender): the 11 picture cases of the LUT run with "wm":{"rm":true} - the C++ draws a stand-in
  watermark over three rectangles, makes their record and renders through the clean preview / restored picture; Android renders
  the clean picture (no record: «Убрать» changes nothing, its frames equal the cases without "wm"): 44 frames, 0 differ (without
  the restore: 4 of 4 differ). The record itself: `edit_check --wm make DIR` / `android-host/run.sh HostEditRender.watermarkRecord
  WM_DIR=DIR` / `edit_check --wm check DIR` - Android's 8-bit, 10-bit and over-4-MP (2 pieces) records read here word for word and
  written again byte for byte, WatermarkStore.append of JPEG / WebP / HEIC byte for byte and read back, the iPhone's records in
  the three containers and the 2-piece one read by Android (WatermarkStore.read, verify, wordsOf) word for word, the restore;
  with zlib at level 6 the C++ records equal Android's byte for byte (the phone's deflate is Foundation's: other bytes, same
  data): 21 checks, 0 differ.
- 2026-11-13 gallery (Android gallery/ui): bottom tabs «Фото» / «Альбомы» / «Поиск» (GalleryTabBar); «Фото»: «Выбрать» and a
  long press (SelectionBar: × · count, a tap selects all · «Сравнить» with exactly two · «Избранное» · «Поделиться» · «Удалить»;
  a month's title selects its photos), «Вид» (order newest / oldest, 2..6 columns, pinch), «Фильтр» (format RAW / JPEG / HEIC,
  favourites, years; the chip resets it), kept in defaults «gallery_ui»; AlbumsPage (Часто используемые: Все, Камера, Избранное,
  RAW, Скриншоты; Другие альбомы: the library's albums, 9 until «Ещё»; Тип: RAW / HEIC / JPEG counts; Другое: the «Фото» tab's
  folder; sections fold, remembered) and the album's grid; SearchPage (GallerySearch: file name, format, favourite, screenshot,
  year / month ru+en / day / сегодня / вчера; recent queries, quick chips, albums); CompareView (ImageCompareFragment: two panes,
  labels, tap = time · ISO, swap, «Зум и сдвиг вместе», share both as one picture), the viewer's «Сравнить» (with the next photo);
  details as GallerySheets.details (album as the folder, 35 mm «экв.» as the lens, Ultra HDR, PhotoMore rows: taken, bias, WB,
  flash, metering, digital zoom, colour space, rotation, location, software; the route note; the RGB histogram). Favourites are
  PhotoKit's (Android keeps its own list only because MediaStore asks per file). Formats are read from PHAssetResource in the
  background (GalleryLibrary.kinds).
- Not done: the full-resolution zoom regions (P367: the preview zooms as a picture); ScamGL editor output not compared with Mesa
  (needs the macOS runner); out of scope or not portable: the video filters / «Записи экрана», «Недавно
  удаленные» (PhotoKit hides the trash), the text read on photos (OCR, P504), day cards / list / timeline layouts (the grid
  stays by month), undo of a delete (PhotoKit asks itself). Swift not compiled here (no Mac): the «Знак» tab, the gallery pages and
  the record's Swift side need the CI build and a phone check; that Photos keeps the bytes after a JPEG's EOI / a HEIC's last box
  (PHAssetResourceManager returns the original) is to be seen on the phone.

## Watermark (2026-11-13)
- `Engine/Watermark/` ports `processing/watermark` (P628): `WatermarkSpec` (the Android keys and defaults from
  `SettingsStore.values`, read as PreferenceValue / PreferenceNumber / SettingsNumericRules do: lists and sliders as strings,
  size clamped 3..20, opacity 10..100 and margin 0..10 clamped and rounded half-even), `WatermarkText` (the groups, column,
  band sides; Java's number formatting: `%.1f` half-up on the shortest decimal, Math.round; SimpleDateFormat's patterns, the
  month in Russian with the interface language), `WatermarkPainter` (the plan in Float as Java's float: four layouts, six
  positions, the glyph's paths, «Авто» ink from 16x8 samples, opacity, shadow; drawn with CoreGraphics / CoreText into the
  RGBA8 rows), `WatermarkArea`, `WatermarkExif` (ImageIO properties: Make / Model, focal / 35 mm, f-number, exposure, ISO,
  DateTimeOriginal + OffsetTimeOriginal), `PhotoWatermark` (switch, facts, apply), `WatermarkPreview` (SwiftUI card for the
  settings row `custom:WatermarkPreviewPreference`: the settings' drawn landscape, LutSamples.scene, landscape / portrait).
- Applied in `BurstProcessor` after SCAMShot and before the encoder (the camera's HEIC properties first, the DNG's EXIF for
  what they lack, the shot's start time when neither has a date), and in `ShotProcessor.process` (upright CIRAWFilter route).
  The Ultra HDR gain map is not watermarked (as ScamProcessor: the map is made before the watermark, the base alone is drawn on).
- Fonts: Roboto Flex is a third-party font the Android app bundles, so «SCAM» is SF Pro; «Узкий» SF Pro condensed, «Засечки»
  New York, «Моно» SF Mono (500 / 600). Geometry, text and colours follow Android exactly; glyph pixels cannot match Skia.
  Shadow: Skia's sigma (0.57735 r + 0.5) with CoreGraphics blur = 2 sigma (approximate).
- Check: `tools/watermark_host/run.sh` compiles the Android Java (Spec, Text, Painter.plan, Area, the preference readers) on
  stubs in a plain JVM (fixed text advances) and writes `Tests/WatermarkGolden.swift` (`--check` diffs it):
  stored-value parsing, all 256 field subsets, 90 times (3 zones x 5 formats x en / ru), shutter / aperture / number / clean,
  90 layouts (15 specs x 6 sizes) with every box and WatermarkArea's rectangles. `Tests/WatermarkTests.swift` checks the Swift
  port against them with the same fixed measures, plus CoreText drawing (corner, upright, «Авто», nothing outside the area),
  EXIF reading.
- 2026-11-13 P633 / P642 record: `Editor/WatermarkPatch.hpp` (WatermarkPatch + WatermarkStore in C++: the pieces of
  WatermarkArea's rectangles split at 4 MP, the predictive code, CRC-32, toBytes / fromBytes, paint exact or scaled, the JPEG /
  WebP / HEIC tails), `Bridge/ScamEdit` `SCAMWatermarkRecord` (Foundation's raw deflate, ImageIO for Android's lossless WebP pieces,
  read only), `Engine/Watermark/WatermarkRecord.swift`. `PhotoWatermark.applyRecorded` cuts the record after «Авто» picks the ink
  and before the drawing (WatermarkPainter.drawOn's hook); `BurstProcessor` appends it to the encoded JPEG / HEIC
  (`appendRecord`). The iPhone writes 8-bit sRGB records with the predictive code (no WebP encoder); the rows' fourth byte is
  recorded as 255 (Android's photo is opaque). Checked against Android: «Gallery and editor» above.
- Not done: `ShotProcessor.process` (the upright CIRAWFilter route) still calls `apply` without the record (the file is outside
  this port's files: switch it to `applyRecorded` + `appendRecord` as BurstProcessor); the preview names the phone «Apple iPhone»
  (iOS has no public model name; the shot uses the EXIF Model).

## Settings (2026-11-13)
- `App/SettingsView.swift` renders `Resources/android_settings.json` (gen_settings_ui.py; it now also carries an `<intent>`'s
  link as `url`); every custom preference and plain row has an iPhone counterpart in `App/Settings/`:
  `ToneCurve` + `ToneCurveEditor` (ToneCurve.java / ToneCurvePreference / ToneCurveView: the "x:y" points with four decimals,
  the monotone cubic, add / drag / hold-to-delete, undo / redo, S-curve / straight, the point's exposure / brightness sliders and
  typed values, save / load in Documents/ToneCurves incl. WhyCam JSON and a camera's linear curve), `StyleSettings`
  (PhotoStyle with its ui_style_last_* memory; LutChanges: LutLook's analysis, curve and key colours through
  SCAMFilm.lookThumbnail), `CapturePages` (format and size cards: pref_photo_format and the camera's RAW / full resolution
  through an optional `SettingsView(camera:)`; shade transparency; RawCrop's sketch with draggable edges), `Profiles`
  (ConfigXml writer / reader in Android's element names, old JSON backups, ConfigState, Documents/XML, save / load / share /
  delete, reset with scam.dev set aside), `SettingsPages` (PageReset in every page's header, «Сбросить ScamMaster», the lens
  modules list, the SHDR report from the session log, log size / share, about), `SettingsAvailability` (the Android reasons that
  depend on the stored values, the iPhone's device facts, and the rows an iPhone cannot serve: root, package spoofing, vendor
  keys / sensor modes, the lab, scam_dev.txt, DCP, noise model files, the fringe net, RAW crop).
- Not done: CameraView still opens `SettingsView()` without the camera (pass `SettingsView(camera: camera)` so the format / size
  cards switch it); ToneCurveCompare (AgX / ACES / Uchimura references and templates need WhyTone's uniforms in Swift); the
  «Для разработчика» page (DevKeys); config import runs no Android migrations (BrandMigration, SettingsMigration); clearing
  today's log needs a SessionLog API; many capture / viewfinder rows are stored but not read by the iOS capture yet.

## Camera screen parity: live look, capture settings, EXIF (2026-11-13)
- Live viewfinder (`Viewfinder/`): Android's default viewfinder is the ISP frame through `shaders/preview/main_fs.glsl` (the
  developed-RAW one, pref_live_viewfinder_raw_key, is off by default and has no iPhone counterpart: no RAW stream). Ported to
  Metal (`Viewfinder.metal`) on AVCaptureVideoDataOutput frames (preview-sized BGRA, rotated / mirrored by the connection, Display
  P3 brought to sRGB): the film / LUT table FilmChain.current gives the photo (first-phase table: FilmPreviewLut's engine table is
  not ported), mixed by its strength; the lens filter's or the film's diffusion from a half-size mip pyramid with
  DiffusionModel's level weights (levelWeights / combine / mipSigmas / nnls ported in `ViewfinderLook.mm`, on Engine/post/color
  read only); focus peaking (pref_peak_method_key: 1 on, 2 with manual focus). Settings read twice a second
  (LiveLensFilter.REFRESH_MS). With nothing to add the output's connection is off and the plain AVCaptureVideoPreviewLayer shows
  (Android draws the frame unchanged then; there is no Android switch for the ISP look). Swift reaches the ObjC++ class by name
  (`ViewfinderNative`: NSClassFromString + perform), so the bridging header is unchanged. Not ported: the Oldstyle live style /
  tone (uOsOn / uOtOn), the streak / prism / GND filters (not offered by Android either).
- Capture settings now read: flash modes (pref_ae_mode_key, FlashMode cycle off → auto → on → torch; a flash shot is one Bayer
  frame with the flash merged as a one-frame SHDR shot, else the camera's flash photo; Auto meters with isFlashScene; the front
  lens's torch is the screen's light with full brightness), shutter / timer sounds (pref_camera_sounds_key / pref_timer_sound_key,
  the Android res/raw mp3s bundled by project.yml, one shutter sound per shot; the system's capture sound suppressed on iOS 18 where allowed,
  else it is the shutter sound), the self-timer (its sound; the shutter or a volume button stops the countdown), the volume
  buttons as the shutter (AVCaptureEventInteraction, iOS 17.2), grid types 0-4 (the top row toggles off ↔ 3x3), tap focus /
  metering with the ring, long press = focus + exposure lock («Закреплено», AE locked after 600 ms), a tap on the ring resets,
  the exposure sun (EV in thirds, the Pro EV), pinch zoom + the zoom ruler (ZoomController: module = largest ratio ≤ zoom,
  digital residual only off SHDR), pref_af_mode_key, pref_face_detect_mode off, pref_low_light_boost, «Спорт»'s shutter ceiling
  (pref_scam_hybrid_mm_shutter → activeMaxExposureDuration), front photo mirrored (pref_tunable_parameters_disablemirror),
  rounded viewfinder corners (pref_show_roundedge_key), «Про»: ISO / shutter / EV / focus / WB each auto on its own, the device's
  ranges, software priority (the other value keeps the AE's exposure), EV locked with both, WB 2000-10000 K.
- EXIF (`Capture/ShotExif.swift`, ShotExif / ParseExif): the press time with SubSecTime* / OffsetTime*, ISO as
  PhotographicSensitivity + SensitivityType 3 + ISOSpeed, ApertureValue, focal length, FocalLenIn35mmFilm (from the format's field
  of view when the camera gives none), LensMake / LensModel («Apple iPhone … back camera 6.77mm f/1.78») / LensSpecification,
  Software, ImageDescription («SCAMERA», Frames=, Camera=, Film=<FilmSim.Settings.recipe>, Route=SHDR). Written into the camera's
  own photo (AVCapturePhotoFileDataRepresentationCustomizer) and into the metadata CameraModel passes to BurstProcessor /
  ShotProcessor.
- Needs Engine changes (not made here): ShotProcessor.encode overwrites TIFF Software with "SCAMERA iOS <version>" (keep the
  metadata's when present, ShotExif.software); the SHDR photo of the front lens is not mirrored (Parameters.mirror for a front
  DNG). The digital zoom and 16:9 (pref_wide169_key) of SHDR: done (Engine/post/SabreCrop.hpp, «SHDR brackets and crop»); the
  camera model still keeps SHDR's zoom on the modules (CameraModel.zoom(to:)).
- Not done: split AF / AE (pref_split_af_ae), tracking AF, antibanding (no iPhone API), ETTR (pref_ettr: the Android viewfinder
  AE's boost from the ring's RAW metering). ES / L brackets: done («SHDR brackets and crop»). Swift / Metal not compiled here (ViewfinderLook.mm syntax-checked with clang).

## The gallery's video player and the viewfinder HUD (2026-11-13; video recording removed)
- Video recording removed per the owner (2026-11-13: «Видео наше приложение не снимает, только фото» - the app takes photos
  only; Android's CameraMode.VIDEO is not reachable from its interface either). Gone: the video mode and its hidden button
  (pref_ios_video_mode), `Video/MovieCapture`, `Video/VideoSettings` (pref_video_resolution_key, the format sheet's size row),
  CaptureService's movie output / microphone input / frames-output removal, CameraModel's video / recording state and the
  pref_camera_mode_key handling, the video shutter glyph and the recording line, `MediaLibrary.saveVideo`, the
  NSMicrophoneUsageDescription and the recording strings. The camera screen is photo-only as before the video mode (shutter,
  flash cycle, timer, Ultra HDR, lens / mode / settings buttons). Kept: the gallery's video items and player (Android's gallery
  plays the library's videos) and the AF / AE HUD, moved to `Capture/CameraHud` (DeviceHudMonitor still hooked in
  CaptureService.createRotationCoordinator).
- Gallery: videos listed with the photos (photoOptions: image or video; the «Видео» album; search words видео / video), the grid
  cell's play mark, length and the watched part (VideoResume); the viewer's video page: the poster with a play disc, no
  «Изменить» / «Сравнить», details with the length; share writes the file with PHAssetResourceManager.writeData.
  `Video/VideoPlayerView` + `VideoPlayerModel` + `VideoSurface` (VideoPlayerActivity on AVPlayer): previous / play / next over the
  list's videos, seek bar (time / length, a tap: what remains), speed 0.25x..4x (0.05 steps, presets, reset), mute, repeat
  off / all / one (prefs «video_player»), fit Вписать / Заполнить / Растянуть / 100 % / 16:9 / 4:3 (VideoLayout.size) with pinch
  zoom 0.5..4 and pan, rotation by the video / landscape / portrait (the player turns its own picture: the app is portrait),
  screen lock, sleep timer 15..90 min / end of video, screenshot (exact frame, JPEG into SCAMERA), subtitles (the file's legible
  options drawn by AVPlayerLayer, the first on by itself; picked .srt / .vtt via `Video/Subtitles`, a port of Subtitles.java incl.
  Windows-1251; sizes 16 / 20 / 26), the sound track (audible options, «Выключить звук»), resume (`Video/VideoResume`, the
  Android rules: not in the first / last 3 s, 300 kept, «Продолжено с … · С начала»), details (file, size, date, length, size /
  fps / codec, bit rate, sound tracks), share, delete; gestures: double tap ∓step at the sides (adds up, step 5..60 s in
  «Настройки плеера»), pause in the middle, sideways drag seeks (≤ 90 s across), vertical drag brightness (left) / volume
  (right), long press 2x.
- Not on the iPhone: HW / HW+ / SW decoders, the volume boost over 100 %, background play and picture-in-picture (need the
  audio background mode in Info.plist, not added), auto-rotate by the sensor, hidden quick buttons, a subtitle file beside the
  video (no folders in PhotoKit), the in-file text tracks read as cues (AVPlayer draws them itself).
- HUD (pref_show_afdata_key, the settings row already exists): `Capture/CameraHud` — DeviceHudMonitor key-value observes the open
  lens (exposure duration, ISO, lens position, focus / exposure / white-balance modes and their adjusting flags, EV bias and
  metering offset, gains, zoom, points of interest, torch), publishes at most every 150 ms; mode 1/2 draws updateViewfinderHud's
  rows (shutter as ExposureIndex.sec2string, ISO, 35 mm-equivalent focal · f-number, AF-S / AF-C · SCAN / LOCK or MF · lens
  position, AWB / MWB · K from temperatureAndTintValues), mode 3 the frame data and green / yellow boxes at the focus / metering
  points. Not ported: the histogram of mode 2 (the viewfinder's frames belong to the live look; no second data output), OIS and
  tripod rows, the frame number, the LMT flags, the metering rectangles' size (the iPhone gives points), the HUD's rotation.
- Swift not compiled here: the CI build and a phone check must confirm the player's gestures and the HUD values.

## SHDR brackets and crop (2026-11-13)
- Capture (Capture/BayerBurst.swift, Engine/HybridPlan.swift): after the N frames (AE bracket at bias 0, as before) the same
  series takes the plan's extra frames as manual exposure brackets (AVCaptureManualExposureBracketedStillImageSettings, RAW only,
  `maxBracketedCapturePhotoCount` a request, ultrashort first): HybridPlan.build as buildNormalBack takes it (the iPhone has no ZSL
  ring, so its N frames are all after the press and the plan adds only Bento / Shasta). N exposure = the newest N frame's measured
  ExposureTime x ISO; Bento «Всегда» (bento 2) or «Авто» (clip share of the newest N frame above bento_trigger, CaptureController
  .zslClipFraction ported as FrameMeasure), `bento_frames` at N / `bento_factor` (gain first, then shutter, skipped at x0.5 and up);
  Shasta 1–3 frames (P410 count by the shadow share, P409 +2 EV shutter-first under deep shadows) at N x 2^`shasta_ev`, gain first,
  shutter only past the ISO range (cap 1/8 s), skipped below x1.2 or above `shasta_max_ratio` unless «Всегда»; none in «Движение»,
  with «HDR кадры» off or in «Oldstyle». Each delivered extra keeps its role within 0.4 EV of the plan, else the role its measured
  exposure gives (HybridPlan.classify). A refused extra request leaves the N frames (P27).
- Transport: Swift writes every frame in capture order (NCH v12 pages) and the facts (last.nch.json: role, exposureNs, ISO,
  timestampNs, noise at the frame's ISO, the zoom, the 16:9 cut). ShotRun (C++) applies the ScamHybridBurst constructor's rules
  (Engine/post/HybridSelect.hpp): base = sharpest (RawFrameQuality) of the newest 4 N within 205 ms, N newest first without the
  P331 blurred donors, the N limit, bracketed only above x1.2 of the base (else N within normalBand, else dropped), ultrashort =
  the closest to base / bento_factor plus up to bento_frames within x1.3 (x0.75 and up: N or dropped), ratios = exposure x ISO over
  the base's (dropped outside x1/512..x512), orderMs to the newest N, noiseFor (noise_photon / noise_readout); ROLE_NORMAL 1 /
  ROLE_BRACKETED 3 / ROLE_ULTRASHORT 5; usSceneEv from the base frame; Parameters.mosaicBlock = ScamHybridBurst.mergedBlock. The
  frame table is rewritten in place over the v12 pages (no plane moved).
- Crop (Engine/post/SabreCrop.hpp, ShotTransport.hpp, ShotRun.hpp): SabreCrop P576 (the zoom's window of whole colour periods,
  cut before the merge and merged on the grid the photo needs: 2x zoom on a 12 MP frame = the 2x Sabre merge of the 3 MP window),
  HybridOutput.plan with the window (never strict 2x, final size = merged), RawCrop.cropGainMap of the window, PortraitFaces.crop of
  the faces, filmFrameScale; after the pipeline ScamProcessor's tail (cropRect remaining crop, the photo size by the downsampler /
  Lanczos-2 upscale, else ZoomController.crop + scaleTo); «Увеличивать кадр с кропом» off (pref_crop_upscale): the window on the
  sensor grid, the crop keeps its pixels. «16:9» (pref_wide169_key, Wide169 P118): the frames' centred rows are cut at capture
  (BayerBurst), ShotRun cuts the DNG's lens shading map to them (Wide169.cropGainMap).
- Checks (host): tools/shot_run_check --select: the frame rules against the Java ScamHybridBurst constructor on 400 random bursts
  (N / L / ES mixes, missed exposures, invalid noise, bento_frames 1–3, bento_factor, sharp_select, noise multipliers; dumped by a
  Robolectric run of the Java): 4497 table lines, 0 differ (roles, exposures, ISO, noise, orderMs, EV100 bit for bit). RawFrameQuality
  .score on the 26 Lenta frames identical to the Java (plain JVM). SabreCrop.plan / cropRect / target / uncroppedPhoto on 3000 random
  cases and RawCrop / Wide169.cropGainMap on Lenta's map against the Java sources (plain JVM): 0 differ. The app's whole shot
  (shot_run_check, Lenta + its DNG, SCAM_SHOT_LAB_PARAMS=1 for the vivo values its DNG cannot carry, the lab meta's tuningBurst =
  the app's burst lines) against the Android lab (HostPostRender) on variant shots whose bursts were cut independently (Python) and
  whose params carry the Java's crop values: no crop 0 of 37748736 values differ (it was 48.4 dB: Parameters.mosaicBlock was not
  set), zoom 2 (window 2048x1536 on the 2x grid) 0 of 37748736, zoom 1.5 (window 2736x2048 at 4104x3072) 0 of 37822464 and after
  the tail (4 px crop to 4096x3072, the Java cropRect) 0 of 37748736, «16:9» (4096x2304) 0 of 28311552.
- Not on the iPhone / not bit for bit: no ZSL ring (the N frames follow the press, no «Движение» moment base, no P641d overflow
  pairs); one DNG per series (the other frames' noise is its profile at their ISO, Camera2 gives each frame its own); the manual
  brackets come as separate requests after the N (a gap of a request's latency; the iPhone may round the exposure: classify);
  «Авто» antibanding has no mains measurement (no snapping, 100 / 120 set by hand snap as Android); the HAL motion cap, DCG,
  learned exposure limits, NoiseModelProfile (pref_noise_model_profile_key) are Android's; the Bayer RAW is assumed to be the whole
  frame at any videoZoomFactor (check on the phone: the log's «burst frame 0: WxH»); the saved DNG stays the whole 4:3 frame with
  «16:9»; the Ultra HDR map follows the crops by a bilinear resample (Skia's filtered draws on Android) and «area» / «bilinear»
  downsampler steps after the pipeline use the bicubic kernel. Swift is not compiled here.
- To do in the camera model (ios/App/CameraModel.swift, not edited here): SHDR keeps its zoom on the modules (`zoom(to:)`:
  `let residual = shdr && !video ? 1 : max(1, target / pick.zoom)`, `zoomRange`, setShdr's reset): with the residual kept for SHDR
  the burst carries it (CaptureService.captureBayerBurst sets BayerBurst.zoom) and `BurstProcessor.process(burst, metadata:
  metadata, zoom: burst.zoom)` crops as Android.

## Re-sync with main P647-P663 (2026-11-13)
- Android moved to `com.scamera.app` and rewrote most Java as its own code (same output); the port keeps its names (the Java ones
  before P659: `mParameters`, `WorkingTexture`, `whitePoint`, `sensorToProPhoto`, `CCT`, ...) - they are the lab JSON's keys
  (ScamLabParams.STORED_KEYS), which gen_parameters.py now reads (field = stored key; `hotSites` skipped, the removed dead fields
  gone, `finishNoiseS` / `finishNoiseO` added). Comments name the new methods (resolveColour, fillFromFrame, develop, buildNodes).
- Output changes ported: P648c «Подстройка под свет (SNR)» (PostSnr in nodes/ScamHdrDenoise.hpp: DenoiseStats.finishSnr sets
  finishNoiseS / O, why_resolve gets flatNightU / flatBoostU / flatGrainU / flatNoiseU, the PostSnr log line); P652 (rlHaloMargin,
  floor 1e-4); P647 `kernelSnr` and P662 `gpuPace` in HybridTuning.text; RotateWatermark without the WATERMARK define. Renamed
  shader interfaces: GLHistogram `binScale` (was `exposure`: the Ultra HDR medians), utils/gaussdown (`srcLevel`, `srcSize`,
  `srcPerOut`) and utils/pyramiddiff (`fineLevel`, `coarseLevel`, `fineTexel`). P647 / P658 / P661 / P663 are in the shared
  merge C++ (no port). Swift: SettingsAvailability (RAW-merge-only `kernel_snr`, «Кадры ZSL» open, the «Сила резкости» reason).
  Film assets and spektrafilm resources unchanged (only the APK compresses film/*.bin); 8 dead shaders gone (111 post shaders link).
- Host harness: android-host Java under com/scamera/app (PostPipeline.develop, GLTexture id / size / format, GLProg's source
  loader by reflection in the smoke test), watermark_host stubs moved.
- Checks (Mesa, references re-rendered by the new Android code, hybrid_replay from the new shared C++), post_node_check full,
  each 0 differing values: lenta, texture, v2, os2, uhdr (gain map 0 of 50331648, max 3.312), f_v2 (old film route), p_awb,
  resize_lanczos (0 of 23970816), night (dn_snr 8: flat 0.916) and night_off (post_snr / kernel_snr off, halo margin 0; differs
  from night in 14.3M values). shot_params_check: tuning 116 lines identical (night_off: kernelSnr 0), colour identical, DNG round
  trip identical. shot_run_check: Lenta PSNR 43.4 dB (the shot's own tuning lines); with the app's burst lines and the lab's values
  (base0, SCAM_SHOT_LAB_PARAMS=1) 0 of 37748736 (merge with kernelSnr included). edit_check vs HostEditRender: 132 + 44 frames 0
  differ, the watermark records 21 checks 0 differ; watermark_host --check matches; HostGlSmokeTest passes. Not re-run here: the
  reference film engine on lavapipe (its library builds; ReferenceFilm unchanged), the shot_run_check --select tables
  (ScamHybridBurst's frame rules unchanged), Metal (CI).

## Merge on Metal after P658 / P661 (2026-11-13)
- The merge-metal CI check (Metal vs Mesa, the five burst crops) after main's P658/P661 base demosaic: four crops 91–101 dB as
  before, e193420 74.6 dB with mean 1e-6 and p99.9 3e-5 of the scale — a few isolated pixels where the two GPUs take the new
  moving-area branch differently (max 0.18). The gate is now PSNR ≥ 70 dB and at most 0.05 % of the values more than 1 % off
  (replay_bursts.py --max-off-share), so a real regression (a wide difference) still fails.

## Gallery developer «Проявка» (P665, 2026-11-14)
- Record (Android DevelopRecord, the same bytes: «SCDV», version, CRC-32, deflated sections JSON / CLIP / EFFR / HLMK, length,
  «SCDVEND1» at the DNG's very end): Engine/post/DevelopRecord.hpp (the platform's raw deflate: Bridge/ScamDevelopCodec.h on the
  iPhone, zlib on the host), Parameters.toJson (generated with fromJson by tools/gen_parameters.py: ScamLabParams.toJson's keys
  and forms), JsonOut.hpp. ShotRun.hpp takes it right before the pipeline (DevelopCapture.take), SCAMPhoto.developRecord,
  CameraModel appends it to the DNG file before the library gets it.
- The iPhone's DNG is the camera's DNG of the series' first frame, not the merged mosaic Android writes (P165; Android gives that
  reference-frame DNG no record). Its record describes that frame: the shot's Parameters on the DNG's whole grid (the lens shading
  map before the «16:9» / window crops, the faces on the whole frame, the final size scaled to the grid, outputScale 1), one N
  frame, no Bento, no effective-frame or clip map, the base highlight mask only when the base is the DNG's frame on its grid,
  headroom 1, the zoom's crop after the pipeline, "dng":{"frame":"reference"}. A develop of it is the camera's pipeline on one
  frame (more noise than the merged photo, highlights as that frame clipped them).
- Develop: Engine/post/DevelopRun.hpp (DevelopRun.parameters / values / map / resampled / hlMask / crops; the record's worker
  numbers over the report's; Ultra HDR off: the editor takes SDR), Editor/EditDevelop.hpp (superpixel preview of 2x2 cells of at
  most 2048 px, Malvar-He-Cutler full develop, halves above 16 MP), DngRaw.decodeLinear (floats; the OpcodeList2 lens shading left
  out when the record's Parameters carry the map - the pipeline applies it, as on the shot). Bridge/ScamDevelop.mm (SCAMDevelop:
  preview / full with the changes' JSON, a GL context of its own per develop, processLock() shared with SCAMShot: ScamLab.LOCK).
- Editor: «Проявка» tab after «Кадр» (Gallery/EditDevelopPanel.swift): «Проявка камерой», the groups as chips, the settings
  screen's own rows (SettingsRow over a DevelopValues source; lists as menus), greyed with DevelopCatalog.inert, a changed row
  marked with the shot's value; EditState «dv» block ({"on", "s"}: version 2 only with it, EditState.hpp reads it), undo / paste /
  reset carry it; EditorModel develops the preview 0.45 s after a change rests (one undo step), swaps the shown session (the
  developed picture: upright sRGB, orientation 1), the copy develops the whole DNG (SCAMDevelop.full) and exports it as a picture.
- DevelopCatalog (App/Settings/DevelopCatalog.swift): the groups' parts, AT_SHOT / BURST_FRAMES and the merge's tuning keys
  generated into android_settings.json "develop" by tools/gen_settings_ui.py; the film group is the iPhone film page's 15 keys
  (FilmAdvanced's DIR / halation / development rows are not on the iPhone's page); visibility flags of preferences.xml are not in the
  generated tree (SettingsAvailability.hidden only).
- Check: tools/develop_check.cpp (CI build-ios-ipa merge-reference): the Android tests' cases - the record through a file (CRC,
  version), Parameters field for field, a reduced grid, Sabre 2x final size, settings and changes, maps, crops, MHC, halves.
- Not here / to check on the phone: Swift not compiled (no Mac); that Photos keeps the bytes after the DNG; the develop's speed and
  memory on a 48 MP DNG (12 B a pixel below 16 MP, 6 B above, plus the pipeline); an Android-recorded DNG of a DCG shot does not
  develop here (the DCG noise zones are not ported); the copy's description line (EditExporter.describe) is not written.

## Re-sync with main P665-P679 (2026-11-14)
- Ported (output): P666 the shadows' texture test of why_resolve (PostSnr.shadowBand / shadowTexture, flatShadowU, the log's
  «shadow lo..hi»); P672 the fusion without the noise model by default (WhyTone.fusionNoiseModel), bracket_denoise by V2's rule
  (no DCG exception, none on the iPhone anyway), NIGHT_DETAIL 1; P672b the detail as V2's (WhyTone.detailSplit, DETAIL_SPLIT,
  detailSplitU, WhyCombine.splitGain); P673 the joint bilateral collapse (V1 off, V2 on at 0.1: lpyr / v2_ef_collapse JBU);
  P676 V1's colour by V2's rule (V2_COLOUR; hl_white, delta_chroma, yellow_hue, hl_neutral, light_warm, light_chroma off with it;
  signedHueU 0) and its settings reason; P677 V2's edge bands (WhyV2Fusion.bandPasses, off by default); P675 fastNightMotion and
  P666 kernelSnrShadow in HybridTuning.text (the merge itself is the shared C++). Parameters: loficLog2 (generated).
- Not ported, the same output: P671's speed paths (resolvedn/optics Oklab cache, why/combine GATHER_TAPS, scamrgb band tiles), P668
  GPU pacing by measured time. Android-only: P679 Xiaomi 17 Ultra LOFIC (LoficMerge, XiaomiLofic), P667 YUV 10-bit default /
  migration, P669 PedestalFloor (the iPhone has no RAW viewfinder), the X200 Pro / Find X7 Ultra fixes, the viewfinder's
  LiveWhyTone.
- Checks (Mesa, references by the new Android code, hybrid_replay from the new shared C++), post_node_check full, each 0 of
  36-37.7M values: vivo_room, lenta, v2 (jbu=0.1/1), night (shadow 1.6..4.0), night_off, texture, os2, uhdr, p_v1 (v2Colour,
  detail=1.0), v2tex, f_v2. shot_params_check / shot_run_check compile.
