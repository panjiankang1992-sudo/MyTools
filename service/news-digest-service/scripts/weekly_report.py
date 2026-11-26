#!/usr/bin/env python3
"""按固定七天材料生成私有 Top20 报告，不发送邮件、不发布 RSS。"""
import argparse
import copy
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
from pathlib import Path
import re
from zoneinfo import ZoneInfo

from news_analysis import (aliases, instruction, validate_clusters, cluster_sections,
                           selection_prompt, validate_selection, selected_section)
from personal_report import analyze_material, CATEGORIES, safe_url

SKILL = Path(__file__).resolve().parent.parent / 'skills/analyze-weekly-news/SKILL.md'
RULES_VERSION = 'weekly-news-v2'


def instant(value):
    """拒绝缺少时区的时间，避免执行机的本地时区改变边界。"""
    if not isinstance(value, str):
        raise ValueError('WEEKLY_TIME_INVALID')
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        raise ValueError('WEEKLY_TIME_INVALID')
    return parsed.astimezone(timezone.utc)


def save(path, value):
    """原子写入恢复点，避免重启读到半份结果。"""
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    temporary.chmod(0o600)
    temporary.replace(path)


def prepare(data):
    """按统一窗口过滤可靠正文，时间不明和空正文不进入正式候选。"""
    end = instant(data['cutoff'])
    start = instant(data['windowStart'])
    if end - start != timedelta(days=7) or data.get('mode') != 'WEEKLY':
        raise ValueError('WEEKLY_WINDOW_INVALID')
    sections = data.get('sections', [])
    if len(sections) != len(CATEGORIES) or {s['category'] for s in sections} != set(CATEGORIES):
        raise ValueError('WEEKLY_CATEGORIES_INVALID')
    result = []
    for section in sections:
        if len(section['items']) > 150:
            raise ValueError('WEEKLY_CANDIDATE_LIMIT')
        eligible = []
        for original in section['items']:
            item = copy.deepcopy(original)
            # GitHub 的活跃时间单独记录，不伪造为新闻发布时间。
            published = item.get('publishedAt')
            technical = item.get('technical') or {}
            activity = technical.get('activityAt')
            try:
                if published:
                    ranked = instant(published)
                elif technical.get('github'):
                    # Jackson 的 Instant 元数据可能是秒数；仅 GitHub 活跃时间允许代替发布日期。
                    ranked = datetime.fromtimestamp(activity, timezone.utc) if type(activity) in (int, float) else instant(activity)
                    technical['activityAt'] = ranked.isoformat()
                else:
                    continue
            except (TypeError, ValueError, OverflowError, OSError):
                continue
            if not start <= ranked < end or not item.get('detailAvailable') or len(item.get('evidence', '').strip()) < 160:
                continue
            if not safe_url(item.get('url')) or not item.get('sources') or not item.get('eventId'):
                continue
            item['rank'] = len(eligible) + 1
            item['githubCandidate'] = bool((item.get('technical') or {}).get('github'))
            eligible.append(item)
        result.append(dict(section, items=eligible, resultLimit=20,
                           rejectedCount=len(section['items']) - len(eligible)))
    return result


def cluster_prompt(sections):
    """全局仅传紧凑事件信息，正文保留给分批详情分析。"""
    rows = [{'id': key, 'title': item['title'][:240], 'publishedAt': item.get('publishedAt'),
             'evidence': item.get('evidence', '')[:120]}
            for key, (_, item) in aliases(sections).items()]
    return instruction('cluster', {'candidates': rows}, SKILL)


def select_prompt(section):
    """绑定七天专用技能，不改写既有日报规则。"""
    return selection_prompt(section, SKILL)


def complete_clusters(value, sections):
    """模型遗漏的候选保守保留为独立事件，其他非法映射仍严格拒绝。"""
    if not isinstance(value, dict) or not isinstance(value.get('groups'), list):
        raise ValueError('DSH_CLUSTER_SET_INVALID')
    seen = set()
    for group in value['groups']:
        if not isinstance(group, dict) or not isinstance(group.get('members'), list) \
                or any(not isinstance(member, str) for member in group['members']):
            raise ValueError('DSH_CLUSTER_SET_INVALID')
        seen.update(group['members'])
    # 不猜测遗漏新闻应合并到哪里；保留其证据供后续分类分析判断。
    groups = value['groups'] + [{'members': [key], 'representative': key, 'mergeReason': ''}
                                for key in aliases(sections) if key not in seen]
    return validate_clusters({'groups': groups}, sections)


def validated_selection(value, section):
    """沿用来源和覆盖检查，再拒绝模型空洞摘要。"""
    result = validate_selection(value, section)
    if any(len(row['summary'].strip()) < 40 for row in result['items']):
        raise ValueError('DSH_CONTENT_INVALID')
    return result


