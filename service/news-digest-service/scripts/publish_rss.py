#!/usr/bin/env python3
"""把冻结日报发布为静态 RSS，使用独立锁、不可变快照和可恢复发布状态。"""
import argparse
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import time
from urllib.parse import urlsplit
import uuid
import xml.etree.ElementTree as ET
from html import escape
from zoneinfo import ZoneInfo
from public_news import export_section, content_hash, page
from personal_report import CATEGORIES
from news_analysis import MAX_CATEGORY_ITEMS

CONTENT = 'http://purl.org/rss/1.0/modules/content/'
ATOM = 'http://www.w3.org/2005/Atom'
ET.register_namespace('content', CONTENT)
ET.register_namespace('atom', ATOM)


def configuration(value):
    """校验显式配置，默认关闭公开发布。"""
    if not isinstance(value, dict) or type(value.get('enabled')) is not bool:
        raise ValueError('RSS_CONFIGURATION_INVALID')
    if not value['enabled']:
        return value
    base = value.get('baseUrl', '').rstrip('/')
    parsed = urlsplit(base)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment\
            or not re.fullmatch(r'https://[A-Za-z0-9.-]+(?::[0-9]+)?(?:/[A-Za-z0-9_-]+)*', base):
        raise ValueError('RSS_BASE_URL_INVALID')
    root = Path(value.get('directory', ''))
    if not root.is_absolute() or root == Path('/') or root.is_symlink():
        raise ValueError('RSS_DIRECTORY_INVALID')
    result = dict(value, baseUrl=base)
    result['feedFormat'] = value.get('feedFormat', 'articles')
    if result['feedFormat'] not in ('articles', 'editions'):
        raise ValueError('RSS_CONFIGURATION_INVALID')
    for key, default, low, high in [('feedDays', 7, 1, 30), ('archiveDays', 90, 30, 365), ('maxFeedBytes', 4194304, 16384, 8388608)]:
        number = value.get(key, default)
        if type(number) is not int or not low <= number <= high:
            raise ValueError('RSS_CONFIGURATION_INVALID')
        result[key] = number
    return result


