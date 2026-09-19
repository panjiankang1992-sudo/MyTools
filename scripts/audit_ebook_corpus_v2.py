"""只读核对证据版结果；结构通过不等于语义通过，不自动批准全量。"""
import argparse
from collections import Counter
import json
from pathlib import Path
import sqlite3

from analyze_ebook_corpus import chunks, extract
from analyze_ebook_corpus_v2 import REVIEW_ONLY_TRAITS, aggregate, digest, segments, validate


def main():
    """核对完整覆盖、每条证据摘要和整书汇总，输出待审核样本。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--state-dir', type=Path, required=True)
    parser.add_argument('--review-excerpts', action='store_true')
    args = parser.parse_args()
    status = json.loads((args.state_dir / 'status.json').read_text())
    pilot_ids = [b['bookId'] for b in json.loads((args.source_dir / 'book-summaries.json').read_text())][:2]
    manifest = {b['id']: b for b in json.loads((args.source_dir / 'manifest.json').read_text())}
    coverage = {b['id']: b for b in json.loads((args.source_dir / 'coverage.json').read_text())}
    db = sqlite3.connect('file:' + str(args.state_dir / 'progress.sqlite3') + '?mode=ro', uri=True)
    results = db.execute('SELECT key,value FROM result ORDER BY rowid').fetchall()
    counts = Counter()
    by_book = {}
    texts = {}
    review = []
    trait_examples = {}
    for book_id in pilot_ids:
        book = manifest[book_id]
        raw = Path(book['path']).read_bytes()
        assert digest(raw) == book['content_sha256']
        texts[book_id] = extract(raw, book['format'])[0]
        assert digest(texts[book_id].encode()) == coverage[book_id]['textSha256']
        by_book[book_id] = []
    for key, raw_value in results:
        book_id, begin, finish = key.split(':')
        if book_id not in texts:
            continue
        start, end = int(begin), int(finish)
        text = texts[book_id]
        assert 0 <= start < end <= len(text)
        value = json.loads(raw_value)
        assert digest(text[start:end].encode()) == value['textSha256']
        parts = segments(text[start:end], start)
        normalized = {'claims': [{'trait': c['trait'], 'segmentIds': [e['segmentId'] for e in c['evidence']],
                                  'confidence': c['confidence']} for c in value['claims']]}
        checked = validate(normalized, parts)
        assert checked['claims'] == value['claims'] and not checked['rejectedEvidence']
        for claim in value['claims']:
            counts['claims'] += 1
            counts[claim['confidence']] += 1
            counts['evidenceRefs'] += len(claim['evidence'])
            if claim['confidence'] == 'observed' and claim['trait'] not in REVIEW_ONLY_TRAITS and claim['trait'] not in trait_examples:
                trait_examples[claim['trait']] = {'chunkKey': key, 'trait': claim['trait'], 'evidence': claim['evidence'][0]}
        counts['rejectedMetricRefs'] += len(value['rejectedEvidence'])
        counts['emptyChunks'] += not bool(value['claims'])
        by_book[book_id].append((key, value))
    book_reports = []
    for book_id, rows in by_book.items():
        expected_keys = [book_id + ':' + str(a) + ':' + str(b) for a, b, _ in chunks(texts[book_id])]
        complete = set(k for k, _ in rows) == set(expected_keys)
        summary_matches = False
        path = args.state_dir / ('book-' + book_id + '.json')
        if complete and path.exists():
            summary = json.loads(path.read_text())
            calculated = aggregate(rows)
            summary_matches = all(summary[k] == v for k, v in calculated.items())
            assert summary_matches
        book_reports.append({'bookId': book_id, 'analyzedChunks': len(rows), 'expectedChunks': len(expected_keys),
                             'completeCoverage': complete, 'summaryMatchesAllEvidence': summary_matches})
        # 每书选前中后三块，再选择不同标签，给人工语义复核使用。
        used = set()
        for index in sorted({0, len(rows) // 2, max(0, len(rows) - 1)}):
            if not rows:
                continue
            key, value = rows[index]
            claims = [c for c in value['claims'] if c['confidence'] == 'observed' and c['trait'] not in REVIEW_ONLY_TRAITS]
            chosen = next((c for c in claims if c['trait'] not in used), claims[0] if claims else None)
            if chosen:
                used.add(chosen['trait'])
                evidence = chosen['evidence'][0]
                item = {'chunkKey': key, 'trait': chosen['trait'], 'evidence': evidence}
                if args.review_excerpts:
                    item['text'] = texts[book_id][evidence['start']:evidence['end']]
                review.append(item)
    grouped = {}
    for item in review + list(trait_examples.values()):
        ref = item['evidence']
        book_id = item['chunkKey'].split(':')[0]
        key = (book_id, ref['start'], ref['end'])
        if key not in grouped:
            grouped[key] = {'bookId': book_id, 'start': ref['start'], 'end': ref['end'], 'traits': []}
            if args.review_excerpts:
                grouped[key]['text'] = texts[book_id][ref['start']:ref['end']]
        if item['trait'] not in grouped[key]['traits']:
            grouped[key]['traits'].append(item['trait'])
    print(json.dumps({'status': status, 'structuralAuditPassed': True,
                      'pilotComplete': all(b['completeCoverage'] and b['summaryMatchesAllEvidence'] for b in book_reports),
                      'counts': counts, 'books': book_reports, 'semanticReviewRequired': True,
                      'reviewSamples': list(grouped.values())}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
