"""验证详情补齐不改变原报告，缓存可重放且不会把空材料变成正文。"""
import json
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import rss_article_details as details
from personal_report import decode_analysis, AnalysisValidationError


class RssArticleDetailsTest(unittest.TestCase):
    def test_ready_requires_substantive_summary_and_points(self):
        material = {'items': [{'id': 'one'}]}
        invalid = {'items': [{'id': 'one', 'status': 'ready', 'title': 'title', 'summary': 'title', 'points': [], 'limitation': ''}]}
        with self.assertRaises(AnalysisValidationError) as error:
            decode_analysis(json.dumps(invalid), material, details.validate)
        self.assertEqual(error.exception.feedback['requiredIds'], ['one'])

    def test_short_details_are_enriched_once_and_original_is_frozen(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = root / 'report.json'; output = root / 'details'
            items = [{'title': 'Some news', 'evidence': 'Facts with enough source material. ' * 10, 'rank': 1,
                      'detailAvailable': True, 'analysis': {'title': 'Some news', 'summary': 'A short summary', 'points': [], 'limitation': ''}},
                     {'title': 'Only title', 'evidence': '', 'analysis': {'summary': 'Only title', 'points': []}}]
            source.write_text(json.dumps({'cutoff': '2026-11-13T23:30:00Z', 'zone': 'Asia/Shanghai',
                                          'sections': [{'category': 'TECH', 'items': items}]}))
            frozen = source.read_bytes()
            def analyze(material, *args):
                return {'items': [{'id': row['id'], 'status': 'ready', 'title': 'Some news', 'summary': 'Verified source facts. ' * 10,
                                   'points': ['Verified key fact.'], 'limitation': ''} for row in material['items']]}
            with patch.object(details, 'analyze_material', side_effect=analyze) as model:
                report, stats = details.prepare(source, output)
                self.assertEqual(stats['generated'], 1)
                self.assertEqual(stats['pending'], 1)
                details.prepare(source, output)
                model.assert_called_once()
                self.assertEqual(model.call_args.args[-1], '2026-11-14 \u6280\u672f \u8be6\u60c5\u8865\u5168 1')
            self.assertEqual(source.read_bytes(), frozen)
            rows = json.loads(report.read_text())['sections'][0]['items']
            self.assertTrue(rows[0]['analysis']['points'])
            self.assertEqual(rows[1]['rssDetailStatus'], 'insufficient')

    def test_legacy_tail_does_not_invoke_analysis_or_modify_frozen_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'report.json'
            item = {'title': 'News', 'evidence': 'Source material. ' * 20, 'detailAvailable': True,
                    'analysis': {'title': 'News', 'summary': 'Verified details. ' * 20, 'points': ['Fact']}}
            items = [dict(copy.deepcopy(item), rank=rank) for rank in range(1, 21)]
            for tail in items[10:]:
                tail['analysis']['summary'] = 'Short'
                tail['analysis']['points'] = []
            source.write_text(json.dumps({'sections': [{'category': 'WORLD', 'items': items}]}))
            frozen = source.read_bytes()
            with patch.object(details, 'analyze_material') as model:
                report, stats = details.prepare(source, root / 'details')
                model.assert_not_called()
            self.assertEqual(stats['reused'], 10)
            self.assertEqual(len(json.loads(report.read_text())['sections'][0]['items']), 10)
            self.assertEqual(source.read_bytes(), frozen)


if __name__ == '__main__':
    unittest.main()
