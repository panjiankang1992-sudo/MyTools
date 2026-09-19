"""停止分析后按现有证据汇总；不调用模型，不把部分书视为完整书。"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import sqlite3
import statistics
from datetime import datetime, timezone


def main():
    """独立计算分块覆盖与等书权标签比例，并保存停止收据。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--state-dir', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path, required=True)
    args = parser.parse_args()
    root = args.state_dir
    coverage = {b['id']: b for b in json.loads((args.source_dir / 'coverage.json').read_text())}
    identity = json.loads((root / 'identity.json').read_text())
    db = sqlite3.connect('file:' + str(root / 'progress.sqlite3') + '?mode=ro', uri=True)
    groups = defaultdict(list)
    rows = db.execute('SELECT key,value FROM result ORDER BY key').fetchall()
    total_refs = rejected = 0
    for key, raw in rows:
        book, start, end = key.split(':')
        value = json.loads(raw)
        groups[book].append((int(start), int(end), value))
        total_refs += sum(len(c['evidence']) for c in value['claims'])
        rejected += len(value['rejectedEvidence'])
    books = []
    for book, values in groups.items():
        values.sort()
        position = 0
        counts = {'observed': Counter(), 'tentative': Counter()}
        for start, end, value in values:
            assert start == position and end > start
            position = end
            seen = set()
            for claim in value['claims']:
                assert claim['trait'] not in seen
                seen.add(claim['trait'])
                counts[claim['confidence']][claim['trait']] += 1
                for ref in claim['evidence']:
                    assert start <= ref['start'] < ref['end'] <= end and len(ref['sha256']) == 64
        expected = coverage[book]
        complete = position == expected['codepoints'] and len(values) == expected['chunks']
        if complete:
            summary = json.loads((root / ('book-' + book + '.json')).read_text())
            assert summary['analyzedChunks'] == len(values)
            combined = dict(summary['traits'], **summary['reviewOnlyTraits'])
            for kind, counter in counts.items():
                for trait, n in counter.items():
                    assert combined[trait][kind + 'Chunks'] == n
        books.append({'id': book, 'complete': complete, 'analyzedChunks': len(values),
                      'expectedChunks': expected['chunks'], 'analyzedCodepoints': position,
                      'expectedCodepoints': expected['codepoints'], 'sourceSha256': expected['sha256'],
                      'observed': dict(counts['observed']), 'tentative': dict(counts['tentative'])})
    complete = [b for b in books if b['complete']]
    unique = list({b['sourceSha256']: b for b in complete}.values())
    excluded = {'first_person_narration', 'third_person_narration', 'information_withholding'}
    traits = sorted({t for b in unique for t in b['observed']} - excluded)
    rates = []
    for trait in traits:
        values = [b['observed'].get(trait, 0) / b['analyzedChunks'] for b in unique]
        rates.append({'trait': trait, 'booksWithObservedEvidence': sum(v > 0 for v in values),
                      'bookDenominator': len(unique), 'equalBookMeanChunkShare': statistics.mean(values),
                      'medianBookChunkShare': statistics.median(values),
                      'observedChunks': sum(b['observed'].get(trait, 0) for b in unique)})
    rates.sort(key=lambda r: r['equalBookMeanChunkShare'], reverse=True)
    report = {'status': 'STOPPED_BY_USER_SUMMARIZED_EXISTING', 'asOfUtc': datetime.now(timezone.utc).isoformat(),
              'identity': identity, 'databaseSha256': hashlib.sha256((root / 'progress.sqlite3').read_bytes()).hexdigest(),
              'analyzedChunks': len(rows), 'originalPlannedChunks': sum(b['chunks'] for b in coverage.values()),
              'completeBooks': len(complete), 'uniqueCompleteBooks': len(unique), 'partialBooks': len(books)-len(complete),
              'analyzedBooks': len(books), 'retainedEvidenceRefs': total_refs, 'rejectedMetricRefs': rejected,
              'rankedTraits': rates, 'books': books, 'excludedTraits': sorted(excluded),
              'scope': 'User authorized stopping remainder. Main findings use complete books only; partial-book evidence retained separately. Model detections, not population estimates.'}
    (root / 'stopped-corpus-summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    status = json.loads((root / 'status.json').read_text())
    status.update({'status': report['status'], 'completedChunks': len(rows), 'completedBooks': len(complete),
                   'scopeDecision': 'USE_EXISTING_RESULTS_WITHOUT_WAITING_FOR_REMAINDER'})
    (root / 'status.json').write_text(json.dumps(status, indent=2))
    print(json.dumps({k: v for k, v in report.items() if k not in ('books', 'identity')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
