"""验证验收报告同步、重复发布、旧任务防倒退及原有日报兼容。"""
import base64
import copy
from datetime import datetime, timedelta, timezone
import hashlib
from io import BytesIO
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid
import xml.etree.ElementTree as ET
from PIL import Image

import public_news
import weekly_rss as weekly


class FixtureClient:
    """模拟已提交的只读 API，与本地报告草稿没有关联。"""

    def __init__(self):
        self.owner = 7
        end = datetime.now(timezone.utc).replace(microsecond=0)
        self.job = {'id': str(uuid.uuid4()), 'status': 'SUCCEEDED', 'reportRevision': 1,
                    'windowEnd': end.isoformat()}
        self.metadata = {'id': self.job['id'], 'status': 'SUCCEEDED', 'revision': 1,
                         'windowStart': (end - timedelta(days=7)).isoformat(), 'windowEnd': end.isoformat(),
                         'region': {'regionCode': 'CN-JS'}, 'articleCount': 20, 'sections': []}
        image = BytesIO()
        Image.new('RGB', (160, 100), 'blue').save(image, 'JPEG')
        self.asset = hashlib.sha256(image.getvalue()).hexdigest()
        self.image = {'mime': 'image/jpeg', 'base64': base64.b64encode(image.getvalue()).decode()}
        self.articles, self.listings = {}, {}
        for category in public_news.CATEGORIES:
            rows = []
            for rank in range(1, 21) if category == 'TECH' else []:
                event = 'event-' + str(rank)
                article = {'rank': rank, 'eventId': event, 'title': 'Verified story ' + str(rank),
                           'summary': 'A source supported summary with sufficient detail for this published news article.',
                           'points': ['A verified source fact.'], 'limitation': '',
                           'sourceLinks': ['https://example.org/news/' + str(rank)]}
                if rank == 1:
                    article['image'] = {'assetId': self.asset, 'sourceUrl': article['sourceLinks'][0], 'caption': 'Original image'}
                self.articles[event] = article
                rows.append({'eventId': event})
            self.metadata['sections'].append({'category': category, 'count': len(rows)})
            self.listings[category] = {'items': rows}
        self.reads = []

    def get(self, path, **params):
        self.reads.append((path, params))
        if path == '/weekly-reports':
            return {'items': [copy.deepcopy(self.job)], 'nextCursor': None}
        suffix = path.removeprefix('/weekly-reports/' + self.job['id'])
        if not suffix:
            return copy.deepcopy(self.metadata)
        if suffix.startswith('/categories/'):
            return copy.deepcopy(self.listings[suffix.split('/')[-1]])
        if suffix.startswith('/articles/'):
            return copy.deepcopy(self.articles[suffix.split('/')[-1]])
        if suffix == '/assets/' + self.asset:
            return copy.deepcopy(self.image)
        raise AssertionError('Unexpected fixture path')


class WeeklyRssTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config = {'stateDirectory': str(self.root / 'state'), 'rss': {
            'enabled': True, 'baseUrl': 'https://news.example.org/news',
            'directory': str(self.root / 'public'), 'feedFormat': 'articles'}}
        self.client = FixtureClient()

    def items(self, slug='daily'):
        return ET.parse(self.root / 'public/current/rss' / (slug + '.xml')).findall('channel/item')

    def test_twenty_individual_articles_original_image_empty_categories_and_repeat(self):
        with patch.object(public_news, 'create_infographic', side_effect=AssertionError('No generated images')):
            first = weekly.synchronize(self.config, self.client)
            self.assertEqual(first['status'], 'published')
            self.assertEqual(len(self.items()), 20)
            self.assertEqual(len(self.items('tech')), 20)
            self.assertEqual(len(self.items('trending')), 0)
            bodies = [item.findtext('{http://purl.org/rss/1.0/modules/content/}encoded') for item in self.items()]
            self.assertEqual(sum(body.count('<img ') for body in bodies), 1)
            self.assertTrue(all('A verified source fact.' in body for body in bodies))
            site = self.root / 'public/current'
            before = {path.name: (path.read_bytes(), path.stat().st_mtime_ns) for path in (site / 'rss').glob('*.xml')}
            self.client.reads.clear()
            self.assertEqual(weekly.synchronize(self.config, self.client)['status'], 'unchanged')
            self.assertEqual(len(self.client.reads), 1)
            after = {path.name: (path.read_bytes(), path.stat().st_mtime_ns) for path in (site / 'rss').glob('*.xml')}
            self.assertEqual(before, after)
            public = ''.join(path.read_text() for path in site.rglob('*') if path.suffix in {'.xml', '.html'})
            self.assertNotIn(self.client.job['id'], public)
            self.assertNotIn('ownerId', public)

    def test_publish_failure_retries_without_advancing_watermark_and_crash_replay_is_stable(self):
        with patch.object(weekly, 'publish', side_effect=OSError('Disk unavailable')):
            with self.assertRaises(OSError):
                weekly.synchronize(self.config, self.client)
        self.assertFalse((self.root / 'state/state.json').exists())
        first = weekly.synchronize(self.config, self.client)
        # 模拟切换 RSS 成功、消费水位写入前崩溃。
        (self.root / 'state/state.json').unlink()
        second = weekly.synchronize(self.config, self.client)
        self.assertEqual(second['status'], 'unchanged')
        self.assertEqual(first['release'], second['release'])

    def test_new_window_keeps_article_guids_then_empty_category_removes_old_feed_items(self):
        weekly.synchronize(self.config, self.client)
        guids = {item.findtext('guid') for item in self.items()}
        self.client.job['id'] = str(uuid.uuid4())
        self.client.metadata['id'] = self.client.job['id']
        end = weekly.moment(self.client.job['windowEnd']) + timedelta(seconds=301)
        self.client.job['windowEnd'] = self.client.metadata['windowEnd'] = end.isoformat()
        self.client.metadata['windowStart'] = (end - timedelta(days=7)).isoformat()
        self.client.articles['event-1']['summary'] += ' Updated facts.'
        weekly.synchronize(self.config, self.client)
        self.assertEqual({item.findtext('guid') for item in self.items()}, guids)
        self.assertEqual(len(self.items()), 20)
        self.client.job['reportRevision'] = self.client.metadata['revision'] = 2
        self.client.listings['TECH']['items'] = []
        self.client.metadata['sections'][0]['count'] = self.client.metadata['articleCount'] = 0
        weekly.synchronize(self.config, self.client)
        self.assertEqual(self.items(), [])

    def test_snapshot_revision_race_or_invalid_asset_preserves_previous_release(self):
        weekly.synchronize(self.config, self.client)
        target = (self.root / 'public/current').resolve()
        self.client.job['reportRevision'] = 2
        with self.assertRaisesRegex(ValueError, 'REVISION_CHANGED'):
            weekly.synchronize(self.config, self.client)
        self.client.metadata['revision'] = 2
        self.client.image['base64'] = base64.b64encode(b'invalid-image-bytes').decode()
        with self.assertRaisesRegex(ValueError, 'ASSET_HASH_INVALID'):
            weekly.synchronize(self.config, self.client)
        self.assertEqual((self.root / 'public/current').resolve(), target)
        self.assertTrue(json.loads((self.root / 'state/state.json').read_text())['publicationId'].endswith(':1'))

    def test_unfinished_or_older_report_and_wrong_owner_cannot_publish(self):
        self.client.job['status'] = 'RUNNING'
        self.assertEqual(weekly.synchronize(self.config, self.client)['status'], 'no_report')
        self.client.job['status'] = 'SUCCEEDED'
        weekly.synchronize(self.config, self.client)
        self.client.job['windowEnd'] = (weekly.moment(self.client.job['windowEnd']) - timedelta(days=1)).isoformat()
        self.assertEqual(weekly.synchronize(self.config, self.client)['status'], 'older_ignored')
        self.client.owner += 1
        with self.assertRaisesRegex(ValueError, 'OWNER_CHANGED'):
            weekly.synchronize(self.config, self.client)

    def test_internal_credentials_are_pinned_to_loopback_without_redirects(self):
        for base in ('https://example.org', 'http://127.0.0.1:23350@other.test', 'http://localhost:23350'):
            with self.assertRaisesRegex(ValueError, 'API_INVALID'):
                weekly.Client(base, 7, 'test-token')
        self.assertIsNone(weekly.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.org'))


if __name__ == '__main__':
    unittest.main()
