"""下载官方固定版空间放大权重，校验完成前不加入模型目录。"""
from pathlib import Path
import hashlib,json,subprocess


def main():
    """只新增候选权重，不覆盖任何生产模型。"""
    root=Path('/opt/yuyutian/mytools/runtime/video-generation')
    folder=root/'runtime-v1/ComfyUI/models/latent_upscale_models';folder.mkdir(exist_ok=True)
    name='ltxv-spatial-upscaler-0.9.8.safetensors'
    target=folder/name;partial=folder/(name+'.partial')
    digest='5b076031c6f860db9037a54f3bb819f10bfb5532ea26a6d30062292428a0c208'
    url='https://huggingface.co/Lightricks/LTX-Video/resolve/8984fa25007f376c1a299016d0957a37a2f797bb/'+name
    if not target.exists():
        subprocess.run(['curl','-fsSL','--http1.1','--retry-all-errors','--proxy','http://127.0.0.1:17890','--retry','2','--connect-timeout','20','--max-time','600','--continue-at','-','-o',str(partial),url],check=True)
        assert partial.stat().st_size==505024432
        with partial.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==digest
        partial.rename(target);target.chmod(0o644)
    with target.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==digest
    result={'url':url,'sha256':digest,'bytes':target.stat().st_size,'verified':True}
    (root/'ltx-upscaler-20260927.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))


if __name__=='__main__':main()
