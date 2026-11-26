"""从冻结报告导出公开新闻正文，不携带邮件、节点或模型内部信息。"""
from datetime import datetime
from hashlib import sha256
from html import escape
import json
from io import BytesIO
from pathlib import Path
import re
import warnings
from urllib.parse import urlsplit, urlunsplit, parse_qsl, urlencode
from zoneinfo import ZoneInfo
from news_infographic import create_infographic
from news_analysis import MAX_CATEGORY_ITEMS
from personal_report import CATEGORIES, public_row, safe_url


def clean_text(value, maximum=8000):
    """拒绝非文本与 XML 非法字符，限制公开字段长度。"""
    if not isinstance(value, str) or len(value) > maximum or any(
            ord(c) < 32 and c not in '\n\r\t' or 0xD800 <= ord(c) <= 0xDFFF or ord(c) in (0xFFFE, 0xFFFF) for c in value):
        raise ValueError("RSS_TEXT_INVALID")
    return value.strip()


def public_url(value):
    """公开来源必须是无认证信息的普通 HTTP 链接。"""
    if not safe_url(value) or len(value) > 2048 or '\\' in value:
        raise ValueError("RSS_SOURCE_URL_INVALID")
    return value


def article_identity(url):
    """以原文地址保持跨日身份，去除常见跟踪参数而保留内容参数。"""
    parsed = urlsplit(public_url(url))
    query = [(k, v) for k, v in parse_qsl(parsed.query, keep_blank_values=True)
             if not k.lower().startswith('utm_') and k.lower() not in ('fbclid', 'gclid')]
    canonical = urlunsplit(('https', parsed.netloc.lower(), parsed.path.rstrip('/') or '/', urlencode(sorted(query)), ''))
    return sha256(canonical.encode()).hexdigest()


def page(title, body, base):
    """公开网页只有受控样式和已转义内容。"""
    return ('<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            '<link rel="alternate" type="application/rss+xml" title="MyTools News" href="' + base + '/rss/daily.xml">'
            '<title>' + escape(title) + '</title><style>body{max-width:780px;margin:36px auto;padding:0 20px;'
            'font:16px/1.85 system-ui,sans-serif;color:#172b42}img{max-width:100%;height:auto}h2{margin-top:36px}'
            'a{color:#176b95}small{color:#526c80}</style></head><body><nav><a href="' + base + '/subscribe/">'
            '\u8ba2\u9605\u4e0e\u5386\u53f2</a></nav><h1>' + escape(title) + '</h1>' + body + '</body></html>')


def original_image(item, source_root, assets):
    """复用已采集的文章原图，限制来源归属、目录、格式和解码尺寸。"""
    picture = item.get('image')
    if not source_root or not isinstance(picture, dict) or picture.get('kind') == 'INFOGRAPHIC':
        return None
    from PIL import Image
    try:
        relative = picture.get('path', '')
        if not isinstance(relative, str) or not re.fullmatch(r'images/[A-Za-z0-9_-]+\.(?:jpg|jpeg|png|gif)', relative):
            return None
        root = Path(source_root).resolve()
        path = root / relative
        if path.is_symlink() or path.parent.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= 2 * 1024 * 1024:
            return None
        source = public_url(picture['sourceUrl'])
        public_url(picture['url'])
        if source not in {item['url'], item.get('evidenceUrl'), *item.get('sourceLinks', [])}:
            return None
        payload = path.read_bytes()
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(BytesIO(payload)) as decoded:
                extension = {'JPEG': 'jpg', 'PNG': 'png', 'GIF': 'gif'}.get(decoded.format)
                width, height = decoded.size
                if not extension or not 120 <= width <= 8192 or not 70 <= height <= 8192 or width * height > 20_000_000:
                    return None
                decoded.verify()
        caption = clean_text(picture.get('caption', ''), 1000)
        name = sha256(payload).hexdigest() + '.' + extension
        # 内容寻址原图不转码；已存在文件保持不变，避免改写旧快照的硬链接。
        destination = assets / name
        if not destination.exists():
            destination.write_bytes(payload)
        return {'name': name, 'sourceUrl': source, 'caption': caption}
    except (OSError, KeyError, TypeError, ValueError, SyntaxError, Image.DecompressionBombError, Image.DecompressionBombWarning):
        # 损坏或无法确认归属的原图不能阻断正文，可按名次使用明确标识的图解。
        return None


