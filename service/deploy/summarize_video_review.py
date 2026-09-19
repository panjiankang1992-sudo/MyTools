#!/usr/bin/env python3
"""把人工评分表汇总成验收结论，并可选合并进 vlm-review/reviews.json。

评分表是 CSV（模板见 docs/verification/2026-09-14-video-generation/p0-reaccept-20260919/review-sheet.csv），
五个维度用与既有 reviews.json 相同的键名，方便人工评分与自动 VLM 复核并排存放：

    instructionFollowing / subjectPreservation / temporalCoherence / editLocality / artifacts

规则与 acceptance.md 一致：每条关键项 ≥3 记为通过；模式准入要求至少 5/6 条通过，且没有一票否决
（severeFlag 为 TRUE/1/yes）。图片条件模式的关键项是指令遵循、主体保持、时间连贯；编辑类模式
额外把编辑局部性算作关键项（`editLocality` 非空即视为适用）。

用法：
    python3 summarize_video_review.py --sheet review-sheet.csv
    python3 summarize_video_review.py --sheet review-sheet.csv --write --reviewer 名字
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

DIMENSIONS = ('instructionFollowing', 'subjectPreservation', 'temporalCoherence', 'editLocality', 'artifacts')
KEY_DIMENSIONS = ('instructionFollowing', 'subjectPreservation', 'temporalCoherence')
SEVERE_VALUES = {'true', '1', 'yes', 'y', '是'}
NOT_APPLICABLE = {'', 'n/a', 'na', '-', 'none'}
MIN_SCORE = 3
REQUIRED_PASS_RATIO = 5 / 6


def parse_score(value):
    """把单元格解析成 1–5 分；空值或 n/a 返回 None；非法值直接报错而不是静默算错。"""
    text = (value or '').strip()
    if text.lower() in NOT_APPLICABLE:
        return None
    if not text.isdigit() or not 1 <= int(text) <= 5:
        raise ValueError(f'score_out_of_range:{value!r}')
    return int(text)


def is_severe(value):
    """一票否决：接受 TRUE/1/yes/是 等写法。"""
    return (value or '').strip().lower() in SEVERE_VALUES


def summarize(rows):
    """逐条判定是否通过，再按 acceptance.md 的比例给出模式结论。"""
    samples = []
    for row in rows:
        scores = {name: parse_score(row.get(name)) for name in DIMENSIONS}
        applicable = [name for name in DIMENSIONS if scores[name] is not None]
        # 编辑局部性被填写时说明这是编辑类样例，它也算关键项。
        key = list(KEY_DIMENSIONS) + (['editLocality'] if scores['editLocality'] is not None else [])
        missing = [name for name in key if scores[name] is None]
        severe = is_severe(row.get('severeFlag'))
        passed = (not missing) and (not severe) and all(scores[name] >= MIN_SCORE for name in key)
        samples.append({'sampleId': row.get('sample_id', ''), 'video': row.get('video', ''),
                        'mode': row.get('mode', ''), 'role': (row.get('role') or 'acceptance').strip() or 'acceptance',
                        'scores': scores, 'keyDimensions': key, 'missingKeyScores': missing,
                        'severe': severe, 'passed': passed})
    # 准入判定只算 role=acceptance 的行；reference 行（例如修复前对照）仅作参考。
    graded = [item for item in samples if item['role'] == 'acceptance']
    passed = [item for item in graded if item['passed']]
    severe = [item for item in graded if item['severe']]
    incomplete = [item['sampleId'] for item in graded if item['missingKeyScores']]
    ratio = len(passed) / len(graded) if graded else 0.0
    verdict = 'accept' if (graded and not incomplete and not severe and ratio >= REQUIRED_PASS_RATIO) else 'reject'
    return {'verdict': verdict, 'samples': samples, 'complete': not incomplete,
            'counts': {'graded': len(graded), 'reference': len(samples) - len(graded), 'passed': len(passed),
                       'severe': len(severe)}, 'unscoredSamples': incomplete, 'passRatio': round(ratio, 4),
            'rule': '只看 role=acceptance 的行；关键项均 ≥3 视为通过；至少 5/6 通过且无一票否决才准入'}


def merge_into_reviews(summary, sheet_path, reviewer):
    """把人工评分合并进 reviews.json；人工条目与自动 VLM 条目用 reviewer 前缀区分。"""
    reviews_path = Path(__file__).resolve().parents[2] / 'docs/verification/2026-09-14-video-generation/vlm-review/reviews.json'
    reviews = json.loads(reviews_path.read_text()) if reviews_path.is_file() else []
    by_video = {item.get('video'): item for item in reviews}
    added = 0
    for sample in summary['samples']:
        video = sample['video']
        entry = by_video.get(video)
        if entry is None:
            entry = {'runId': sample['sampleId'], 'video': video, 'mode': sample['mode'], 'anchors': [],
                     'frames': [], 'defects': '', 'summary': ''}
            reviews.append(entry)
            by_video[video] = entry
            added += 1
        entry['scores'] = sample['scores']
        entry['role'] = sample['role']
        entry['severeFlag'] = sample['severe']
        entry['reviewer'] = 'human:' + reviewer
        entry['notHumanReview'] = False
        entry['summary'] = f"人工评分（表：{sheet_path.name}）：关键项{'通过' if sample['passed'] else '未通过'}"
    reviews_path.write_text(json.dumps(reviews, ensure_ascii=False, indent=2) + '\n')
    return {'reviews': str(reviews_path), 'merged': len(summary['samples']), 'added': added}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--sheet', required=True, type=Path)
    parser.add_argument('--write', action='store_true', help='同时合并进 reviews.json')
    parser.add_argument('--reviewer', default='owner', help='人工评分者标识')
    parser.add_argument('--machine-prescore', action='store_true',
                        help='标记本次汇总来自机器预评（供人工校准），不是人工验收结论')
    parsed = parser.parse_args()
    with parsed.sheet.open(encoding='utf-8', newline='') as handle:
        rows = list(csv.DictReader(handle))
    summary = summarize(rows)
    summary['sheet'] = parsed.sheet.name
    if parsed.machine_prescore:
        # 明确标注来源：机器预评只能当校准起点，不能当人工验收。
        summary['machinePrescore'] = True
        summary['reviewer'] = 'machine-prescore'
    output = parsed.sheet.with_name('review-summary.json')
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    if parsed.write:
        summary['mergedInto'] = merge_into_reviews(summary, parsed.sheet, parsed.reviewer)
        output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'machinePrescore': summary.get('machinePrescore', False),
                      'verdict': summary['verdict'], 'complete': summary['complete'],
                      'unscoredSamples': summary['unscoredSamples'], 'counts': summary['counts'],
                      'passRatio': summary['passRatio'], 'sheet': summary['sheet'],
                      'mergedInto': summary.get('mergedInto')}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
