"""使用离线 Chromium 排版文本，禁用活动内容和外部资源。"""
import html
import json
import os
from pathlib import Path
import sys
from markdown_it import MarkdownIt
from playwright.sync_api import sync_playwright


def render(request: dict, output: Path) -> None:
    """将白名单 Markdown 元素排版为可检验 PDF。"""
    content = request.get('content', {}).get('text')
    if content is None:
        content = Path(request['inputPaths'][0]).read_text(encoding='utf-8')
    if not content.strip() or len(content.encode('utf-8')) > 262144:
        raise ValueError('PDF_003')
    if request['operation'] == 'TEXT_TO_PDF':
        body = '<pre>' + html.escape(content) + '</pre>'
    else:
        parser = MarkdownIt('commonmark', {'html': False, 'linkify': False}).enable('table')
        tokens = parser.parse(content)
        # 图片仅引用冻结的受管附件，不允许外部网络和宿主文件路径。
        for token in tokens:
            for child in token.children or []:
                if child.type == 'image':
                    source = child.attrGet('src') or ''
                    identity = source.removeprefix('attachment:').removeprefix('//')
                    if not source.startswith('attachment:') or identity not in request.get('attachments', {}):
                        raise ValueError('PDF_006')
                    child.attrSet('src', 'https://pdf.invalid/attachment/' + identity)
        # 链接仅显示文字，避免生成活动注释后又被内核拒绝。
        for token in tokens:
            for child in token.children or []:
                if child.type in ('link_open', 'link_close'):
                    child.tag = 'span'
                    child.attrs = {}
        body = parser.renderer.render(tokens, parser.options, {})
    font = Path(os.environ['PDF_FONT_FILE'])
    if not font.is_file():
        raise ValueError('PDF_009')
    font_url = 'https://pdf.invalid/font'
    options = request.get('options', {})
    markup = '<!doctype html><meta charset="utf-8"><style>' + f'@font-face{{font-family:PdfFont;src:url({font_url})}}' + '''
    body{font-family:PdfFont,sans-serif;font-size:12pt;line-height:1.6;color:#17211c}
    p,li{overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-family:PdfFont,monospace}
    h1,h2,h3{break-after:avoid}table{border-collapse:collapse;width:100%;table-layout:fixed}
    td,th{border:1px solid #aaa;padding:6px;overflow-wrap:anywhere}thead{display:table-header-group}
    img{max-width:100%}a{color:inherit;text-decoration:none}
    </style>''' + body
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, args=['--disable-dev-shm-usage'])
        context = browser.new_context(java_script_enabled=False, service_workers='block', locale='zh-CN', timezone_id='UTC')
        def resource(route):
            if route.request.url == font_url:
                route.fulfill(path=str(font), content_type='font/ttf')
            elif route.request.url.startswith('https://pdf.invalid/attachment/'):
                identity = route.request.url.rsplit('/', 1)[-1]
                attachment = request.get('attachments', {}).get(identity)
                if attachment:
                    route.fulfill(path=attachment['path'], content_type=attachment['mediaType'])
                else:
                    route.abort()
            else:
                route.abort()
        context.route('**/*', resource)
        page = context.new_page()
        page.set_content(markup, wait_until='networkidle', timeout=30000)
        page.evaluate('document.fonts.ready')
        if not page.evaluate('Array.from(document.fonts).length > 0 && Array.from(document.fonts).every(font => font.status === \"loaded\")'):
            raise ValueError('PDF_009')
        if not page.evaluate('Array.from(document.images).every(image => image.complete && image.naturalWidth > 0)'):
            raise ValueError('PDF_006')
        margin = str(options.get('marginMm', 15)) + 'mm'
        page.pdf(path=str(output / 'result.pdf'), format=options.get('paperSize', 'A4'),
                 print_background=True, margin={side: margin for side in ('top','bottom','left','right')},
                 display_header_footer=options.get('pageNumbers', False), header_template='<span></span>',
                 footer_template='<div style="font-size:9px;width:100%;text-align:center"><span class="pageNumber"></span></div>')
        context.close()
        browser.close()


if __name__ == '__main__':
    out = Path(sys.argv[2])
    try:
        render(json.loads(Path(sys.argv[1]).read_text()), out)
    except Exception as error:
        code = str(error) if str(error) in {'PDF_003','PDF_006','PDF_009'} else 'PDF_010'
        (out / 'error.json').write_text(json.dumps({'code': code}))
        sys.exit(1)