def export_section(report, section, assets, base, source_root=None):
    """白名单导出分类，优先采用新闻原图，前十条缺图时用要点图解。"""
    weekly = report.get('mode') == 'WEEKLY'
    limit = 20 if weekly else MAX_CATEGORY_ITEMS
    category = section['category']
    if category not in CATEGORIES:
        raise ValueError("RSS_CATEGORY_INVALID")
    moment = datetime.fromisoformat(report['cutoff'].replace('Z', '+00:00'))
    if moment.tzinfo is None:
        raise ValueError("RSS_CUTOFF_INVALID")
    day = moment.astimezone(ZoneInfo(report['zone'])).date().isoformat()
    slug, label, region_code = category.lower(), CATEGORIES[category], ''
    if category == 'LOCAL':
        # 地区字段明确绑定栏目；缺少地区证据不发布泛化的本地源。
        region = report.get('region') or {}
        region_code = region.get('regionCode') or ''
        if weekly and not section['items'] and not region_code:
            # 未定位且没有本地条目时保留空栏目，不猜测发布地区。
            region_code = ''
        elif not re.fullmatch(r'(?:[A-Z]{2}-[A-Z0-9]{1,12}|GEONAME-[0-9]{1,12})', region_code):
            raise ValueError("RSS_REGION_UNRESOLVED")
        if region_code:
            slug += '/' + region_code.lower()
            label += ' ' + region_code
    items = section['items']
    if not (0 if weekly else 1) <= len(items) <= 20 or [i['rank'] for i in items] != list(range(1, len(items) + 1)):
        raise ValueError("RSS_ITEMS_INVALID")
    if len({i['eventId'] for i in items}) != len(items):
        raise ValueError("RSS_ITEMS_INVALID")
    # 兼容已有二十条冻结报告，只公开原排名前十，不改写历史输入。
    items = items[:limit]
    title = day + ' ' + label + '\u65e5\u62a5'
    body = ['<p>' + str(len(items)) + ' \u6761\u7cbe\u9009\uff0c\u524d ' + str(min(10, len(items))) +
            ' \u6761\u56fe\u6587\u8be6\u60c5\u3002</p>']
    if weekly:
        title = day + ' ' + label + '\u4e03\u5929\u8981\u70b9'
        body = ['<p>' + str(len(items)) + ' \u6761\u65b0\u95fb\u8be6\u60c5\u3002</p>']
    if region_code:
        body.append('<p>\u672c\u5730\u8303\u56f4\uff1a' + escape(label) +
                    '\uff08\u6309\u670d\u52a1\u6267\u884c\u5730\u533a\u751f\u6210\uff0c\u975e\u9605\u8bfb\u8005\u5b9a\u4f4d\uff09\u3002</p>')
    image_names, headlines, articles = [], [], []
    for item in items:
        row = item['analysis'] if item.get('rssDetailStatus') == 'ready' else public_row(item, item['analysis'])
        headline = clean_text(row['title'], 1000)
        summary = clean_text(row['summary'])
        limitation = clean_text(row.get('limitation', ''), 2000)
        points = row['points']
        if not isinstance(points, list) or len(points) > 8:
            raise ValueError("RSS_POINTS_INVALID")
        points = [clean_text(p, 2000) for p in points]
        links = list(dict.fromkeys(item.get('sourceLinks') or [item['url']]))
        if not 1 <= len(links) <= (32 if weekly else 30):
            raise ValueError("RSS_SOURCE_URL_INVALID")
        links = [public_url(link) for link in links]
        headlines.append(headline)
        body.append('<h2>' + str(item['rank']) + '. ' + escape(headline) + '</h2>')
        start = len(body)
        article_images = []
        original = original_image(item, source_root, assets)
        image_kind = 'NONE'
        if original:
            name = original['name']
            image_kind = 'SOURCE'
            image_names.append(name)
            article_images.append(name)
            caption = escape(original['caption']) + ' ' if original['caption'] else ''
            body.append('<p><img src="' + base + '/assets/' + name + '" alt="' + escape(headline, quote=True) + '"></p>'
                        '<small>' + caption + '\u539f\u6587\u914d\u56fe\uff0c<a href="' + escape(original['sourceUrl'], quote=True)
                        + '">\u56fe\u7247\u6765\u6e90\uff1a' + escape(urlsplit(original['sourceUrl']).hostname) + '</a></small>')
        elif not weekly and item['rank'] <= 10:
            # 缺少可用原图时才生成图解，保留明确标识，不作为现场照片展示。
            image_kind = 'INFOGRAPHIC'
            temporary = assets / 'brief.png'
            create_infographic({'url': links[0], 'detailAvailable': bool(item.get('detailAvailable'))},
                               {'title': headline, 'summary': summary, 'points': points, 'limitation': limitation}, temporary)
            image = temporary.read_bytes()
            if len(image) > 2 * 1024 * 1024 or not image.startswith(b'\x89PNG\r\n\x1a\n'):
                raise ValueError("RSS_IMAGE_INVALID")
            name = sha256(image).hexdigest() + '.png'
            temporary.replace(assets / name)
            image_names.append(name)
            article_images.append(name)
            body.append('<p><img src="' + base + '/assets/' + name + '" alt="' + escape(headline, quote=True) + '"></p>'
                        '<small>\u65b0\u95fb\u8981\u70b9\u56fe\u89e3\uff0c\u975e\u73b0\u573a\u7167\u7247\u3002</small>')
        body.append('<p>' + escape(summary) + '</p>')
        if points:
            body.append('<ul>' + ''.join('<li>' + escape(p) + '</li>' for p in points) + '</ul>')
        if limitation:
            body.append('<p><small>' + escape(limitation) + '</small></p>')
        body.append('<p>' + ' | '.join('<a href="' + escape(link, quote=True) + '">'
                    + '\u6765\u6e90\uff1a' + escape(urlsplit(link).hostname) + '</a>' for link in links) + '</p>')
        # 正文门槛独立于榜单名次，只有标题或尚未补齐的简述不进入单篇订阅。
        ready = item.get('rssDetailStatus') == 'ready' or (item.get('rssDetailStatus') != 'insufficient'
                and item.get('detailAvailable') and len(item.get('evidence', '').strip()) >= 80
                and len(summary) >= 80 and bool(points))
        if ready:
            identity = article_identity(item['url'])
            articles.append({'key': identity, 'day': day, 'slug': slug, 'category': category, 'kind': 'article',
                             'title': headline, 'body': '\n'.join(body[start:]), 'description': summary,
                             'images': article_images, 'imageKind': image_kind,
                             'imageSourceUrl': original['sourceUrl'] if original else '',
                             'headlines': [], 'sourceUrl': public_url(item['url'])})
    result = {'key': day + ':' + slug.replace('/', ':'), 'day': day, 'slug': slug, 'category': category,
            'title': title, 'body': '\n'.join(body), 'headlines': headlines, 'images': image_names, 'articles': articles,
            'pendingArticles': len(items) - len(articles),
            'description': str(len(items)) + ' \u6761\u7cbe\u9009\uff0c' + str(min(10, len(items))) + ' \u6761\u56fe\u6587\u8be6\u60c5\u3002'}
    if weekly:
        result.update(resultLimit=20, weeklyRun=report['publicationId'],
                      description=str(len(items)) + ' \u6761\u65b0\u95fb\u8be6\u60c5\u3002')
    return result


def content_hash(record):
    """仅依据公开内容计算身份之外的修订摘要。"""
    return sha256(json.dumps(record, sort_keys=True, ensure_ascii=True).encode()).hexdigest()
