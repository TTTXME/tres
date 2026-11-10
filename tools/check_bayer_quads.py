from pathlib import Path
import argparse,hashlib,itertools,json,re,subprocess,tempfile
from check_vf_fused import find_glslc
ROOT=Path(__file__).resolve().parents[1]
def literal(text,name,delimiter=''):
    expression=r'static const char\* '+name+r'=R"'+delimiter+r'\((.*?)\)'+delimiter+r'";'
    found=re.search(expression,text,re.S)
    if not found:raise RuntimeError('Missing shader '+name)
    return found.group(1)
def dezip_source(text,base):
    # P480: hybridDezipSource (the merge's declarations, kHybDezipMerge, before frameSamples)
    head='void frameSamples(inout Acc a,int f,vec2 O,float r,float cover,vec3 P,bool win){\n'
    assert text.count(head)==1
    return text.replace(head,literal(base,'kHybDezipMerge')+head)
def dezip_main(text):
    # P480: hybridDezipMain (the merge's per-colour weight sums stored for kHybDzPass)
    out='        outRgb[o]=col.x;outRgb[o+1]=col.y;outRgb[o+2]=col.z;\n';assert text.count(out)==1
    return text.replace(out,out+'#ifndef DZ_NODEN\n        dzDen[o]=a.den.x;dzDen[o+1]=a.den.y;dzDen[o+2]=a.den.z;\n#endif\n')
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--glslc');parser.add_argument('--output',type=Path)
    args=parser.parse_args();compiler=args.glslc or find_glslc()
    if not compiler:raise RuntimeError('glslc is required')
    base=(ROOT/'app/src/main/cpp/scam-hybrid.h').read_text(encoding='utf8')
    quads=(ROOT/'app/src/main/cpp/scam-bayer-quads.h').read_text(encoding='utf8')
    qmain=literal(quads,'kHybQzMain','QZ');old=literal(base,'kHybMergeMain1')
    needle='for(int q=0;q<4;q++){'
    assert old.count(needle)==1 and qmain==old.replace(needle,'{ int q=int(gl_WorkGroupID.z);',1),'Q-site merge changed beyond invocation ownership'
    # P596 round 2: kHybHelpers' fine-tensor choice (k61cU.z > 0 only on the native Quad; 0 elsewhere = the old branch)
    expected=[
        '654a77c370645a4a3ba27d045243c8cf300cd3ce0daef73df36a74f963faa6f0',
        '36ddccac170cd87b255c16ec544b4e7482486a2cac7f20a9f8e908286d3170ed',
        '7914987e579f7ec825c50004b6804ccab85336fa4a57c4b9492580e61cbcd93a',
        '1d8c4bf85be6e7c0ec342f469c49d233be936e969afd68a6cddc51026137daac',
        'dcea9ba59d7a89c008a34e00b92c3cb6877d1161304744793ed7cdc9db4efc23',
        '567ed507bdc0b438c07d1d59f1b8930318c71b278aa9c1af077479a454e45fef',
        'c278589991379a06dc6b5bea2ccfc7f6cf32a7b314b3be5b354ca66b0a51b44f',
        'c6da09ff3ef8d1d46bfd3d50e053d9bfdd39712c80f95c7068997fcf11bfa88e']
    common=literal(base,'kHybCommon');helpers=literal(base,'kHybHelpers');rows=[];programs=[]
    for index,(local,dezip,jsr) in enumerate(itertools.product([False,True],repeat=3)):
        prefix=common+''.join('#define '+name+' 1\n'for name,on in [('LOCAL_ALIGN',local),('JSR_LCA',jsr)]if on)+helpers
        plain=literal(quads,'kHybQzQuadPlain','QZ')
        body=prefix+(dezip_source(plain,base)+dezip_main(qmain)if dezip else plain+qmain)
        digest=hashlib.sha256(body.encode()).hexdigest()
        assert digest==expected[index],('Validated quad program changed',local,dezip,jsr,digest)
        programs.append(('quads-'+str(index),body));rows.append(dict(local=local,dezip=dezip,jsr=jsr,sha256=digest))
    for fine in [False,True]:
        programs.append(('cells-'+str(int(fine)),common+helpers+('#define CELL_FINE 1\n'if fine else'')+literal(quads,'kHybQzQuadCells','QZ')))
    for local,jsr in itertools.product([False,True],repeat=2):
        prefix=common+''.join('#define '+name+' 1\n'for name,on in [('LOCAL_ALIGN',local),('JSR_LCA',jsr)]if on)+helpers
        programs.append(('qz-'+str(int(local))+str(int(jsr)),prefix+literal(base,'kHybMergeCommon')+qmain))
    with tempfile.TemporaryDirectory(prefix='bayer-quads-shaders-') as tmp:
        folder=args.output or Path(tmp);folder.mkdir(parents=True,exist_ok=True)
        for name,body in programs:
            file=folder/(name+'.comp');file.write_text(body,encoding='utf8')
            subprocess.run([str(compiler),'--target-env=opengl','-fshader-stage=compute','-fauto-map-locations','-fauto-bind-uniforms',str(file),'-o',str(file.with_suffix('.spv'))],check=True)
        if args.output:(folder/'proof.json').write_text(json.dumps(dict(passed=True,programs=len(programs),expandedV3ByteIdentity=rows),indent=2)+'\n')
    print('PASS fourteen actual Q-site/quad/Cells programs; eight frozen source identities')
if __name__=='__main__':main()