def durable_write(path, payload, mode=0o644):
    """原子替换文件，避免修改与旧快照共享的硬链接。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=path.parent, prefix='.write-')
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(name, mode)
        # 保证秒级缓存验证器在内容改变时也变化。
        modified = max(int(time.time()), int(path.stat().st_mtime) + 1 if path.exists() else 0)
        os.utime(name, (modified, modified))
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def sync_directory(path):
    """发布指针切换前后同步目录项。"""
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def display_title(record):
    """RSS 标题使用报告日期，去掉已有展示前缀，不改变文章身份与正文。"""
    label = CATEGORIES[record['category']]
    day = datetime.strptime(record['day'], '%Y-%m-%d').strftime('%Y/%m/%d')
    title = re.sub(r'^(?:\u3010(?:' + '|'.join(CATEGORIES.values()) + r')\u3011(?:\u3010[0-9]{4}/[0-9]{2}/[0-9]{2}\u3011)?)+', '', record['title']).lstrip()
    return '\u3010' + label + '\u3011\u3010' + day + '\u3011' + title


def rss_bytes(records, slug, base, maximum, articles=False, compatibility=False):
    """生成 RSS；兼容源仅限制历史条数，每条详情保持完整。"""
    entries = sorted(records, key=lambda r: (r['day'], r['key']), reverse=True)
    if compatibility:
        # 独立入口控制首次下载量，一周完整源仍保留全部文章。
        entries = entries[:100]
    def render(compact=False):
        root = ET.Element('rss', {'version': '2.0'})
        channel = ET.SubElement(root, 'channel')
        for key, value in [('title', 'MyTools News - ' + slug), ('link', base + '/subscribe/'),
                           ('description', '\u6bcf\u65e5\u65b0\u95fb\u7cbe\u9009\u4e0e\u56fe\u6587\u62a5\u544a'), ('language', 'zh-cn'), ('ttl', '60')]:
            ET.SubElement(channel, key).text = value
        if not compatibility:
            ET.SubElement(channel, '{' + ATOM + '}link', {'href': base + '/rss/' + slug + '.xml', 'rel': 'self', 'type': 'application/rss+xml'})
        if entries:
            ET.SubElement(channel, 'lastBuildDate').text = format_datetime(datetime.fromisoformat(max(r['updatedAt'] for r in entries)))
        for record in entries:
            item = ET.SubElement(channel, 'item')
            for key, value in [('title', display_title(record) if articles else record['title']), ('link', base + '/' + record['path']),
                               ('description', record['body'] if compatibility else record['description']), ('category', record['category']),
                               ('pubDate', format_datetime(datetime.fromisoformat(record['publishedAt'])))]:
                ET.SubElement(item, key).text = value
            ET.SubElement(item, 'guid', {'isPermaLink': 'false'}).text = ('urn:mytools:news:article:' if articles else 'urn:mytools:news:edition:') + record['key']
            text = record['body'] if not compact else '<p>' + escape(record['description']) + '</p><p>\u7cbe\u7b80\u7248\uff1a<a href="' + base + '/' + record['path'] + '">\u9605\u8bfb\u5b8c\u6574\u56fe\u6587\u65e5\u62a5</a></p>'
            if not compatibility:
                ET.SubElement(item, '{' + CONTENT + '}encoded').text = text
        return ET.tostring(root, encoding='utf-8', xml_declaration=True)
    while True:
        payload = render()
        if len(payload) <= maximum:
            return payload
        if compatibility and len(entries) > 1:
            # 超预算只移除最旧条目，禁止截断 XML 或裁剪图文详情。
            entries.pop()
            continue
        if articles:
            # 不为大小上限默默丢掉最近一周文章或把详情变成标题链接。
            raise ValueError('RSS_FEED_TOO_LARGE')
        if len(entries) > 1:
            entries.pop()
        else:
            return render(compact=True)


def switch_current(root, release):
    """最后一步切换指针；清单与站点已完整落盘。"""
    temporary = root / ('.current-' + uuid.uuid4().hex)
    try:
        temporary.symlink_to(release.relative_to(root) / 'site', target_is_directory=True)
        os.replace(temporary, root / 'current')
        sync_directory(root)
    finally:
        temporary.unlink(missing_ok=True)


def publish(report_path, config, now=None, region_input=None):
    """发布已完成分类；任何网络、模型和邮件动作均不在本函数执行。"""
    config = configuration(config)
    if not config['enabled']:
        return {'status': 'disabled'}
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise ValueError('RSS_TIME_INVALID')
    report_path = Path(report_path).resolve()
    if report_path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('RSS_REPORT_TOO_LARGE')
    report = json.loads(report_path.read_text())
    weekly_run = report.get('publicationId') if report.get('mode') == 'WEEKLY' else None
    if report.get('mode') == 'WEEKLY':
        if not isinstance(weekly_run, str) or not re.fullmatch(r'[a-f0-9-]{36}:[1-9][0-9]*', weekly_run):
            raise ValueError('RSS_WEEKLY_ID_INVALID')
        if config['feedFormat'] != 'articles':
            raise ValueError('RSS_WEEKLY_FORMAT_INVALID')
        config = dict(config, weeklyRun=weekly_run)
    # 每日任务按配置时区运行，早晨发布时不能用前一天的 UTC 日期计算窗口。
    business_day = now.astimezone(ZoneInfo(report['zone'])).date()
    if region_input and not report.get('region'):
        # 兼容早期冻结报告，仅补读地区，不重写原文件。
        report['region'] = json.loads(Path(region_input).read_text()).get('region')
    # 原始采集包同时提供地区与已校验的配图，RSS 分析副本不移动原图目录。
    source_root = Path(region_input).resolve().parent if region_input else None
    categories = [s['category'] for s in report['sections']]
    if len(categories) != len(set(categories)) or any(c not in CATEGORIES for c in categories):
        raise ValueError('RSS_CATEGORIES_INVALID')
    root = Path(config['directory'])
    if root.resolve() == report_path.parent or root.resolve() in report_path.parents:
        raise ValueError('RSS_DIRECTORY_INVALID')
    root.mkdir(parents=True, exist_ok=True)
    root.chmod(0o755)
    releases = root / 'releases'
    releases.mkdir(exist_ok=True)
    releases.chmod(0o755)
    with (root / '.publish.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        previous = None
        state = {'baseUrl': config['baseUrl'], 'records': {}}
        current = root / 'current'
        if current.exists() or current.is_symlink():
            previous = current.resolve(strict=True)
            if not current.is_symlink() or previous.parent.parent != releases.resolve() or previous.name != 'site':
                raise ValueError('RSS_CURRENT_INVALID')
            state = json.loads((previous.parent / 'manifest.json').read_text())
            if state['baseUrl'] != config['baseUrl']:
                raise ValueError('RSS_BASE_URL_CHANGED')
        oldest = (business_day - timedelta(days=config['archiveDays'] - 1)).isoformat()
        records = {k: v for k, v in state['records'].items() if v['day'] >= oldest}
        failures = []
        with tempfile.TemporaryDirectory(prefix='.staging-', dir=root) as temporary:
            staging = Path(temporary)
            site = staging / 'site'
            if previous:
                shutil.copytree(previous, site, copy_function=os.link)
            else:
                site.mkdir()
            assets = site / 'assets'
            assets.mkdir(exist_ok=True)
            changed = records != state['records']
            successful = []
            for section in report['sections']:
                try:
                    exported = export_section(report, section, assets, config['baseUrl'], source_root)
                    if exported['day'] < oldest or exported['day'] > (business_day + timedelta(days=1)).isoformat():
                        raise ValueError('RSS_EDITION_OUT_OF_RANGE')
                    successful.append(exported['key'])
                    changed |= install_record(exported, records, site, config['baseUrl'], now)
                except (ValueError, KeyError, TypeError) as error:
                    failures.append({'category': section.get('category'), 'code': str(error) if isinstance(error, ValueError) and str(error).startswith('RSS_') else 'RSS_SECTION_INVALID'})
            if not successful:
                raise ValueError('RSS_NO_PUBLISHABLE_SECTIONS')
            # 已验收的周报必须完整转换；转换失败不覆盖上一版，也不能标记消费完成。
            if weekly_run and failures:
                raise ValueError('RSS_WEEKLY_EXPORT_FAILED')
            day = records[successful[0]]['day']
            today = sorted((r for r in records.values() if r['day'] == day and r['slug'] != 'daily'
                            and (not weekly_run or r.get('weeklyRun') == weekly_run)), key=lambda r: r['slug'])
            overview = '<p>\u6bcf\u7c7b\u6700\u591a 10 \u6761\u65b0\u95fb\uff0c\u6bcf\u6761\u56fe\u6587\u8be6\u60c5\u3002</p>'
            if weekly_run:
                overview = '<p>\u6700\u8fd1\u4e03\u5929\uff0c\u6bcf\u7c7b\u6700\u591a 20 \u6761\u65b0\u95fb\u8be6\u60c5\u3002</p>'
            for record in today:
                overview += '<h2><a href="' + config['baseUrl'] + '/' + record['path'] + '">' + escape(record['title']) + '</a></h2><ul>' + ''.join('<li>' + escape(h) + '</li>' for h in record['headlines'][:3]) + '</ul>'
            expected = set(CATEGORIES) if weekly_run else set(categories) | {'TECH', 'WORLD', 'DOMESTIC', 'LOCAL'}
            missing = sorted(expected - {r['category'] for r in today})
            if missing:
                overview += '<p>\u672c\u671f\u672a\u5b8c\u6210\u5206\u7c7b\uff1a' + ', '.join(missing) + '</p>'
            changed |= install_record({'key': day + ':daily', 'day': day, 'slug': 'daily', 'category': 'DAILY',
                                       'title': day + ' \u6bcf\u65e5\u65b0\u95fb\u603b\u89c8', 'description': '\u6280\u672f\u3001\u56fd\u9645\u3001\u56fd\u5185\u548c\u672c\u5730\u65b0\u95fb\u5bfc\u8bfb',
                                       'body': overview, 'headlines': [], 'images': []}, records, site, config['baseUrl'], now)
            feed_start = (business_day - timedelta(days=config['feedDays'] - 1)).isoformat()
            feed_policy = {k: config[k] for k in ('feedDays', 'archiveDays', 'maxFeedBytes', 'feedFormat')}
            # 渲染规则升级时即使冻结报告未变，也必须重建订阅文件。
            feed_policy['rendererVersion'] = 4
            feed_policy['dailyCategoryLimit'] = MAX_CATEGORY_ITEMS
            if weekly_run:
                feed_policy.update(weeklyRun=weekly_run, weeklyCategoryLimit=20)
            changed |= state.get('feedStart') != feed_start or state.get('policy') != feed_policy
            if not changed:
                return {'status': 'unchanged', 'release': previous.parent.name, 'failedSections': failures, 'feedUrl': config['baseUrl'] + '/rss/daily.xml'}
            articles = reconcile_articles(records, state.get('articles', {}), site, config['baseUrl'], now, weekly_run)
            render_site(site, records, config, feed_start, oldest, now, articles)
            release_id = now.strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:12]
            manifest = {'baseUrl': config['baseUrl'], 'records': records, 'articles': articles,
                        'feedStart': feed_start, 'policy': feed_policy, 'publishedAt': now.isoformat()}
            durable_write(staging / 'manifest.json', json.dumps(manifest, ensure_ascii=True).encode(), 0o600)
            # 父进程可能使用严格 umask，公网目录需要显式可遍历权限。
            for directory in [site, *[p for p in site.rglob('*') if p.is_dir()]]:
                directory.chmod(0o755)
                sync_directory(directory)
            for file in site.rglob('*'):
                if file.is_file():
                    file.chmod(0o644)
                    with file.open('rb') as stream:
                        os.fsync(stream.fileno())
            staging.chmod(0o755)
            sync_directory(staging)
            release = releases / release_id
            staging.rename(release)
            sync_directory(releases)
            switch_current(root, release)
            # 最近三个完整快照可直接回退，其余快照不再占用历史硬链接。
            for old in sorted(releases.iterdir(), key=lambda p: p.name)[:-3]:
                if old.is_dir() and not old.is_symlink():
                    shutil.rmtree(old)
            return {'status': 'published_partial' if failures or missing else 'published', 'release': release_id,
                    'failedSections': failures, 'missingCategories': missing, 'articleCount': len(articles),
                    'pendingArticles': sum(r.get('pendingArticles', 0) for r in records.values()),
                    'feedUrl': config['baseUrl'] + '/rss/daily.xml'}


def reconcile_articles(records, previous, site, base, now, weekly_run=None):
    """同一原文多日入榜只发布一篇，详情变动保留 GUID 与首次公开时间。"""
    selected = {}
    for edition in sorted(records.values(), key=lambda r: (bool(weekly_run and r.get('weeklyRun') == weekly_run), r['day'], r['key'])):
        for article in edition.get('articles', []):
            selected[article['key']] = article
    # 缩小榜单不撤销已经发布的文章链接，归档到期后才清理。
    oldest = min(record['day'] for record in records.values())
    result = {key: article for key, article in previous.items() if article['day'] >= oldest}
    for key, exported in selected.items():
        fingerprint = content_hash({k: v for k, v in exported.items() if k != 'day'})
        old = previous.get(key)
        if old and old['contentHash'] == fingerprint:
            result[key] = dict(old, day=exported['day'])
            continue
        revision = old['revision'] + 1 if old else 1
        path = 'articles/' + key + '/r' + str(revision) + '.html'
        record = dict(exported, contentHash=fingerprint, revision=revision, path=path,
                      publishedAt=old['publishedAt'] if old else now.isoformat(), updatedAt=now.isoformat())
        note = '<p><small>' + escape(record['day']) + ' | ' + escape(record['category']) + '</small></p>'
        html = page(record['title'], note + record['body'], base).encode()
        durable_write(site / path, html)
        durable_write(site / 'articles' / key / 'index.html', html)
        result[key] = record
    for directory in (site / 'articles').iterdir() if (site / 'articles').exists() else []:
        if directory.name not in result:
            shutil.rmtree(directory)
    return result


def install_record(exported, records, site, base, now):
    """保留稳定 GUID 和首次发布时间，以新网页修订替换有变化的内容。"""
    fingerprint = content_hash(exported)
    old = records.get(exported['key'])
    if old and old['contentHash'] == fingerprint:
        return False
    revision = old['revision'] + 1 if old else 1
    path = 'editions/' + exported['day'] + '/r' + str(revision) + '/' + exported['slug'] + '.html'
    record = dict(exported, contentHash=fingerprint, revision=revision, path=path,
                  publishedAt=old['publishedAt'] if old else now.isoformat(), updatedAt=now.isoformat())
    note = '<p><small>\u53d1\u5e03\uff1a' + escape(record['publishedAt']) + ' | \u4fee\u8ba2 ' + str(revision) + '</small></p>'
    html = page(record['title'], note + record['body'], base).encode()
    durable_write(site / path, html)
    durable_write(site / 'editions' / exported['day'] / (exported['slug'] + '.html'), html)
    records[exported['key']] = record
    return True


def ranked_feed_articles(records, articles, weekly_run=None):
    """按各期编辑顺序保留每日每类前十，历史文章网页与身份保持可用。"""
    selected, counts = {}, {}
    for edition in sorted(records.values(), key=lambda r: (r['day'], r['key']), reverse=True):
        # 手动周报只展示最新一次已验收快照，空分类不会从旧日报补入过期条目。
        if weekly_run and edition.get('weeklyRun') != weekly_run:
            continue
        limit = 20 if weekly_run else MAX_CATEGORY_ITEMS
        group = (edition['day'], edition['category'])
        for entry in edition.get('articles', [])[:limit]:
            article = articles.get(entry['key'])
            # 跨日重复文章只使用当前归属，不能从旧榜单重新带入。
            if not article or (article['day'], article['category']) != group or article['key'] in selected:
                continue
            if counts.get(group, 0) >= limit:
                break
            selected[article['key']] = article
            counts[group] = counts.get(group, 0) + 1
    return selected


def render_site(site, records, config, feed_start, oldest, now, articles):
    """生成 Feed、订阅入口和 OPML，并清理不再被归档引用的素材。"""
    base = config['baseUrl']
    slugs = sorted({'daily', 'tech', 'world', 'domestic'} | {r['slug'] for r in records.values()})
    article_mode = config['feedFormat'] == 'articles'
    # 旧归档可以仍有二十条，订阅统一应用每日前十规则。
    articles = ranked_feed_articles(records, articles, config.get('weeklyRun'))
    index = '<p>\u6bcf\u6761\u65b0\u95fb\u72ec\u7acb\u9605\u8bfb\uff0c\u542b\u6b63\u6587\u6458\u8981\u3001\u8981\u70b9\u4e0e\u6765\u6e90\u3002\u590d\u5236 RSS \u5730\u5740\u5230\u9605\u8bfb\u5668\u8ba2\u9605\u3002</p><ul>'
    opml = ET.Element('opml', {'version': '2.0'})
    ET.SubElement(ET.SubElement(opml, 'head'), 'title').text = 'MyTools News Categories'
    outlines = ET.SubElement(opml, 'body')
    for slug in slugs:
        selected = [r for r in (articles if article_mode else records).values()
                    if (r['slug'] == slug or article_mode and slug == 'daily') and r['day'] >= feed_start]
        payload = rss_bytes(selected, slug, base, config['maxFeedBytes'], articles=article_mode)
        ET.fromstring(payload)
        destination = site / 'rss' / (slug + '.xml')
        if not destination.exists() or destination.read_bytes() != payload:
            durable_write(destination, payload)
        url = base + '/rss/' + slug + '.xml'
        index += '<li><a href="' + url + '">' + slug + '</a> <code>' + url + '</code></li>'
        if slug != 'daily':
            ET.SubElement(outlines, 'outline', {'text': slug, 'title': slug, 'type': 'rss', 'xmlUrl': url, 'htmlUrl': base + '/subscribe/'})
    compatibility_path = site / 'rss/outlook.xml'
    if article_mode:
        selected = [r for r in articles.values() if r['day'] >= feed_start]
        payload = rss_bytes(selected, 'outlook', base, min(config['maxFeedBytes'], 512 * 1024),
                            articles=True, compatibility=True)
        if not compatibility_path.exists() or compatibility_path.read_bytes() != payload:
            durable_write(compatibility_path, payload)
        url = base + '/rss/outlook.xml'
        index += '<li><a href="' + url + '">Outlook RSS</a> <code>' + url + '</code> ' + '\u6700\u65b0\u6700\u591a 100 \u6761\uff0c\u6bcf\u6761\u4fdd\u7559\u5b8c\u6574\u56fe\u6587\uff1b\u4e00\u5468\u5168\u91cf\u8bf7\u8ba2\u9605 daily\u3002</li>'
    else:
        compatibility_path.unlink(missing_ok=True)
    index += '</ul><p><a href="' + base + '/rss/categories.opml">OPML</a></p><h2>\u65b0\u95fb\u6587\u7ae0</h2><ul>'
    for record in sorted((articles if article_mode else records).values(), key=lambda r: (r['day'], r['key']), reverse=True):
        index += '<li><a href="' + base + '/' + record['path'] + '">' + escape(display_title(record) if article_mode else record['title']) + '</a></li>'
    index += '</ul>'
    index += '<h2>\u65e5\u62a5\u5f52\u6863</h2><ul>' + ''.join('<li><a href="' + base + '/' + r['path'] + '">' + escape(r['title']) + '</a></li>'
                for r in sorted(records.values(), key=lambda r: r['day'], reverse=True) if r['slug'] == 'daily') + '</ul>'
    durable_write(site / 'subscribe/index.html', page('MyTools \u65b0\u95fb\u8ba2\u9605', index, base).encode())
    durable_write(site / 'index.html', page('MyTools \u65b0\u95fb\u8ba2\u9605', index, base).encode())
    durable_write(site / 'rss/categories.opml', ET.tostring(opml, encoding='utf-8', xml_declaration=True))
    for directory in (site / 'editions').iterdir():
        if directory.is_dir() and directory.name < oldest:
            shutil.rmtree(directory)
    used = set()
    for file in site.rglob('*.html'):
        used.update(re.findall(r'/assets/([0-9a-f]{64}\.(?:png|jpg|gif))', file.read_text()))
    for asset in (site / 'assets').iterdir():
        # 旧读者可能仍缓存 HTML，孤立图片保留更长缓冲；暂存文件不公开。
        if asset.name == 'brief.png' or (asset.name not in used and asset.stat().st_mtime < (now - timedelta(days=config['archiveDays'] + 30)).timestamp()):
            asset.unlink()


def main():
    """单独重试公开发布，不修改冻结材料或邮件回执。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--region-input', type=Path)
    parser.add_argument('--details-directory', type=Path)
    parser.add_argument('--dsh', default='dsh')
    args = parser.parse_args()
    config = configuration(json.loads(args.config.read_text()))
    report = args.report
    if config['enabled'] and config['feedFormat'] == 'articles':
        from rss_article_details import prepare
        details = args.details_directory or args.report.parent / '.rss-details'
        details.mkdir(parents=True, exist_ok=True, mode=0o700)
        with (details / '.details.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            # 子步骤进度写入私有日志，stdout 仅返回最终发布结果给父进程。
            from contextlib import redirect_stdout
            with (details / 'enrichment.log').open('a') as log, redirect_stdout(log):
                report, _ = prepare(report, details, args.dsh, timeout=900, region_input=args.region_input)
    result = publish(report, config, region_input=args.region_input)
    print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'status': 'failed', 'code': str(error) if isinstance(error, ValueError) and str(error).startswith('RSS_') else type(error).__name__}))
        sys.exit(1)
