# Processing precision and film scheduling

## Accepted changes

The film scheduler now uses the same scratch-capacity calculation for route
selection and actual tiles. A device can advertise a large storage-buffer
limit while the available memory budget still forces many overlapping tiles.
The supported staged graph avoids repeating every spatial effect for every
overlapping tile. Existing storage-limited routing remains unchanged; the new
budget-limited route requires room for the full-image milestones.

Global diffusion reduction runs the existing box-reduction shader on the GPU.
Incoming half-float film pixels are copied as half words and expanded by the
existing GPU format shader, instead of scalar CPU conversion. Upload storage
and traffic for that input are halved. Intermediate chemical calculations
retain their original precision. CPU buffer management and transfers remain.

Supported native-film shots no longer build the unused legacy spectral LUT.
The legacy route still builds its own table when required. Initialization,
device, storage limit, selected route and setup/submit/copy diagnostics identify
future device failures. Submit time includes a CPU fence wait; it is not a
hardware GPU timestamp.

## Accumulator experiments rejected

Two isolated workers were compared with the production worker on identical
four-normal-frame subsets of the owner's 230628 and 231613 detail bursts,
using ABBA order and unchanged tuning, frame decisions and dimensions:

- FP16-qualified merge, rim and chroma accumulators;
- FP16-qualified merge numerator/denominator only, preserving FP32 clip, rim
  and chroma accumulators.

Neither accelerated the warm end-to-end worker. The narrower variant took
3019 ms versus 2979/2985 ms on the first burst, and 3014/3034 ms versus
2948/2956 ms on the second. Changed pixels are not by themselves evidence of
inferior quality, but the first case has a 1.48828125 maximum linear RGB error
at a highlight and changed output trailers. That fails the quality gate.
Neither variant is enabled or included in production shaders.

These are shader-local accumulators, not a persistent FP32 image that can
simply be changed to RGBA16F. `mediump` alone does not establish native FP16
throughput. RAW samples, local-flow storage, output and coverage already use
packed integer/half representations in several paths. Coordinate, rejection
and subtraction-sensitive arithmetic must not be narrowed indiscriminately.

## Validation and limits

- Native JNI library builds with NDK 27.2.12479018.
- Actual OPPO Adreno 750: eleven cases match the saved reference bit for bit,
  including odd dimensions, spatial effects and two budgets. All thirteen
  resource files are pinned to the official 31203 APK by SHA-256.
- A full 4096x3072 real scene with all effects remains bit-identical after GPU
  reduction and unpacking. The staged warm native render is approximately
  6.3 seconds. Forcing the historical whole-graph route took 51.4 seconds on
  that device; this is a routing experiment, not an X200 Ultra speed claim.
- The actual X200 Ultra log without energy saving reports 27.917 seconds in
  ReferenceFilm. The earlier 173-second shot used energy saving. No current
  X200 Ultra device validation is claimed.
- GPU unpack covers all 65536 half encodings, including subnormals, signed
  zero and infinities. The shared planner covers 2304 capacity cases.
- An initial research replay used a stale diffusion SPIR-V and produced bands.
  Its files and failed comparisons remain as invalid research evidence. With
  the resource manifest verified against the APK, all eleven older oracles
  match again. The actual Android app copied the correct resources throughout.
- Actual Android full-post repeats of film, ordinary tone and a movement burst
  retain their complete output hashes and save decodable JPEGs. Film post
  measured 7.415 seconds cold and 5.953 seconds warm in that replay. These are
  separate runs from the native-only timing above.
- Physical Mali and the unavailable Nubia/12X remain separate device gates.
  In particular, this does not establish a fix for Nubia's black photograph.

Detailed inputs, hashes, rejected prototypes and logs are preserved outside
Git in `../evidence/why220-speed-20261102/`.
