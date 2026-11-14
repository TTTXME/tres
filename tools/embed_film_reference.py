from pathlib import Path
import argparse,hashlib,json,struct,subprocess,sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
import film_pack

# P683 (APK and install size): resources stored with tools/film_pack.py's codec (delta + shuffle + deflate of 4-byte words;
# film-reference-resources.cpp decodes them at their first use into the same bytes). With useLegacyPackaging the library is
# also extracted on install: the Hanatos spectra were 11.94 MB of its rodata, 2.49 MB now.
PACKED=('SpektraHanatos2025Spectra.f32',)

def resource_id(name):
    value=0xcbf29ce484222325
    for byte in name.encode('utf-8'):value=((value^byte)*0x100000001b3)&0xffffffffffffffff
    return value

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--compiler',type=Path,required=True)
    parser.add_argument('--shaders',type=Path,required=True)
    parser.add_argument('--floats',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    shader_output=args.output/'shaders';shader_output.mkdir(exist_ok=True)
    resources=[]
    for source in sorted(args.shaders.glob('*.comp')):
        compiled=shader_output/(source.name+'.spv')
        subprocess.run([str(args.compiler),'-O','--target-env=vulkan1.0',str(source),'-o',str(compiled)],check=True)
        data=compiled.read_bytes()
        if len(data)%4 or struct.unpack_from('<I',data)[0]!=0x07230203:raise ValueError('Invalid SPIR-V: '+source.name)
        resources.append(('shaders/'+compiled.name,data))
    for name in ('SpektraHanatos2025Spectra.f32','SpektraOutputGamutCompression.f32'):
        data=(args.floats/name).read_bytes()
        if not data or len(data)%4:raise ValueError('Invalid float resource: '+name)
        resources.append((name,data))
    if len(resources)!=13:raise ValueError('Unexpected embedded film resource count')
    bundle=bytearray();rows=[];identities=set()
    for name,data in resources:
        identity=resource_id(name)
        if identity in identities:raise ValueError('Resource ID collision')
        identities.add(identity)
        bundle.extend(bytes((-len(bundle))%16))
        codec,stored=0,data
        if name in PACKED:
            codec,stored=1,film_pack.encode(data,4)
            if film_pack.decode(stored,4,len(data)//4)!=data:raise ValueError('Packed resource does not decode: '+name)
        rows.append(dict(name=name,id=f'{identity:016x}',offset=len(bundle),bytes=len(stored),codec=codec,plainBytes=len(data),
                         sha256=hashlib.sha256(data).hexdigest()))
        bundle.extend(stored)
    target=args.output/'embedded_film_data.bin';target.write_bytes(bundle)
    index='#pragma once\nstruct FilmResourceIndex { uint64_t id; size_t offset, bytes; uint32_t codec; size_t plain; };\n'
    index+='static constexpr size_t kFilmResourceBytes='+str(len(bundle))+';\n'
    index+='static constexpr FilmResourceIndex kFilmResourceIndex[] = {\n'
    index+=''.join('    {0x'+row['id']+'ull, '+str(row['offset'])+', '+str(row['bytes'])+', '+str(row['codec'])+', '+str(row['plainBytes'])+'},\n' for row in rows)+'};\n'
    (args.output/'embedded_film_index.h').write_bytes(index.encode())
    path=target.resolve().as_posix().replace('"','\\"')
    assembly='''asm(R"scamera(
.pushsection .rodata.scamera_film,"a",%progbits
.balign 16
.global scamera_film_blob
.hidden scamera_film_blob
.type scamera_film_blob,%object
scamera_film_blob:
.incbin "'''+path+'''"
.size scamera_film_blob, . - scamera_film_blob
.popsection
)scamera");
'''
    (args.output/'embedded_film_data.cpp').write_bytes(assembly.encode())
    manifest=dict(format=2,bundleBytes=len(bundle),bundleSha256=hashlib.sha256(bundle).hexdigest(),resources=rows)
    (args.output/'embedded_film_manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps(dict(resources=len(rows),bytes=len(bundle),sha256=manifest['bundleSha256'])))

if __name__=='__main__':main()