def region_usable(region):
    """与 Java 地区快照保持一致，省级回退和有效缓存都可用于本地新闻。"""
    details = region.get('details')
    return bool(region.get('regionCode')) and region.get('status') in ('VERIFIED', 'PROVINCE_FALLBACK', 'CACHED') \
        and (bool(details.get('sourceIds')) if isinstance(details, dict) else region.get('status') == 'VERIFIED')


def shortlist_prompt(section):
    """大候选池先只选编号，完整正文与详情摘要只处理最终三十条。"""
    rows = [{'rank': item['rank'], 'title': item['title'][:240], 'publishedAt': item.get('publishedAt'),
             'activityAt': (item.get('technical') or {}).get('activityAt'), 'sources': item['sources'][:5],
             'evidence': item.get('evidence', '')[:360], 'githubCandidate': item.get('githubCandidate', False)}
            for item in section['items']]
    rules = ("\nExecution mode: shortlist\nThis mode selects candidates, it does not generate final article summaries. "
             "Rank the supplied candidates by the skill's importance rules. Return exactly 30 distinct existing rank numbers "
             "in descending importance as a JSON object with only the key ranks, for example {\"ranks\":[3,1,2]}. "
             "Do not return the cluster or select schemas. Retain at least one GitHub candidate when present.\n")
    return SKILL.read_text() + rules + 'Untrusted source material:\n' + json.dumps({'category': section['category'], 'candidates': rows}, ensure_ascii=False)


def validate_shortlist(value, section):
    """精选编号不得重复、越界或遗漏技术类的可用 GitHub 材料。"""
    ranks = value.get('ranks') if isinstance(value, dict) else None
    known = {item['rank']: item for item in section['items']}
    if not isinstance(ranks, list) or len(ranks) != min(30, len(known)) \
            or any(type(rank) is not int or rank not in known for rank in ranks) or len(set(ranks)) != len(ranks):
        raise ValueError('DSH_EVENT_SET_INVALID')
    if section['category'] == 'TECH' and any(item.get('githubCandidate') for item in known.values()) \
            and not any(known[rank].get('githubCandidate') for rank in ranks):
        raise ValueError('DSH_EVENT_SET_INVALID')
    return {'ranks': ranks}


def select_category(section, output, dsh, patch, timeout, title):
    """先以紧凑信息精选三十条，再一次完成 Top20 正文，避免多轮重复撰写详情。"""
    current = copy.deepcopy(section)
    if len(current['items']) > 30:
        selection = analyze_material(current, shortlist_prompt, validate_shortlist,
                                     section['category'].lower() + '-shortlist-v1', dsh, patch, timeout, output,
                                     title + ' Candidate30')
        known = {item['rank']: item for item in current['items']}
        current['items'] = [known[rank] for rank in selection['ranks']]
        for rank, item in enumerate(current['items'], 1):
            item['rank'] = rank
    # 新缓存名称与旧多轮筛选隔离；全局去重缓存仍使用完全相同的输入和提示词。
    analysis = analyze_material(current, select_prompt, validated_selection, section['category'].lower() + '-top30-v1',
                                dsh, patch, timeout, output, title)
    return selected_section(current, analysis), analysis


