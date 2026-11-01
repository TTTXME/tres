# Sports processing review (P450)

## P461 follow-up, 2026-11-02

The comparison below concerns the inspected reference mechanisms; a separate
reference Sports capture controller has not been established. The owner's
requested general v220 speed investigation is the next task after this delivery.

| Mechanism | Reference evidence | SCAMERA Sports decision |
|---|---|---|
| Base selection | Early sharp candidate; relative floor 0.7/0.85 | Keep the owner's two-frame pre-press anchor |
| Donors | Sharpness floor 0.6; translation/static-score checks | Keep the stricter 0.85 floor and time/speed window, including 8/4/2 bursts |
| Extra exposures | Inspected photo controller can append short frames | Keep Sports N-only, with no exposure bracket |
| Rejected regions | Resolve and later multiscale filtering use local coverage | Retain the ported separate luma/chroma filtering and stronger low-coverage denoise |
| Exposure | No verified independent reference Sports exposure loop | Retain measured-motion shutter priority and flicker diagnostics |

An actual gap was found in SCAMERA's own focus/face adaptation: it selected an
analysis block only if that block's centre lay inside the subject rectangle.
A small subject rectangle between block centres could therefore receive no
special analysis; a single moving subject could still be dismissed as an
isolated tile. Selection now uses positive-area overlap. Only intersecting
blocks bypass the checkerboard and single-tile exclusion. Existing texture,
subpixel-confidence and flicker checks remain unchanged.

The new small-region regression failed before the fix and passes afterward.
Boundary-only contact does not select an adjacent block. Replays of all four
owner Moving bursts cover 50 measurable pairs: the unselected-region route
remains exactly unchanged; a small selected region now recovers an additional
90 RAW px/s in one 173124 comparison. This is measured algorithm behaviour,
not ground-truth object speed or a newly captured phone photograph.

Current Resolve/pyramid GPU checks pass fifteen full/strip cases and three
independent Float64 oracles. The sharp-priority/rejection checks pass, and
actual Android movement post replays repeat exactly and save JPEGs. No blur
removal after an already smeared exposure, or new matched live reference
Sports photograph, is claimed.

Evidence: `../evidence/ark220-speed-20261102/sports-*` and
`replay_sports_meter.py`.

## Earlier inspection

The supplied 2.10 reference exposes a general native frame selector, not a
separate Sports capture controller in the inspected Java code. This does not
prove that every native capture path has been identified.

The selector at native offset `0x18db4c` prefers an early frame whose sharpness
is at least 0.7 or 0.85 of the maximum. Its donor floor is 0.6 of the base's
sharpness. It also rejects large thumbnail translations (threshold 35 in its
thumbnail coordinates) and uses a static-score sum threshold of 0.5. These
thresholds cannot be copied as RAW-pixel speeds or sensor exposure limits.
Disassembly is preserved outside Git in
`../evidence/gcam61-deep-20261101/ark-frame-selector.txt`.

SCAMERA already has stricter Sports selection: the base is anchored two frames
before the shutter event, short 8/4/2 series receive sharp-donor rejection,
the base is preserved, and the sharp-priority floor is at least 0.85. Existing
motion freshness, subpixel measurement, donor-age limits, no-bracket policy
and explicit motion/flicker priority are retained. Replacing these with the
general reference selector would discard the shutter-time contract without
evidence of improved moving-subject detail.

The supported processing improvement is the reference's separation of Resolve
and the later Oklab pyramid. Both consume this worker's effective-frame map;
rejected/moving areas receive the reference's separate luminance/chroma
strengths. The additional legacy Sports filter is removed, rather than stacked
over the reference stages. The frame-map adapter is SCAMERA-specific; it is
not claimed to be the reference accumulator's weight sum.

The original-shader comparisons cover 60 cases, and full/strip pyramid checks
cover six grids. Actual full Android replays include the owner's Moving burst
and eight recent detail/lighting bursts, preserving complete inputs, frame
counts, output dimensions and capture settings. The final nine replays each
repeat exactly and save a decodable JPEG. Visual reviews cover motion edges,
fabric, window controls and night/quay detail. No new Sports capture algorithm
or live capture comparison with the reference application is claimed.
