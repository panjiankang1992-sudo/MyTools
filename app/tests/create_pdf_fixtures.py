"""生成隔离验收样本，构建脚本在结束后删除生成的文件。"""
import io
import sys
from pathlib import Path
from reportlab.pdfgen import canvas
from pypdf import PdfReader, PdfWriter
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
b=io.BytesIO();c=canvas.Canvas(b,pagesize=(640,480));c.setFont('Helvetica',20)
for text,x,y in [('TL',50,390),('TR',490,390),('BL',50,90),('BR',490,90)]:c.drawString(x,y,text)
c.save()
for rotation in [0,90,180,270]:
 w=PdfWriter();p=PdfReader(io.BytesIO(b.getvalue())).pages[0];p.mediabox.lower_left=(20,40);p.mediabox.upper_right=(620,440);p.cropbox.lower_left=(40,60);p.cropbox.upper_right=(600,420);p.rotate(rotation);w.add_page(p)
 with (out/f'pdf-acceptance-{rotation}.pdf').open('wb') as f:w.write(f)
w=PdfWriter();w.add_page(PdfReader(io.BytesIO(b.getvalue())).pages[0]);w.encrypt('test-only-pdf-password')
with (out/'pdf-acceptance-encrypted.pdf').open('wb') as f:w.write(f)