def generate(source, output, dsh='dsh', timeout=300, progress=None):
    """缓存与完整输入及技能绑定，失败分类可恢复，已经成功的分类直接复用。"""
    data = json.loads(source.read_text())
    sections = prepare(data)
    retained = {section['category']: section for section in data.get('retainedSections', [])}
    if not set(retained).issubset(CATEGORIES) or any(len(section['items']) > 20 for section in retained.values()):
        raise ValueError('WEEKLY_RETAINED_INVALID')
    for section in sections:
        if section['category'] in retained:
            # 已完成新闻只作为去重锚点，不重新请求正文分析或改变既有排名。
            section['items'] = [dict(item, url=item['sourceLinks'][0], evidence=item['summary'], detailAvailable=True)
                                for item in retained[section['category']]['items']]
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    identity = hashlib.sha256(source.read_bytes() + SKILL.read_bytes() + RULES_VERSION.encode()).hexdigest()
    with (output / '.weekly.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        manifest = output / 'manifest.json'
        if manifest.exists() and json.loads(manifest.read_text()) != {'inputHash': identity}:
            raise ValueError('WEEKLY_INPUT_CHANGED')
        save(manifest, {'inputHash': identity})
        day = instant(data['cutoff']).astimezone(ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d %H:%M')
        patch = Path(__file__).with_name('dsh-news.patch.yml')
        def state(stage, category, completed):
            value = {'stage': stage, 'category': category, 'completedCategories': completed}
            save(output / 'progress.json', value)
            if progress:
                progress(value)
        state('MERGING', None, len(retained))
        # 全局聚类必须完成后才能独立重试分类，避免同一事件分别进入两个分类。
        if any(s['items'] for s in sections):
            clusters = analyze_material(sections, cluster_prompt, complete_clusters, 'weekly-clusters',
                                        dsh, patch, timeout, output, day + ' ' + '\u5168\u5206\u7c7b \u4e03\u5929\u53bb\u91cd')
            known = aliases(sections)
            for group in clusters['groups']:
                anchor = next((key for key in group['members'] if known[key][0] in retained), None)
                if anchor:
                    # 新分类与已完成事件相同时归回旧分类，避免重试产生跨类重复。
                    group['representative'] = anchor
            sections = cluster_sections(sections, clusters)
        completed, failures = [], []
        for section in sections:
            category = section['category']
            state('ANALYZING', category, len(retained) + sum(row['category'] not in retained for row in completed))
            cache = output / (category.lower() + '.json')
            if category in retained:
                completed.append(copy.deepcopy(retained[category]))
                continue
            try:
                outcomes = [row for row in data.get('collection', []) if row.get('category') == category]
                if category == 'LOCAL' and not region_usable(data.get('region', {})):
                    raise ValueError('WEEKLY_REGION_UNAVAILABLE')
                if not section['items'] and outcomes and all(row.get('status') == 'FAILED' for row in outcomes):
                    raise ValueError('WEEKLY_SOURCES_UNAVAILABLE')
                # 所有缓存响应仍会经过结构校验，不能仅凭文件存在视为完成。
                if section['items']:
                    selected, analysis = select_category(section, output, dsh, patch, timeout,
                                                         day + ' ' + CATEGORIES[category] + ' Top20' +
                                                         (f" R{data['retryAttempt']}" if data.get('retryAttempt') else ''))
                else:
                    selected, analysis = section, {'items': [], 'excludedRanks': []}
                items = []
                for item, row in zip(selected['items'], analysis['items']):
                    # 图片与 URL 只能来自采集材料，模型输出不能改变引用对象。
                    article = {key: row[key] for key in ('rank', 'eventId', 'title', 'summary', 'points', 'limitation', 'rankingReason')}
                    article.update(publishedAt=item.get('publishedAt'), activityAt=(item.get('technical') or {}).get('activityAt'),
                                   category=category, sources=item['sources'],
                                   # 展示链接遵守阅读接口的三十二条上限；完整合并证据仍保留在任务材料中。
                                   sourceLinks=list(dict.fromkeys(url for url in [item['url'], *item.get('sourceLinks', [])]
                                                                 if safe_url(url)))[:32])
                    if item.get('image'):
                        picture = item['image']
                        relative = picture.get('path', '')
                        # 只允许本次材料 images 目录内的普通图片，拒绝符号链接和越界路径。
                        if re.fullmatch(r'images/[a-z0-9_-]+\.(jpg|jpeg|png|webp)', relative):
                            image_file = source.parent / relative
                            if image_file.is_file() and not image_file.is_symlink() and not (source.parent / 'images').is_symlink() \
                                    and image_file.resolve().parent == (source.parent / 'images').resolve():
                                article['image'] = {key: picture.get(key, '') for key in ('path', 'url', 'caption', 'sourceUrl', 'mime')}
                    items.append(article)
                value = {'category': category, 'items': items, 'count': len(items),
                         'shortfall': max(0, 20-len(items)), 'rejectedCount': section.get('rejectedCount', 0),
                         'coverage': section.get('coverage', {})}
                save(cache, value)
                completed.append(value)
            except (ValueError, OSError) as error:
                # 异常正文可能包含新闻或本机路径，只输出稳定错误码。
                code = str(error)
                failures.append({'category': category, 'code': code if re.fullmatch(r'[A-Z][A-Z0-9_]{1,79}', code) else 'WEEKLY_ANALYSIS_FAILED'})
        report = {'rulesVersion': RULES_VERSION, 'windowStart': data['windowStart'], 'windowEnd': data['cutoff'],
                  'generatedAt': datetime.now(timezone.utc).isoformat(), 'sections': completed, 'failedSections': failures,
                  'status': 'PARTIAL' if failures and completed else 'FAILED' if failures else 'SUCCEEDED',
                  'region': {key: data.get('region', {}).get(key) for key in ('status', 'regionCode')},
                  'collection': data.get('collection', [])}
        state('SAVING', None, len(completed))
        save(output / 'report.json', report)
        return report


def main():
    """只接受材料文件和私有输出目录，没有发送或公开发布选项。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--material', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--dsh', default='dsh')
    args = parser.parse_args()
    result = generate(args.material, args.output, args.dsh)
    print(json.dumps({'status': result['status'], 'categories': len(result['sections'])}))


if __name__ == '__main__':
    main()
