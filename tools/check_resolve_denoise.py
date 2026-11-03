#!/usr/bin/env python3
"""Production Resolve and Oklab pyramid: independent CPU oracle and full/strip GPU equivalence."""
from pathlib import Path
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
VS = '#version 310 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0.,1.);}'


class Gpu:
    def __init__(self):
        try:
            self.ctx = moderngl.create_standalone_context(backend='egl', require=430)
        except Exception:
            self.ctx = moderngl.create_standalone_context(require=430)
        self.programs = {}

    def tex(self, data, dtype='f2'):
        a = np.asarray(data)
        if a.ndim == 2: a = a[..., None]
        kind = {'f2': np.float16, 'f4': np.float32, 'u1': np.uint8}[dtype]
        t = self.ctx.texture((a.shape[1], a.shape[0]), a.shape[2], a.astype(kind).tobytes(), dtype=dtype)
        t.filter = (moderngl.NEAREST, moderngl.NEAREST) if dtype == 'u1' else (moderngl.LINEAR, moderngl.LINEAR)
        t.repeat_x = t.repeat_y = False
        return t

    def target(self, w, h, dtype='f2'):
        return self.tex(np.zeros((h, w, 4), np.float32), dtype)

    @staticmethod
    def read(t):
        return np.frombuffer(t.read(), np.float16 if t.dtype == 'f2' else np.float32).reshape(t.height, t.width, t.components).astype(np.float64)

    def draw(self, name, out, textures, values, tiled=False, viewport=None):
        key = name, tiled
        if key not in self.programs:
            source = (SH / (name + '.glsl')).read_text(encoding='utf-8')
            source = source.replace('#define TILED 0', '#define TILED %d' % tiled)
            self.programs[key] = self.ctx.program(vertex_shader=VS, fragment_shader='#version 310 es\n' + source)
        p = self.programs[key]
        fb = self.ctx.framebuffer([out]); fb.use()
        for i, (n, t) in enumerate(textures.items()):
            t.use(i)
            if n in p: p[n].value = i
        for n, v in values.items():
            if n in p: p[n].value = v
        vao = self.ctx.vertex_array(p, [])
        self.ctx.viewport = viewport or (0, 0, out.width, out.height)
        vao.render(vertices=3)
        assert self.ctx.error == 'GL_NO_ERROR', (name, self.ctx.error)
        vao.release(); fb.release()


def resolve(g, image, codes, total, rows, dtype='f2'):
    h, w = image.shape[:2]
    inp = g.tex(image, dtype); eff = g.tex(codes, 'u1')
    p0 = g.target(w, min(h, rows + 14), dtype); p1 = g.target(w, min(h, rows + 14), dtype)
    out = g.target(w, h, dtype)
    values = dict(useEffU=1, effScaleU=total/128, framesU=float(total), totalU=float(total), fullSizeU=(w, h),
                  noiseModelU=(.562*.001, .25*.001, .25*.001, .000002), factorU=.4, outlierU=.125,
                  rejectRevertU=.5, globalRevertU=.85, grainU=0., vignetteBoostU=0., vignetteScaleU=1.)
    for y in range(0, h, rows):
        count = min(rows, h-y); first = max(0, y-7); height = min(h, y+count+7)-first
        source = inp
        for pas, k in enumerate((1, 2, 4)):
            target = (p0, p1, out)[pas]
            g.draw('scamdn/why_resolve', target, dict(InputBuffer=source, EffMap=eff),
                   dict(values, passU=pas, stepU=k, inputOriginU=0 if pas == 0 else first, outputOriginU=0 if pas == 2 else first),
                   viewport=(0, y if pas == 2 else 0, w, count if pas == 2 else height))
            source = target
    result = g.read(out)
    for t in (inp, eff, p0, p1, out): t.release()
    return result


