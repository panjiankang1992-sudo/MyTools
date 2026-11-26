"""对公开固定权重进行四连接分段续传；每段校验长度，合并后校验官方摘要。"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib,json,math,subprocess,shutil


def main():
    """复用本轮已下载前缀，不重复下载或覆盖生产权重。"""
    folder=Path('/opt/yuyutian/mytools/runtime/video-generation/runtime-v1/ComfyUI/models/latent_upscale_models')
    target=folder/'ltxv-spatial-upscaler-0.9.8.safetensors';partial=target.with_suffix('.safetensors.partial')
    total=505024432;digest='5b076031c6f860db9037a54f3bb819f10bfb5532ea26a6d30062292428a0c208'
    url='https://huggingface.co/Lightricks/LTX-Video/resolve/8984fa25007f376c1a299016d0957a37a2f797bb/'+target.name
    start=partial.stat().st_size;assert 0<start<total and not target.exists()
    chunk=math.ceil((total-start)/4)
    parts=[(begin,min(begin+chunk,total)-1,folder/(target.name+f'.part-{i}')) for i,begin in enumerate(range(start,total,chunk))]
    def fetch(part):
        begin,end,path=part;size=end-begin+1
        subprocess.run(['curl','-fsSL','--http1.1','--proxy','http://127.0.0.1:17890','--retry','2','--retry-all-errors',
                        '--connect-timeout','20','--max-time','600','--max-filesize',str(size),'--range',f'{begin}-{end}',
                        '-o',str(path),url+f'?segment={begin}'],check=True)
        assert path.stat().st_size==size,'partial_range_mismatch'
        print(json.dumps({'segment':begin,'bytes':size}),flush=True)
    with ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(fetch,parts))
    merged=folder/(target.name+'.merged')
    with merged.open('wb') as out:
        with partial.open('rb') as source:shutil.copyfileobj(source,out)
        for _,_,path in parts:
            with path.open('rb') as source:shutil.copyfileobj(source,out)
    assert merged.stat().st_size==total
    with merged.open('rb') as source:assert hashlib.file_digest(source,'sha256').hexdigest()==digest
    merged.rename(target);target.chmod(0o644)
    partial.unlink()
    for _,_,path in parts:path.unlink()
    result={'url':url,'sha256':digest,'bytes':total,'verified':True}
    (folder.parents[3]/'ltx-upscaler-20260927.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result))


if __name__=='__main__':main()
