"""将候选实验所有帧整理为可审查联系图，不改动生成结果。"""
import argparse
import json
import tarfile
from pathlib import Path
from PIL import Image, ImageOps, ImageDraw


def main():
    """导出所有帧的缩略图、视频与恢复状态，供逐帧复核。"""
    parser=argparse.ArgumentParser();parser.add_argument('folder',type=Path);parser.add_argument('archive',type=Path)
    args=parser.parse_args();p=args.folder
    assert json.loads((p/'window.json').read_text())['restored'], 'window_not_restored'
    for directory in p.glob('*-frames'):
        frames=sorted(directory.glob('*.png'))
        sheet=Image.new('RGB',(1280,((len(frames)+7)//8)*180),'white');draw=ImageDraw.Draw(sheet)
        for i,f in enumerate(frames):
            with Image.open(f) as image:
                image=ImageOps.contain(image.convert('RGB'),(160,155))
                sheet.paste(image,((i%8)*160,(i//8)*180))
            draw.text(((i%8)*160,(i//8)*180+157),str(i),fill='black')
        sheet.save(p/(directory.name.removesuffix('-frames')+'-contact.jpg'))
    with tarfile.open(args.archive,'w:gz') as archive:
        for f in p.iterdir():
            if f.is_file() and f.suffix in ('.jpg','.png','.json','.jsonl','.mp4'):
                archive.add(f,arcname=f.name)
    print(json.dumps({'restored':True,'archiveBytes':args.archive.stat().st_size}))


if __name__=='__main__':main()
