"""生成有尺寸与字节上限的预览，保留原文件并去除输出中的私密元数据。"""
import io
import sys
import warnings
from PIL import Image, ImageOps

Image.MAX_IMAGE_PIXELS = 40_000_000
warnings.simplefilter('error', Image.DecompressionBombWarning)
edge = int(sys.argv[1])
if edge not in (512, 2048):
    raise ValueError('invalid_preview_edge')
with Image.open(sys.stdin.buffer) as source:
    if source.width * source.height > 40_000_000:
        raise ValueError('preview_pixel_limit')
    source.seek(0)
    # 先缩放再应用方向，避免 JPEG 原图在方向校正时提前完整解码。
    source.thumbnail((edge, edge), Image.Resampling.LANCZOS, reducing_gap=2.0)
    image = ImageOps.exif_transpose(source)
    if image.mode in ('RGBA', 'LA') or 'transparency' in image.info:
        rgba = image.convert('RGBA')
        image = Image.new('RGB', rgba.size, 'white')
        image.paste(rgba, mask=rgba.getchannel('A'))
    else:
        image = image.convert('RGB')
    maximum = 192 * 1024 if edge == 512 else 1024 * 1024
    for quality in (82, 72, 60, 45):
        output = io.BytesIO()
        image.save(output, format='JPEG', quality=quality, optimize=True)
        if output.tell() <= maximum:
            sys.stdout.buffer.write(output.getvalue())
            break
    else:
        raise ValueError('preview_byte_limit')
