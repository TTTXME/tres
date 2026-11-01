# init35 processing defaults

Owner request, 2026-11-01. Both supplied `init35_burial_v4_test.xml` files have
SHA256 `49971c1e01656fbe7c3639e2ff3b0b258237df2375a5da810faeab8200b46198`.
Only global processing parameters are applicable here. Camera IDs, sensor modes,
per-lens JSON, calibration, RAW capture, exposure scheduling and storage paths
are excluded. Existing stored settings keep priority over new defaults.

## Changed defaults

| Reference key suffix | SCAMERA WHY suffix | Old | New |
|---|---|---:|---:|
| smart_hdr_ae_target | ae_target | .15 | .18 |
| smart_hdr_bright_thresh | bright_thresh | .9 | 1 |
| smart_hdr_dark_thresh | dark_thresh | 12.5 | 10 |
| smart_hdr_dark_pixel_thresh | dark_pixel_thresh | 22 | 22.5 |
| smart_hdr_hl_boost | hl_boost | 1.1 | 1 |
| sharp_ef_highlight_str | highlight_str | 2.1 | 2 |
| ef_weight_ext_hl | classic_weight_ext_hl | .7 | 1 |
| ef_weight_mid | classic_weight_mid | 2 | 1 |
| ef_weight_shadow | classic_weight_shadow | 1 | 1.5 |
| sharp_ef_macro_contrast | macro_contrast | 1.1 | 1 |
| sharp_bilateral_radius | sharp_bilateral_radius | .5 | 0 |
| sharp_ef_blend_smoothness | blend_smoothness | .45 | 1 |

Bilateral radius is absent from the XML. Its effective reference default is 0:
native preference loading at 0x186fcc stores zero at settings+0x7c; the amount
at +0x80 defaults to 1. This disables that additional sharpening pass rather
than applying the stale radius/amount aliases elsewhere in the XML.

## Already equal

Resolve: factor .4, static revert .85, motion revert .5, grain .15.
Separate multiscale: enabled, model/shadow 1.5, luma .2 with levels .4/.1/.35,
chroma .5 with levels .2/.4/.6. USM radius .1, amount .5, threshold 0;
RL1 kernel2/radius1/amount1/3 iterations, RL2 disabled, RL3 kernel0/radius.5/
amount1.5/3 iterations; damping0 and halo100/margin.15/macro.4.
AgX look4/slope2.45/SP1.35/TP2/EV range -8.5..3.5, inset .5, saturation1,
hue lock1. Gamma2.2/type0 is pure power in both implementations (reference
s078 display-gamma functions), not an instruction to add another gamma pass.

## Semantic exceptions

`pref_ae_night_dim_key=.05` in reference 2.10 is a **noise tolerance**, read at
native 0x16a4b4 from settings+0x13c and divided by effective noise standard
deviation. Our existing `night_thresh=.05` is that tolerance. Our `night_dim`
is an older exposure exponent; changing it to .05 would be an incorrect port.
Keep that exponent at1 and retain the separate P442 night-exposure protection.
The reference's older ISO threshold is not substituted for the noise tolerance.

The classic fusion layout uses the new weights. Alternative legacy-layout
weights are unchanged. Reference ParamRegistry maps operator3 to the custom
curve, not AgX (operator0). Its17 global points exactly match our existing
ToneCurve.SETTING_POINTS; the custom-curve default stays enabled. The listed
AgX controls are retained for that alternative and do not replace the curve.
No extra hardware or capture setting is inferred from a similarly named key.
