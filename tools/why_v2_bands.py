#!/usr/bin/env python3
"""P677: WhyV2Fusion's edge-aware bands (V1's P391 rule on V2's pyramid) as WhyV2Fusion draws them, for the host chains
(why_v2_host.render, the full-chain renderers): passes(api, ...) -> the planes the coarsest / collapse passes read, and their
uniforms. api: an object with target(w, h, comps, linear) and draw(shader name without why/, defines, out, [(sampler, tex)],
uniforms); the constants are WhyTone's (check_why_pyramid_bands.shipped)."""
import check_why_pyramid_bands as cwb

REF_DOWN = cwb.REF_DOWN
# host A/B knobs: the strength (None: WhyTone's) and the first level whose band takes the share (0: every level, as V1)
STRENGTH = None
FROM_LEVEL = 0
RAMP = None          # (lo, hi) EV of a step in place of WhyTone's bands lo / hi


def sizes(lv):
    """V2's level sizes, REF_DOWN - 1 levels beyond the pyramid's (WhyV2Ae.levels' halving)."""
    out = [tuple(x) for x in lv]
    while len(out) < len(lv) + REF_DOWN - 1:
        w, h = out[-1]
        out.append((max(w >> 1, 1), h // 2 if h > 3 else 1))
    return out


def guide_noise(s, o, ae, scale=1.0):
    """The guide I's noise (x AE): variance S' I + O' from the clean RGB's S x + O (WhyFusion.guideNoise at the full grid)."""
    if not (s > 0 or o > 0) or not ae > 0:
        return None
    return (scale * ae * max(s, 0.0), scale * ae * ae * max(o, 0.0))


def passes(api, prep_uniforms, lv, noise=None, bands=None):
    """GF (level 0 .. n + REF_DOWN - 2), D0 and the shares A (levels 1 .. n - 1) -> dict(GF, D0, A, bands); prep_uniforms: those
    of why/v2_ef_prep MODE 0 (the clean RGB is the caller's 'u_tex_clean_rgb' texture, passed in prep_uniforms['_clean'])."""
    b = list(bands or cwb.BANDS)
    if RAMP is not None:
        b[0], b[1] = RAMP
    n = len(lv)
    sz = sizes(lv)
    clean = prep_uniforms['_clean']
    up = {k: v for k, v in prep_uniforms.items() if k != '_clean'}
    up['u_band_floor'] = float(b[2])
    GF = [api.target(sz[0][0], sz[0][1], 2, True)]
    api.draw('v2_ef_prep', {'MODE': '2'}, GF[0], [('u_tex_clean_rgb', clean)], up)
    for i in range(1, len(sz)):
        GF.append(api.target(sz[i][0], sz[i][1], 2, True))
        api.draw('v2_pyr_down', {}, GF[i], [('u_tex_src', GF[i - 1])],
                 {'u_texel_size': (1.0 / sz[i - 1][0], 1.0 / sz[i - 1][1]), 'u_dst_w': sz[i][0], 'u_dst_h': sz[i][1]})
    k = cwb.EDGE_SIGMAS
    D = []
    for l in range(n - 1):
        D.append(api.target(sz[l][0], sz[l][1], 1, False))
        nu = (k * cwb.edge_beta(l), noise[0], noise[1], float(b[2])) if noise and k > 0 else (0.0, 0.0, 0.0, float(b[2]))
        api.draw('v2_ef_bands', {'MODE': '0'}, D[l], [('u_tex_GF', GF[l]), ('u_tex_ref', GF[l + REF_DOWN])],
                 {'u_width': sz[l][0], 'u_height': sz[l][1], 'u_band_noise': nu})
    A = [None]
    r0 = cwb.edge_ramp(b, 0)
    for l in range(1, n):
        A.append(api.target(sz[l][0], sz[l][1], 2, False))
        ramp = cwb.edge_ramp(b, l)
        tx = [('u_tex_fine', D[0] if l == 1 else A[l - 1])]
        tx.append(('u_tex_D', D[l] if l < n - 1 else D[0]))
        api.draw('v2_ef_bands', {'MODE': '1'}, A[l], tx,
                 {'u_width': sz[l][0], 'u_height': sz[l][1], 'u_band_edge': (ramp[0], ramp[1], ramp[2], 0.0),
                  'u_band_reach': (-1 if l == n - 1 else int(b[4]), int(b[4]) if l == 1 else 1),
                  'u_band_carry': (r0[0], r0[1], r0[2]) if l == 1 else (1.0, 0.0, 0.0)})
    return dict(GF=GF, D0=D[0], D=D, A=A, bands=b)


def coarsest(st, n):
    """The coarsest pass's BANDS extras: (defines, textures, uniforms)."""
    return {'BANDS': '1'}, [('u_tex_GF', st['GF'][n - 1]), ('u_tex_share', st['A'][n - 1])], {'u_band_strength': strength(st, n - 1)}


def strength(st, l):
    s = float(st['bands'][3]) if STRENGTH is None else float(STRENGTH)
    return s if l >= FROM_LEVEL else 0.0


def collapse(st, l):
    """The collapse pass of level l's BANDS extras: (defines, textures, uniforms)."""
    b = st['bands']
    tx = [('u_tex_GF_fine', st['GF'][l]), ('u_tex_GF_coarse', st['GF'][l + 1]), ('u_tex_share', st['D0'] if l == 0 else st['A'][l])]
    if l == 0:
        r0 = cwb.edge_ramp(b, 0)
        un = {'u_band_edge': (r0[0], r0[1], r0[2], strength(st, 0)), 'u_band_reach': int(b[4])}
    else:
        un = {'u_band_edge': (0.0, 0.0, 0.0, strength(st, l)), 'u_band_reach': -1}
    return {'BANDS': '1'}, tx, un


def release(st):
    for t in st['GF'] + st['D'] + st['A'][1:]:
        t.release()
