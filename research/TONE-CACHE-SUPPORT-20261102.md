# Tone cache and rim support

Rootless OPPO PHY110 / Adreno750. Identical complete post inputs, settings, dimensions and explicit OFF/ON switches. Two warmups; eight balanced timed runs per12MP case and four per50MP case. No acquisition, RAW merge or JPEG encoding is timed here.

| Case | OFF ms | ON ms | Gain ms |
| --- | ---: | ---: | ---: |
| 12-night19 | 699.42 | 547.64 | 151.77 |
| 12-detail30 | 649.13 | 536.95 | 112.18 |
| 12-sky30 | 710.02 | 587.75 | 122.27 |
| 12-moving8 | 849.27 | 647.94 | 201.33 |
| window111118 | 992.56 | 853.15 | 139.41 |
| window111305 | 810.35 | 711.77 | 98.58 |
| 50-sabre2x | 2660.16 | 2172.45 | 487.72 |
| 50-moving2x | 2661.13 | 2265.42 | 395.71 |
| 12-detail30-agx | 685.70 | 546.16 | 139.54 |
| 12-detail30-defaulttone | 667.11 | 528.66 | 138.45 |
| 12-sky30-hdr | 766.39 | 658.18 | 108.20 |
| window111118-film50 | 1595.12 | 1487.00 | 108.12 |
| 12-detail30-film50 | 1475.91 | 1303.41 | 172.50 |

All complete RGBA bytes, alpha, target-window/radio/detail crops and actual HDR gain-map bytes match exactly. AgX, custom tone, sky/HDR, partial-strength film, eight-normal Moving and50MP Sabre2x are exercised. The two50MP cases are Sabre2x output from12MP RAW, not native50MP Quad2x2. Raw and accepted frame counts are preserved; the first night series has19 normals, not30.

The rim certificate skips only tiles proven to have zero original light-rim support; nonfinite/uncertain tiles retain the original scan. The scene plane stores unsharpened luminance in R32F and reuses it for edge protection. Eligibility bounds it to256MiB, known memory headroom and renderable R32F. Missing capability, memory decline, allocation or compilation failure retains the original path. A different GPU may round differently; these exact-byte results describe this physical driver, not all hardware.

Both optimizations are enabled by default after this matrix. Independent rollback: `hybrid_why_rim_support 0` and `hybrid_why_edge_luma_cache 0`. Rejected post experiments were subsequently removed from production sources. The pruned QA APK2a46750b repeats all eleven12MP cases with complete RGBA, alpha and HDR gain maps byte-identical to this earlier matrix and to its own OFF route (`../evidence/vf-processing-20261102/post-prune/physical/final12-parity.json`). That revision check has only two timed samples per state and does not replace the larger timing matrix above. A synchronized attribution run lowers WhyCombine node median189.5 to80ms, but glFinish changes overlap and those instrumented stage times must not be summed into production latency.

Evidence: `../evidence/vf-processing-20261102/combined-post-review/summary.json`; complete outputs, crops and logs are retained under `D:/burst/post-combined-20261102/results/`. Physical Mali and unavailable devices remain separate validation gaps. These post gains do not satisfy the full WhyCam+500ms delivery gate. No push is implied.
