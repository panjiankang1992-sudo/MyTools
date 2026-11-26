"""为 RSS 单篇文章补齐分析，独立缓存且不修改冻结日报。"""
import argparse
import copy
from hashlib import sha256
import json
import fcntl
from pathlib import Path
import time
from personal_report import analyze_material, write_json, CATEGORIES, report_day
from news_analysis import MAX_CATEGORY_ITEMS

SKILL = Path(__file__).resolve().parent.parent / 'skills/analyze-rss-articles/SKILL.md'


def evidence_available(item):
    """只有实质材料才进入文章发布，标题占位不能算作详情。"""
    text = item.get('evidence', '').strip()
    return bool(text and text != item.get('title', '').strip() and len(text) >= 80)


def prompt(material):
    """技能和外部材料分离，所有文字均为待分析数据。"""
    return SKILL.read_text() + '\nUntrusted evidence:\n' + json.dumps(material, ensure_ascii=False)


def validate(value, material):
    """严格校验身份覆盖，拒绝标题占位和缺少事实的成功结果。"""
    rows = value.get('items') if isinstance(value, dict) else None
    expected = {r['id'] for r in material['items']}
    if not isinstance(rows, list) or any(not isinstance(r, dict) for r in rows) or len(rows) != len(expected):
        raise ValueError('DSH_EVENT_SET_INVALID')
    seen, checked = set(), []
    for row in rows:
        identity = row.get('id')
        if identity not in expected or identity in seen or row.get('status') not in ('ready', 'insufficient'):
            raise ValueError('DSH_EVENT_SET_INVALID')
        seen.add(identity)
        for field, maximum in (('title', 1000), ('summary', 2400), ('limitation', 1000)):
            if not isinstance(row.get(field), str) or len(row[field]) > maximum:
                raise ValueError('DSH_CONTENT_INVALID')
        points = row.get('points')
        if not isinstance(points, list) or len(points) > 3 or any(not isinstance(p, str) or not p.strip() or len(p) > 600 for p in points):
            raise ValueError('DSH_POINTS_INVALID')
        if row['status'] == 'ready' and (not row['title'].strip() or len(row['summary'].strip()) < 40 or not points):
            raise ValueError('DSH_CONTENT_INVALID')
        if row['status'] == 'insufficient' and (row['summary'] or points or not row['limitation'].strip()):
            raise ValueError('DSH_CONTENT_INVALID')
        checked.append({k: row[k] for k in ('id', 'status', 'title', 'summary', 'points', 'limitation')})
    return {'items': checked}


def detail_feedback(value, material):
    """详情模式按固定身份修复，不使用榜单排名的诊断结构。"""
    return {'requiredIds': [r['id'] for r in material['items']], 'statuses': ['ready', 'insufficient'],
            'readyRequires': 'nonempty title, substantive summary, one to three source-backed points'}


validate.feedback = detail_feedback


def prepare(report_path, output, dsh='dsh', timeout=900, region_input=None):
    """复用已有详情，只对简述补做分析；输出仍是私有中间材料。"""
    original = json.loads(Path(report_path).read_text())
    report = copy.deepcopy(original)
    if region_input and not report.get('region'):
        report['region'] = json.loads(Path(region_input).read_text()).get('region')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    cache = output / 'cache'
    cache.mkdir(exist_ok=True, mode=0o700)
    stats = {'reused': 0, 'generated': 0, 'cached': 0, 'pending': 0, 'batches': 0}
    skill_hash = sha256(SKILL.read_bytes()).hexdigest()
    deadline = time.monotonic() + timeout
    for section in report['sections']:
        if section['category'] not in CATEGORIES:
            raise ValueError('RSS_CATEGORY_INVALID')
        # 历史报告重发也仅补齐前十，避免为已退出榜单的条目调用模型。
        section['items'] = section['items'][:MAX_CATEGORY_ITEMS]
        pending = []
        for item in section['items']:
            analysis = item['analysis']
            if not evidence_available(item):
                item['rssDetailStatus'] = 'insufficient'
                stats['pending'] += 1
                continue
            if analysis.get('points') and len(analysis.get('summary', '')) >= 80 and item.get('detailAvailable'):
                item['rssDetailStatus'] = 'ready'
                stats['reused'] += 1
                continue
            material = {'title': item['title'], 'evidence': item['evidence'][:8000],
                        'publishedAt': item.get('publishedAt'), 'summary': analysis.get('summary', '')}
            identity = sha256(json.dumps([skill_hash, material], sort_keys=True).encode()).hexdigest()
            destination = cache / (identity + '.json')
            material['id'] = identity
            if destination.exists():
                row = validate({'items': [json.loads(destination.read_text())]}, {'items': [material]})['items'][0]
                apply_detail(item, row)
                stats['cached'] += 1
                stats['pending'] += row['status'] != 'ready'
            else:
                pending.append((item, material, destination))
        for offset in range(0, len(pending), 10):
            batch = pending[offset:offset + 10]
            material = {'items': [r[1] for r in batch]}
            batch_id = sha256(json.dumps(material, sort_keys=True).encode()).hexdigest()[:20]
            workspace = output / ('batch-' + batch_id)
            workspace.mkdir(exist_ok=True, mode=0o700)
            remaining = min(300, deadline - time.monotonic())
            if remaining <= 0:
                raise ValueError('DSH_TIMEOUT')
            result = analyze_material(material, prompt, validate, 'rss-details', dsh,
                                      Path(__file__).with_name('dsh-news.patch.yml'), remaining, workspace,
                                      report_day(report) + ' ' + CATEGORIES[section['category']] +
                                      ' \u8be6\u60c5\u8865\u5168 ' + str(offset // 10 + 1))
            by_id = {r['id']: r for r in result['items']}
            for item, source, destination in batch:
                row = by_id[source['id']]
                write_json(destination, row)
                apply_detail(item, row)
                stats['generated'] += row['status'] == 'ready'
                stats['pending'] += row['status'] != 'ready'
            stats['batches'] += 1
            print(json.dumps({'stage': 'rss_details', 'category': section['category'], **stats}), flush=True)
    write_json(output / 'report.json', report)
    write_json(output / 'summary.json', stats)
    return output / 'report.json', stats


def apply_detail(item, row):
    """只覆盖 RSS 副本的分析字段，原排名和来源不变。"""
    item['rssDetailStatus'] = row['status']
    if row['status'] == 'ready':
        item['analysis'] = dict(item['analysis'], **{k: row[k] for k in ('title', 'summary', 'points', 'limitation')})


def main():
    """独立补齐历史详情，便于中断后恢复。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--region-input', type=Path)
    parser.add_argument('--dsh', default='dsh')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (args.output / '.details.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        report, stats = prepare(args.report, args.output, args.dsh, region_input=args.region_input)
    print(json.dumps({'report': str(report), **stats}))


if __name__ == '__main__':
    main()
