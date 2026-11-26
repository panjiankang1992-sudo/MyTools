"""按冻结时间窗复用已采集的真实正文与原图，不把抓取时间当新闻时间。"""
import copy
from datetime import timedelta
import hashlib
import json
from pathlib import Path
import re
from zoneinfo import ZoneInfo

from weekly_report import instant, prepare, save


def archives(config, directory, end):
    """只读取固定个人归档目录和最近手动任务，候选文件数量有界。"""
    day = end.astimezone(ZoneInfo(config['zone'])).date()
    personal = Path(config['stateDirectory']) / str(config['ownerId'])
    candidates = [personal / (day - timedelta(days=index)).isoformat() / 'input/report-input.json' for index in range(8)]
    # 手动任务缓存仅用于正文复用；不读取其他账号报告、DSH 草稿或邮件目录。
    recent = []
    for path in directory.parent.iterdir():
        if path == directory or path.is_symlink() or not re.fullmatch(r'[a-f0-9-]{36}', path.name):
            continue
        source = path / 'input/report-input.json'
        if source.is_file() and not source.is_symlink() and source.stat().st_mtime >= end.timestamp() - 8 * 86400:
            recent.append(source)
    return candidates + sorted(recent, key=lambda path: path.stat().st_mtime, reverse=True)[:24]


def copy_image(item, source, target):
    """缓存原图复制成内容哈希文件，旧归档后续清理不会破坏本次报告。"""
    picture = item.pop('image', None)
    if not picture:
        return
    relative = picture.get('path', '')
    if not re.fullmatch(r'images/[a-z0-9_-]+\.(jpg|jpeg|png|webp)', relative):
        return
    path = source.parent / relative
    if path.is_symlink() or (source.parent / 'images').is_symlink() or not path.is_file() \
            or path.resolve().parent != (source.parent / 'images').resolve() or path.stat().st_size > 2 * 1024 * 1024:
        return
    payload = path.read_bytes()
    name = 'archive-' + hashlib.sha256(payload).hexdigest() + path.suffix
    images = target.parent / 'images'
    images.mkdir(exist_ok=True)
    (images / name).write_bytes(payload)
    item['image'] = dict(picture, path='images/' + name)


def backfill(config, directory):
    """合并七天内明确日期和正文的候选，每类上限一百五十，保留真实覆盖统计。"""
    target = directory / 'input/report-input.json'
    current = json.loads(target.read_text())
    if current.get('backfill', {}).get('version') == 1:
        return
    end = instant(current['cutoff'])
    sections = {row['category']: row for row in prepare(current)}
    seen = {category: {item['url'] for item in section['items']} for category, section in sections.items()}
    counts = {category: 0 for category in sections}
    read = 0
    for source in archives(config, directory, end):
        if not source.is_file() or source.is_symlink() or source.stat().st_size > 10_000_000:
            continue
        try:
            archived = json.loads(source.read_text())
            # 旧日报有不同的截止时间，只重用单条证据，再按本次统一窗口重新过滤。
            candidate = dict(archived, mode='WEEKLY', cutoff=current['cutoff'], windowStart=current['windowStart'])
            prepared = prepare(candidate)
        except (ValueError, OSError, KeyError, TypeError):
            continue
        read += 1
        for section in prepared:
            category = section['category']
            if category == 'LOCAL' and archived.get('region', {}).get('regionCode') != current.get('region', {}).get('regionCode'):
                continue
            rows = sections[category]['items']
            for original in section['items']:
                if len(rows) >= 150 or original['url'] in seen[category]:
                    continue
                item = copy.deepcopy(original)
                copy_image(item, source, target)
                seen[category].add(item['url'])
                rows.append(item)
                counts[category] += 1
    for category, section in sections.items():
        for rank, item in enumerate(section['items'], 1):
            item['rank'] = rank
        times = [instant(item.get('publishedAt') or (item.get('technical') or {}).get('activityAt')) for item in section['items']]
        section['coverage'] = {'eligibleCandidates': len(times), 'archiveItems': counts[category],
            'oldestPublishedAt': min(times).isoformat() if times else None, 'newestPublishedAt': max(times).isoformat() if times else None}
    current['sections'] = list(sections.values())
    current['backfill'] = {'version': 1, 'archivesRead': read, 'archiveItems': sum(counts.values()), 'scope': 'AVAILABLE_SOURCES_AND_ARCHIVES'}
    save(target, current)
