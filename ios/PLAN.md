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
- Done: ScamGL render programs + format conversions; Engine/post/GLShaderSource.hpp (GLInterface.readProgram),
  Engine/post/GLCore.hpp (GLFormat, GLTexture, GLProg, TileSize rows); tools/post_shader_check (119 / 119 shaders on Mesa
  and Metal); tools/post_pass_check (Why V2 passes Mesa vs Metal in CI).
- Next: Engine/post/PostPipeline.hpp (GLBasePipeline main1/main2 ping-pong, Node, runNodes, final output readback),
  Parameters (the fields the nodes read: rawSize, whitePoint, sensorToProPhoto / proPhotoToSRGB, gainMap / mapSize,
  blackLevel, whiteLevel, noise, cameraID, outputScale, mosaicBlock, scamRgb), settings (PreferenceKeys.hybridValue /
  hybridSwitch / scamDevSwitch defaults from res/xml + the burst .json prefs), then ScamRgb.run (default paths:
  half RGB upload, channelClip, hlrecovery chanprep/reduce/opposed, purplebound, scamrgb, clipmask/clipband, suncore).
- Each node: a host check (Mesa) and the Metal run in CI on the same input; the Drive bursts for the whole chain.

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
