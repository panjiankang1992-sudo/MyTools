"""使用 Trafilatura 离线提取已通过网络与身份校验的网页，不自行访问网络。"""
import json
import sys
from pathlib import Path

import trafilatura

MAX_BYTES = 2 * 1024 * 1024


def extract_html(html, url):
    """提取正文而非评论、表格或导航，保留有界纯文本供 DSH 分析。"""
    if len(html.encode("utf-8")) > MAX_BYTES:
        raise ValueError("ARTICLE_INPUT_TOO_LARGE")
    text = trafilatura.extract(html, url=url, output_format="txt", favor_precision=True,
                               include_comments=False, include_tables=False,
                               include_images=False, include_links=False, deduplicate=True)
    return {"text": (text or "")[:4000], "method": "TRAFILATURA", "version": trafilatura.__version__}


def main():
    """只读取调用者准备的临时文件，将结果写入新文件，异常由 Java 调用方降级。"""
    if len(sys.argv) != 4:
        raise ValueError("ARTICLE_ARGUMENTS_INVALID")
    source, target, url = sys.argv[1:]
    if Path(source).stat().st_size > MAX_BYTES:
        raise ValueError("ARTICLE_INPUT_TOO_LARGE")
    result = extract_html(Path(source).read_text(encoding="utf-8"), url)
    with Path(target).open("x", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False)


if __name__ == "__main__":
    main()
