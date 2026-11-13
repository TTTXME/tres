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
  frames at bias 0, repeated to N (default 8); frames copied from the 14-bit Bayer pixel buffers (CFA from the format).
- `Engine/NchWriter.swift`: the burst as NCH v11 (Android's transport) in Documents/Bursts/last.nch (Files app) — the
  same file replays on a PC against the Android merge. Noise: DNG NoiseProfile, else estimated from frames 0 / 1 (green
  sites, 32 px tiles, lower half of the variances), scaled by ISO per frame as Android's fallback.
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
    the macOS job renders both on MoltenVK (Metal) against the lavapipe reference with a PSNR bound. Not ported: the editor's /
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
    Transport: the base frame is NCH frame 0 (Android: the sharpest of the newest within the window), per-frame noise from the
    DNG profile scaled by ISO.
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
- Not done: «Знак» (watermark) in the editor; the full-resolution zoom regions (P367: the preview zooms as a picture); gallery albums / search / filters /
  selection / compare / video; ScamGL editor output not compared with Mesa (needs the macOS runner).

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
- Not done: P633 / P642 (the record of the pixels under the watermark appended to the file, WatermarkPatch / WatermarkStore,
  for the gallery editor's «Знак»), the editor's watermark tab; the preview names the phone «Apple iPhone» (iOS has no public
  model name; the shot uses the EXIF Model).
