# Sports processing review (P450)

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
