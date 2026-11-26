"""验证真实存档回溯的时间、图片、重复和本地地区边界。"""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_weekly_report import material
from weekly_sources import backfill


class WeeklySourcesTest(unittest.TestCase):
    def test_archive_uses_publication_dates_merges_urls_and_freezes_once(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / 'jobs/current'
            source = directory / 'input/report-input.json'
            source.parent.mkdir(parents=True)
            source.write_text(json.dumps(material(1)))
            archived = material(3)
            archived['mode'] = 'DAILY'
            archived['sections'][0]['items'][1]['publishedAt'] = '2026-11-16T00:00:00Z'
            archived['sections'][0]['items'][2]['publishedAt'] = '2026-11-14T23:59:59Z'
            archived['sections'][0]['items'][1]['image'] = {'path': 'images/tech-1.png', 'sourceUrl': 'https://example.org/news'}
            archive = root / 'personal/1/2026-11-20/input'
            (archive / 'images').mkdir(parents=True)
            (archive / 'images/tech-1.png').write_bytes(b'original fixture image')
            (archive / 'report-input.json').write_text(json.dumps(archived))
            config = {'stateDirectory': str(root / 'personal'), 'ownerId': '1', 'zone': 'Asia/Shanghai'}
            backfill(config, directory)
            actual = json.loads(source.read_text())
            self.assertEqual(actual['backfill']['archiveItems'], 1)
            rows = actual['sections'][0]['items']
            self.assertEqual(len(rows), 2)
            self.assertEqual([row['rank'] for row in rows], [1, 2])
            self.assertEqual((source.parent / rows[1]['image']['path']).read_bytes(), b'original fixture image')
            self.assertEqual(actual['sections'][0]['coverage']['oldestPublishedAt'], '2026-11-16T00:00:00+00:00')
            before = source.read_bytes()
            (archive / 'report-input.json').write_text(json.dumps(material(5)))
            backfill(config, directory)
            self.assertEqual(source.read_bytes(), before)

    def test_different_local_region_and_undated_content_are_not_imported(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / 'jobs/current'
            source = directory / 'input/report-input.json'
            source.parent.mkdir(parents=True)
            source.write_text(json.dumps(material(0)))
            archived = material(1)
            local = copy.deepcopy(archived['sections'][0]['items'][0])
            archived['sections'][3]['items'] = [local]
            archived['region']['regionCode'] = 'CN-BJ'
            archived['sections'][0]['items'][0]['publishedAt'] = None
            archive = root / 'personal/1/2026-11-20/input'
            archive.mkdir(parents=True)
            (archive / 'report-input.json').write_text(json.dumps(archived))
            backfill({'stateDirectory': str(root / 'personal'), 'ownerId': '1', 'zone': 'Asia/Shanghai'}, directory)
            self.assertTrue(all(not row['items'] for row in json.loads(source.read_text())['sections']))


if __name__ == '__main__':
    unittest.main()
