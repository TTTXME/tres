# Unspektra AE port

Original implementation: Unspektrawesome by JohnTheFarm3r,
https://github.com/JohnTheFarm3r/Unspektrawesome-Releases.

## Recovered reference

The owner's 1.2.4 APK has SHA-256
`0d1adbd9e94c66cd5a2ba82d91896f54a908fe85829ecf3144947ec8ecfa1210`.
The controller is DEX class `ta3`; `js2.D` supplies matching sensor timestamp,
requested exposure, actual ISO and session/reset generations. The controller
does not use ISO as a substitute for a vendor gain.

The embedded `shaders/SpektraAutoExposure.comp.spv` is the module at native
VA `0x1399a24`, 69,448 bytes. The loader's comparison at `0x196f6e8` through
`0x196f760` selects it. This distinguishes it from the film-development
exposure shader, which follows a separate loader branch.

The statistic uses a grid with longest side at most 256, centre-weighted mean
or median of linear colour luminance, and optional 12% spot metering blended
with global metering. Spot influence is bounded to one stop. The correction is
`-log2(luminance * 5.4347825050354)`. Metering must precede display gain,
film effects and tone mapping.

Control uses a 16 ms minimum sample interval, a startup period of at most
1.5 seconds, a 300 ms median window after startup, separate target/control
time constants, and bounded changes of at most 0.2 EV. ISO/shutter allocation
honours the selected priority, lens limits, compensation and flicker frequency.

JADX's ordinary output incorrectly makes the tiny-change shutter ratio an
integer division. Fallback DEX output at `L374` converts both operands to
double before division. The port and comparison oracle use that DEX operation.

## Local validation

- 104 original GPU-shader fixtures: weighted mean, median, spot, odd sizes,
  513x257 downsampling, HDR values, edges and black. Java adaptation differs by
  at most `3.52e-7 EV` from the original GPU output.
- 80,000 controller samples against the recovered original Java arithmetic:
  all 27,183 updates, ISO, shutter, error and limit decisions identical. Includes
  all four modes, startup, median history, 50/60 Hz, bias, compensation, stale
  session samples, resets and tiny-change suppression.
- Ten integrated JVM tests pass: convergence, limits, manual priorities,
  stale/out-of-order samples, invalid statistics, spot influence and highlights.

Original binaries, extracted shaders, independent oracles and receipts remain
outside Git in `../evidence/unspektrawesome-124-20261101`.

## Readiness boundary

`UnspektraCameraAe` now connects the arithmetic to timestamp-matched repeating
RAW frames. The local RAW adapter samples complete Bayer/Quad/Tetra colour
periods, applies per-site levels, lens shading, matched white balance and the
photo colour matrix before the linear-sRGB statistic. This is SCAMERA's RAW
adapter, not a claim that two cameras produce identical developed pixels.

The per-module selector defaults to Android AE. Unspektra AE exposes mean/median
metering, minimum/maximum ISO and fastest/slowest shutter denominators. Bounds
are clamped to sensor ranges and the learned deliverable ISO cap. Fixed manual
dimensions retain their sensor range; fully manual exposure takes precedence.
Sports retains its adaptive short-shutter ceiling, manual shutter priority
retains its fixed value. ETTR and the previous software priority loop do not
compete with the active alternative. Native FULLRES white balance uses the
alternative's applied-value/settling state when it owns exposure.

Only frames carrying the current requested exposure feed back into the loop;
old session/meter revisions, duplicate/out-of-order timestamps, delayed images
and invalid metadata are rejected. Sustained HAL refusal or external override
releases ownership for the session. A new session resets this decision. The
engine selector requests a session restart and a continuous matched RAW stream
even with ISP display selected; it does not enable RAW display by itself.
Module copy/reset and sensor-session snapshots include the applicable AE keys.

On OPPO PHY110 / Adreno750, local test APK `f7a57408...77f40af7e` passed seven
actual Camera2 phases: Android, automatic alternative, ISO priority, shutter
priority, fully manual, automatic return and Android return. All 320 sampled
frames and their original request values are preserved in
`../evidence/gcam61-deep-20261101/unspektra-ae-device.json`.
The last ten frames of each active alternative phase report AE OFF and applied
ISO/shutter within 5% of the requested values (actual errors below 0.33%).
A second seven-phase320-frame run on local APK
`050e12d8...c8a17b1c` confirms the same controls with ISP display selected and
RAW display remaining off. Test settings were restored. Capture/settings/source
contracts115tests pass; the full2420-test sweep found two changed UI/source
contracts, now corrected, plus four known baseline Editor failures.
The close/reopen reset source change builds and passes the focused lifecycle
tests; a further physical close/reopen sequence has not been recorded.
Native FULLRES and physical Mali remain unverified. Still-only SDK routes without repeating RAW
cannot supply this meter and retain their existing exposure route.

The owner authorizes using 1.2.7 as the new reference. Verify its controller,
meter and processing changes before completing integration. Its automatic
GitHub source archive contains only README.md, not application sources
(tag commit `32a4c36ebfd8456fbc975f530d1dbfb22e09aa2e`).

1.2.7 APK SHA-256 was verified against GitHub:
`4d77df0c72d5323be3d3734f925ff398ca4be8a746bf50829ff68f53c0022d66`.
Its `rg3` controller passes the same 80,000-sample oracle with identical
27,183 updates. The shutter ratio's floating-point division is also confirmed
in its fallback DEX output. Its meter module `shader-133.spv` is byte-identical
to 1.2.4 `shader-121.spv`; the 104-fixture meter proof applies unchanged.
