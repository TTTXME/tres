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
    expected=[
        'c1579fcfe3773556b1af67cfeb7d0c8eb91f4926714de587e29c890995d4f522',
        '8efcb27dcbae3f464f66e8ed5ccc65a0abaaf42ede279ac1a55236733e45885b',
        '078f4c00337ef4f5ac6ab4ca19160a3de92a1e765361f5c9835d5fdb39fff5a1',
        '69a75731a4d6492159b5d8403e8f018fce5c9cdc032ffcb29a01e7485e889777',
        '6ea4c1b9807573920124c5586ff59a8a0b68980401b71c8260e0a723a9604dab',
        'f04cd44c2cfcebcbec40a8aacf5e4ec67e84a597c7c0ccd9ec17d8fe82c00c5a',
        '350bb550e32c7965fb71dbb221684848df5599f51b251c557a0e511efca6b6c5',
        '4694ea54cf92a7acd2ca5907a6ac18aaa45ba445565bb165e99bd7a1256b4d02']
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
