"""将已校验的新闻要点绘成明确标记的图解，不生成或冒充新闻照片。"""
import os
from pathlib import Path
from urllib.parse import urlsplit


def create_infographic(item, analysis, destination):
    """原文无图时展示结构化要点，正文证据不足时显示材料限制。"""
    from PIL import Image, ImageDraw, ImageFont
    candidates = [os.environ.get("NEWS_REPORT_FONT", ""),
                  "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                  "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                  "/System/Library/Fonts/STHeiti Light.ttc"]
    font_path = next((path for path in candidates if path and Path(path).is_file()), None)
    if not font_path:
        raise ValueError("REPORT_CJK_FONT_REQUIRED")
    title_font = ImageFont.truetype(font_path, 45)
    body_font = ImageFont.truetype(font_path, 32)
    small_font = ImageFont.truetype(font_path, 24)
    measure = ImageDraw.Draw(Image.new("RGB", (1, 1)))

    def wrap(text, font, width):
        lines, line = [], ""
        for character in text:
            if character == "\n" or measure.textlength(line + character, font=font) > width:
                lines.append(line); line = "" if character == "\n" else character
            else:
                line += character
        if line:
            lines.append(line)
        return lines or [""]

    heading = wrap(analysis["title"], title_font, 1020)[:3]
    points = analysis["points"] if item.get("detailAvailable") else [analysis["summary"], analysis["limitation"] or "\u539f\u6587\u8bc1\u636e\u4e0d\u8db3\uff0c\u8bf7\u67e5\u9605\u6765\u6e90\u3002"]
    blocks = [wrap(point, body_font, 930) for point in points]
    header_height = 140 + len(heading) * 62
    height = header_height + sum(70 + len(lines) * 47 for lines in blocks) + 120
    image = Image.new("RGB", (1200, height), "#edf3f8")
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 1200, 14), fill="#176b95")
    label = "\u8981\u70b9\u56fe\u89e3" if item.get("detailAvailable") else "\u6750\u6599\u63d0\u793a"
    draw.text((64, 48), label + " / SOURCE BRIEF", font=small_font, fill="#176b95")
    y = 102
    for line in heading:
        draw.text((64, y), line, font=title_font, fill="#17384e"); y += 62
    y = header_height
    for index, lines in enumerate(blocks, 1):
        block_height = 50 + len(lines) * 47
        draw.rounded_rectangle((56, y, 1144, y + block_height), radius=20, fill="white")
        draw.ellipse((80, y + 25, 124, y + 69), fill="#176b95")
        draw.text((92, y + 29), str(index), font=small_font, fill="white")
        for offset, line in enumerate(lines):
            draw.text((150, y + 24 + offset * 47), line, font=body_font, fill="#263e50")
        y += block_height + 20
    footer = "\u6839\u636e\u539f\u6587\u6574\u7406\u7684\u56fe\u89e3\uff0c\u975e\u65b0\u95fb\u73b0\u573a\u7167\u7247 / " + (urlsplit(item["url"]).hostname or "")
    draw.text((64, height - 64), footer, font=small_font, fill="#526c80")
    image.save(destination, format="PNG", optimize=True)