def oracle(image, codes, total):
    """Float64 equations, without GPU textures, integer addressing or half stores."""
    rgb = image[..., :3].astype(np.float64)
    y = rgb @ np.array([.2126390, .7151688, .0721923])
    q = np.stack((y, y-rgb[..., 0], y-rgb[..., 2]), -1)
    n = np.where(codes > 0, codes*(total/128), total).clip(1, total)
    blurry = np.clip(1.25-.25*n, 0, 1)
    fallback = np.clip(1-(n-1)/max(1, total*.7), 0, 1)
    risk = np.maximum(blurry, fallback)
    for pas, k in enumerate((1, 2, 4)):
        padded = np.pad(q, ((k,k), (k,k), (0,0)), mode='symmetric')
        h, w = q.shape[:2]
        sample = {(x,z): padded[k+z*k:k+z*k+h, k+x*k:k+x*k+w] for z in (-1,0,1) for x in (-1,0,1)}
        key = q[..., 0]
        if pas == 0:
            cross = sum(sample[x,z][..., 0] for x,z in ((-1,-1),(1,-1),(0,0),(-1,1),(1,1)))*.2
            key = key+(cross-key)*blurry
        var = (np.abs(q)*np.array([.562*.001,.25*.001,.25*.001])).sum(-1)+.000002
        inv = n/(np.maximum(1e-6,var)*.4)*(.5/np.log(2))
        a=q*.25; wide=a.copy(); sw=np.full((h,w),.25); ww=sw.copy()
        for x,z in ((-1,-1),(0,-1),(1,-1),(1,0)):
            pair = sample[x,z]+sample[-x,-z]
            d = pair[..., 0]*.5-key
            spatial = (.5 if x == 0 else .25)*(.5 if z == 0 else .25)
            weight = spatial*np.exp2(-d*d*inv); wider=spatial*np.exp2(-d*d*inv*.125)
            a += pair*weight[...,None]; wide += pair*wider[...,None]; sw += 2*weight; ww += 2*wider
        den=a/sw[...,None]; broad=wide/ww[...,None]
        t=np.clip((sw-.25)/.25,0,1); blend=(1-t*t*(3-2*t))*blurry
        den += (broad-den)*blend[...,None]
        guided=q[...,1:]*.25; weights=np.full((h,w),.25)
        sy=np.maximum(.04,.22*q[...,0])
        for (x,z),v in sample.items():
            if x == z == 0: continue
            weight=(.5 if x == 0 else .25)*(.5 if z == 0 else .25)*np.exp(-(v[...,0]-q[...,0])**2/(2*sy*sy))
            guided += v[...,1:]*weight[...,None]; weights += weight
        mix=np.maximum(.65,risk) if pas < 2 else np.where(risk>.05,risk,0)
        den[...,1:] += (guided/weights[...,None]-den[...,1:])*mix[...,None]
        den[...,0] += (q[...,0]-den[...,0])*(.85+(.5-.85)*risk)
        q=den
    y,u,v=np.maximum(q[...,0],0),q[...,1],q[...,2]
    return np.maximum(np.stack((y-u,y+.297327*u+.100944*v,y-v),-1),0)


