# Nubia Z17 processing evidence, 2026-11-01

## Supplied reference log

`WhyCam_Log_2026-11-01.txt`, successful snapshot 2 at 20:33:50:

| Stage | Time |
| --- | ---: |
| Input | 15 frames, 5488 x 4112 |
| Sabre merge | 4166 ms |
| Three-pass Resolve | 312 ms |
| LSC and CCM | 390 ms |
| Complete GL postprocessing | 973 ms |
| Native pipeline | 6013 ms |
| Native call including JPEG | 6456 ms |

Stage timings overlap asynchronous GPU work and must not be summed as an exact
accounting identity. The 6456 ms starts at the native processing call, after the
snapshot already exists; it is not capture latency. The post path reports zero
USM radius and disabled RL stages. Earlier SCAMERA shots used active Airy and
Gaussian RL. These are different captures and processing settings, not a matched
quality or algorithm benchmark.

Earlier crashes in this reference log belong to the reference application's
shader compiler: `libllvm-glnext.so`, `TParseContext::handleArrayIndexing`, during
`glCompileShader`. They are not SCAMERA crash stacks.

## Supplied SCAMERA diagnostic ZIP

`SCAM-20261101-140618-391-5a89c493.zip` identifies SCAMERA 31200, 9fc12cbf,
camera 2, 5488 x 4112, ISO 100, exposure 2426671 ns. Ten source records describe
four normal, three long and three extra-short frames. All extra-short frames
were excluded because the base has no qualifying clipped region. Long frames
were refused by alignment; four normal frames entered the merge.

The merge takes 22695.65 ms; complete native stages take 25243.18 ms. The same
phone's earlier two-/three-frame merges took about 21 seconds. Few accepted
frames therefore do not establish that the remaining merge is cheap.

The ZIP itself contains sampled stage images at 914 x 685, reports and metadata
only. The owner subsequently supplied the separate exact RAW burst, JSON and
preview, and explicitly confirmed that the saved photograph is black, without
an application crash. Do not continue treating this as an unconfirmed crash.

`WhyCombine` samples are all zero while `ScamRgb` and `WhyLow` are finite and
nonzero. The failing interval therefore includes the fusion, sharpening and
combine; it is not evidence of an underexposed or empty source capture.

## Supplied exact RAW burst and replay

Preserved outside Git in `D:/burst/Nubia/20261101-140618/`, with a receipt:

- RAW SHA256: `971e7eea97d2ad0f386408ebd21fe4878ab771e2f067ce4252e0bb1adedad7f4`.
- JSON SHA256: `d6892ca767ae3baa30a997b88ae26ddcfb4d79cfd67a6d5cb5987f54528fc08e`.
- Preview SHA256: `86ad21827c81a3409f7b654c617a1693d4cea8211fd5c24d49ae1133bf99ace3`.

The RAW file is NCH v11, exactly 451333568 bytes: 128-byte header, ten 32-byte
records and ten RAW16 planes at 5488 x 4112. CFA 0, white 1023, black 64;
four normal, three long and three extra-short frames. All source planes are
nonempty. JSON tuning fields are empty; the replay uses the exact requested
tuning from the matching diagnostic native-report, including duplicate keys.

On the available OPPO Adreno 750, original/split/split/original worker replays
preserve all ten source frames and apply the original rejection rules. Four
normals are accepted in every run. Complete RGB/effective-map/clip-map outputs
and frame decisions are byte-identical (SHA256
`200926592829bacb5253d28daaaf76410482014fd51a0351719aa774212d253e`).
Worker written times: 9476 / 5961 / 5964 / 9491 ms. The split experiment remains
outside production: this demonstrates a gain on OPPO with Nubia input, not
an Adreno 540 speed measurement or a universal motion-burst gain.

The merged RGB is finite and nonblack (mean 0.1023154, max 0.6977539). Full
postprocessing on OPPO with the frozen Nubia metadata produces a normal window
scene both on the official 31200 APK and the local candidate. Repeats are exact
within each version. Official RGBA SHA256
`a442a1bb5ab1ed5ee15beb81c444c513cfb5c436ff9f94c421d8ba1c1339f77d`;
local candidate `8abaea58eafb89c18fb7364671608ba1ad989c3729148a3ee042713aec9f043a`.
The difference between versions includes the intended denoise port; it does
not establish the cause of the black result on Nubia.

A second control reserves more texture object names than the combined texture
unit limit before the first main texture. Each version still reproduces its
own complete normal output exactly. The independent GLTexture state violation
remains real, but this full-image control does not reproduce the Nubia failure.

The preserved NX563J SCAM TEST report declares GL ES 3.2 driver V@331.0,
MAX_TEXTURE_SIZE 16384, 16 fragment samplers, EXT_color_buffer_float and
OES_texture_float_linear. Do not attribute the black image to a generic 4096
size limit or missing float renderability without an actual failing GL call.

An isolated 23MP PBO-readback extension also preserves the exact image, but
ABBA readback totals on this input were tiles 388/414 ms versus PBO 448/410 ms.
No useful speed gain is established. Its source changes were removed from
production; the experiment patch and receipts remain in the evidence folder.

Scripts and logs: `evidence/gcam61-deep-20261101/replay_nubia_supplied.py`,
`post_nubia_supplied.py`; native receipts in the preserved burst directory and
post results in `D:/burst/Nubia/post-results/`. Remote duplicate outputs were
deleted only after verification against preserved local files. Owner data and
preferences were not cleared. The phone was restored to the official CI APK.

The local diagnostic path now exports `WhyFused`, `WhyLumaS` and (when present)
`WhyDetailRef` immediately before combine closes them. Pending GL errors are
recorded before diagnostic readback clears them. No new sampling occurs in a
normal shot. Three actual OPPO diagnostic replays of this exact burst retain
the complete candidate output hash above; exported fused/sharpened luma are
finite and nonzero. Sampled maxima: fused 0.9463034, sharpened luma 0.94189453,
combine 0.9995117. These additional stages are intended to distinguish the
remaining Nubia failure; their success on OPPO is not the missing Nubia proof.

## Local changes and limits

- GLTexture no longer treats texture object names as texture-unit indices. A
  high-object-name test fails on the old Adreno APK and passes after the fix;
  nine complete post outputs retain their values. Causality for the Nubia black
  photograph is unproved.
- Busy-preview throttling survives long processing, defaults to 2 fps on
  `msm8998` or devices with at most 4.5 GiB RAM, and thermal severity cannot
  raise that cap. Camera delivery and AE remain independent of display pacing.
- Permanent DCIM logs are disabled below the existing 8 Gen 3 device tier,
  without starting their writer, flush timer or storage queue. Explicit debug
  and timing diagnostics remain available separately.
- The ordinary optional RAW viewfinder is disabled on 8 Gen 3 and below, also
  when its saved preference is enabled. Required RAW display on modules with
  no usable ISP preview remains available for ISZ/FULLRES.

Focused host tests and actual Adreno 750 processing replays validate these
code paths and unchanged tested image outputs. They do not measure an Adreno
540 speedup. Only OPPO is connected over ADB. The remaining gate is a current
NX563J execution with the corrected texture binding and per-stage GL evidence;
the owner has confirmed there is no current crash. A black-photo fix on Nubia,
the reference's 6456 ms and GCam 6.1 parity are not claimed.