def pyramid(g, image, rows):
    h,w=image.shape[:2]; result=np.empty_like(image); grids={}
    for y in range(0,h,rows):
        count=min(rows,h-y); first=max(0,y-64); last=min(h,y+count+64); tiled=rows<h; owned=[]
        def texture(level):
            full=(w>>level,h>>level); start=first>>level
            t=g.target(full[0],(last>>level)-start); grids[t]=(full,start); owned.append(t); return t
        def draw(name,target,bindings,extra=None):
            full,start=grids[target]; values=dict(u_width=full[0],u_height=full[1],originDstU=start)
            values.update(extra or {})
            if tiled:
                for n,t in bindings.items(): values['full_'+n],values['origin_'+n]=grids[t]
            g.draw('resolvedn/'+name,target,bindings,values,tiled=tiled)
        inp=g.tex(image[first:last]);grids[inp]=((w,h),first);owned.append(inp)
        clean=texture(0)
        draw('optics',clean,dict(u_tex_rgb=inp),dict(u_quad_speckle_strength=0.,u_quad_speckle_threshold=.15))
        lab=[texture(0)];draw('lab',lab[0],dict(u_tex_rgb=clean))
        for level in (1,2,3):
            t=texture(level);lab.append(t);sw,sh=grids[lab[level-1]][0];dw,dh=grids[t][0]
            draw('down',t,dict(u_tex_src=lab[level-1]),dict(u_texel_size=(1/sw,1/sh),u_dst_w=dw,u_dst_h=dh))
        rebuilt=texture(3)
        draw('base',rebuilt,dict(u_tex_src=lab[3]),dict(u_chroma_thresh=.006,u_shadow_boost=1.5))
        for level in (2,1,0):
            detail,next_=texture(level),texture(level)
            draw('detail',detail,dict(u_tex_fine=lab[level],u_tex_coarse=lab[level+1],u_tex_sabre_weights=lab[level]),
                 dict(u_has_sabre_weights=0,u_level=level,u_luma_thresh=.003,u_chroma_thresh=.006,u_shadow_boost=1.5))
            draw('rebuild',next_,dict(u_tex_coarse_recon=rebuilt,u_tex_laplace_filt=detail));rebuilt=next_
        out=texture(0);draw('rgb',out,dict(u_tex_oklab=rebuilt))
        result[y:y+count]=g.read(out)[y-first:y-first+count]
        for t in owned: t.release()
    return result


def main():
    g=Gpu();rng=np.random.default_rng(448);checks=0
    for total in (1,4,30):
        image=rng.uniform(.001,.1,(64,64,4)).astype('f4');image[...,3]=1
        codes=rng.integers(0,255,(64,64),dtype='u1')
        got=resolve(g,image,codes,total,64,'f4')[...,:3];expected=oracle(image,codes,total)
        assert np.max(np.abs(got-expected))<4e-7, (total,float(np.max(np.abs(got-expected))))
        whole=resolve(g,image,codes,total,64)
        for rows in (1,13,32):
            assert np.array_equal(whole,resolve(g,image,codes,total,rows)), (total,rows)
            checks+=1
    for w,h in ((256,256),(512,384),(1024,768)):
        image=rng.uniform(.001,.3,(h,w,4)).astype('f4');image[...,3]=np.linspace(1,4,w)[None,:]
        whole=pyramid(g,image,h)
        for rows in (64,128):
            tiled=pyramid(g,image,rows)
            assert np.isfinite(tiled).all() and np.array_equal(whole,tiled), (w,h,rows,float(np.max(np.abs(whole-tiled))))
            checks+=1
    image=np.full((256,256,4),.03,np.float32);image[...,3]=1
    image[...,:3]+=rng.normal(0,.003,(256,256,3))
    filtered=pyramid(g,image,256)
    ky=np.array([.2126390,.7151688,.0721923])
    a,b=image[16:-16,16:-16,:3],filtered[16:-16,16:-16,:3]
    chroma=lambda x:np.stack((x@ky-x[...,0],x@ky-x[...,2]),-1)
    assert (b@ky).std()<.99*(a@ky).std(), 'pyramid did not reduce luminance noise'
    assert chroma(b).std()<.8*chroma(a).std(), 'pyramid did not reduce chroma noise'
    assert np.max(np.abs(b.mean((0,1))-a.mean((0,1))))<2e-4, 'shadow colour mean drift'
    image[:,:128,:3]=.02;image[:,128:,:3]=.2
    filtered=pyramid(g,image,256)
    contrast=filtered[16:-16,132:,:3].mean()-filtered[16:-16,:124,:3].mean()
    assert contrast>.95*.18, 'pyramid blurred a strong physical edge'
    print('Resolve/pyramid PASS:',checks,'full/strip cases; 3 independent Float64 Resolve oracles;',g.ctx.info['GL_RENDERER'])


if __name__ == '__main__': main()
