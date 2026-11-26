"""覆盖公开内容、发布快照、稳定身份与损坏恢复边界。"""
import copy
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import publish_rss as rss
import public_news


class PublishRssTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.report = self.root / 'report.json'
        self.now = datetime(2026, 11, 1, 0, 0, tzinfo=timezone.utc)
        self.config = {'enabled': True, 'baseUrl': 'https://news.example.com/news', 'directory': str(self.root / 'web'), 'feedFormat': 'editions'}
        self.data = {'cutoff': self.now.isoformat(), 'zone': 'Asia/Shanghai', 'region': {'regionCode': 'CN-JS', 'ip': 'private-ip'},
                     'recipients': ['private@example.com'], 'sections': []}
        for category in ('TECH', 'WORLD', 'DOMESTIC', 'LOCAL'):
            rows = []
            for rank in range(1, 21):
                rows.append({'rank': rank, 'eventId': category + str(rank), 'url': 'https://example.com/article', 'evidence': 'private-raw-evidence',
                             'sourceLinks': ['https://example.com/article?a=1&b=2'], 'detailAvailable': True,
                             'image': {'path': '/home/private/image.jpg'},
                             'analysis': {'title': 'A & B <script>alert(1)</script> ]]>', 'summary': 'Source facts ' + str(rank),
                                          'points': ['One', 'Two'], 'limitation': '', 'internal': 'private-model-data'}})
            self.data['sections'].append({'category': category, 'items': rows})
        self.image_patch = patch.object(public_news, 'create_infographic', side_effect=lambda item, row, path: path.write_bytes(b'\x89PNG\r\n\x1a\n' + row['title'].encode()))
        self.image_patch.start()
        self.addCleanup(self.image_patch.stop)

    def publish(self, data=None, now=None, source=None):
        self.report.write_text(json.dumps(data or self.data))
        return rss.publish(self.report, self.config, now or self.now, region_input=source)

    def feed(self, slug='tech'):
        return ET.parse(self.root / 'web/current/rss' / (slug + '.xml'))

    def test_full_report_has_five_feeds_full_details_and_no_internal_fields(self):
        result = self.publish()
        self.assertEqual(result['status'], 'published')
        site = self.root / 'web/current'
        self.assertEqual(len(list((site / 'rss').rglob('*.xml'))), 5)
        body = self.feed().findtext('channel/item/{' + rss.CONTENT + '}encoded')
        self.assertEqual(body.count('<h2>'), 10)
        self.assertEqual(body.count('<img '), 10)
        self.assertNotIn('<script>', body)
        self.assertIn('&lt;script&gt;', body)
        self.assertIn('https://news.example.com/news/assets/', body)
        public = ''.join(p.read_text() for p in site.rglob('*') if p.suffix in ('.html', '.xml', '.opml'))
        for private in ('private@example.com', 'private-raw-evidence', 'private-model-data', '/home/private', 'private-ip', 'cid:'):
            self.assertNotIn(private, public)
        self.assertFalse((site / 'manifest.json').exists())
        self.assertEqual(len(ET.parse(site / 'rss/categories.opml').findall('body/outline')), 4)

    def test_repeat_is_byte_identical_and_does_not_change_first_publication(self):
        first = self.publish()
        path = self.root / 'web/current/rss/tech.xml'
        data, modified = path.read_bytes(), path.stat().st_mtime_ns
        again = self.publish(now=self.now + timedelta(hours=1))
        self.assertEqual(again['status'], 'unchanged')
        self.assertEqual(first['release'], again['release'])
        self.assertEqual(path.read_bytes(), data)
        self.assertEqual(path.stat().st_mtime_ns, modified)

    def test_revision_preserves_guid_and_old_links_and_updates_validator(self):
        self.publish()
        old = self.feed().find('channel/item')
        old_file = self.root / 'web/current/rss/tech.xml'
        before = old_file.stat().st_mtime
        self.data['sections'][0]['items'][0]['analysis']['summary'] = 'Changed facts'
        self.publish(now=self.now + timedelta(minutes=1))
        new = self.feed().find('channel/item')
        self.assertEqual(old.findtext('guid'), new.findtext('guid'))
        self.assertEqual(old.findtext('pubDate'), new.findtext('pubDate'))
        self.assertNotEqual(old.findtext('link'), new.findtext('link'))
        old_relative = old.findtext('link').removeprefix(self.config['baseUrl'] + '/')
        self.assertTrue((self.root / 'web/current' / old_relative).is_file())
        self.assertGreater(old_file.stat().st_mtime, before)
        self.assertEqual(parsedate_to_datetime(new.findtext('pubDate')), self.now)

    def test_partial_category_recovers_with_same_overview_identity(self):
        partial = copy.deepcopy(self.data)
        partial['sections'] = partial['sections'][:3]
        result = self.publish(partial)
        self.assertEqual(result['status'], 'published_partial')
        old_guid = self.feed('daily').findtext('channel/item/guid')
        self.assertIn('LOCAL', self.feed('daily').findtext('channel/item/{' + rss.CONTENT + '}encoded'))
        self.publish(now=self.now + timedelta(minutes=5))
        self.assertEqual(self.feed('daily').findtext('channel/item/guid'), old_guid)

    def test_bad_source_only_excludes_affected_category(self):
        self.data['sections'][0]['items'][0]['sourceLinks'] = ['javascript:alert(1)']
        result = self.publish()
        self.assertEqual(result['failedSections'][0]['category'], 'TECH')
        self.assertEqual(len(self.feed('world').findall('channel/item')), 1)
        self.assertEqual(len(self.feed('tech').findall('channel/item')), 0)

    def test_crash_before_switch_keeps_previous_feed_and_retry_recovers(self):
        first = self.publish()
        self.data['sections'][0]['items'][0]['analysis']['summary'] = 'Changed facts'
        with patch.object(rss, 'switch_current', side_effect=OSError('simulated crash')):
            with self.assertRaises(OSError):
                self.publish(now=self.now + timedelta(minutes=1))
        self.assertEqual((self.root / 'web/current').resolve().parent.name, first['release'])
        self.assertEqual(self.publish(now=self.now + timedelta(minutes=2))['status'], 'published')

    def test_crash_after_switch_does_not_repeat_revision(self):
        original = rss.switch_current
        def after(root, release):
            original(root, release)
            raise OSError('simulated lost receipt')
        with patch.object(rss, 'switch_current', side_effect=after):
            with self.assertRaises(OSError):
                self.publish()
        self.assertEqual(self.publish(now=self.now + timedelta(minutes=2))['status'], 'unchanged')

    def test_unknown_region_does_not_publish_a_misleading_local_feed(self):
        self.data['region'] = {}
        result = self.publish()
        self.assertEqual(result['failedSections'][0]['code'], 'RSS_REGION_UNRESOLVED')
        self.assertFalse((self.root / 'web/current/rss/local').exists())

    def test_feed_window_and_size_budget_preserve_valid_xml(self):
        self.publish()
        self.data['cutoff'] = (self.now + timedelta(days=8)).isoformat()
        self.publish(now=self.now + timedelta(days=8))
        self.assertEqual(len(self.feed().findall('channel/item')), 1)
        self.config['maxFeedBytes'] = 16384
        self.data['sections'][0]['items'][0]['analysis']['summary'] = 'x' * 8000
        self.publish(now=self.now + timedelta(days=8, hours=1))
        self.assertLessEqual((self.root / 'web/current/rss/tech.xml').stat().st_size, 16384)

    def test_base_change_and_xml_controls_are_rejected(self):
        self.publish()
        self.config['baseUrl'] = 'https://another.example.com'
        with self.assertRaisesRegex(ValueError, 'RSS_BASE_URL_CHANGED'):
            self.publish()
        with self.assertRaisesRegex(ValueError, 'RSS_TEXT_INVALID'):
            public_news.clean_text('a\x01b')

    def enable_articles(self):
        self.config['feedFormat'] = 'articles'
        for section in self.data['sections']:
            for item in section['items']:
                item.update(url='https://example.com/' + item['eventId'], evidence='Source facts. ' * 30, rssDetailStatus='ready')
                item['analysis']['summary'] = 'A detailed source-backed summary. ' * 5

    def test_article_feeds_have_one_story_per_item_with_body_and_permanent_page(self):
        self.enable_articles()
        self.publish()
        self.assertEqual(len(self.feed('daily').findall('channel/item')), 40)
        for slug in ('tech','world','domestic','local/cn-js'):
            items = self.feed(slug).findall('channel/item')
            self.assertEqual(len(items), 10)
            for item in items:
                self.assertIn('/articles/', item.findtext('link'))
                self.assertTrue(item.findtext('guid').startswith('urn:mytools:news:article:'))
                body = item.findtext('{' + rss.CONTENT + '}encoded')
                self.assertIn('detailed source-backed', body)
                self.assertIn('<ul>', body)
                self.assertNotIn('<h2>', body)

    def test_compatibility_feed_preserves_full_details_images_and_identity_in_description(self):
        self.enable_articles()
        self.publish()
        full = {i.findtext('guid'): i for i in self.feed('daily').findall('channel/item')}
        compatible = self.feed('outlook')
        self.assertEqual(len(compatible.findall('channel/item')), 40)
        for item in compatible.findall('channel/item'):
            original = full[item.findtext('guid')]
            self.assertEqual(item.findtext('description'), original.findtext('{' + rss.CONTENT + '}encoded'))
            self.assertEqual(item.findtext('pubDate'), original.findtext('pubDate'))
            self.assertEqual(item.findtext('link'), original.findtext('link'))
            self.assertIsNone(item.find('{' + rss.CONTENT + '}encoded'))
        payload = (self.root / 'web/current/rss/outlook.xml').read_text()
        self.assertNotIn('xmlns:', payload)
        self.assertIn('&lt;img ', payload)
        self.assertEqual(len(ET.parse(self.root / 'web/current/rss/categories.opml').findall('body/outline')), 4)

    def test_compatibility_limits_old_items_without_truncating_content(self):
        records = [dict(key=str(n).zfill(3), day='2026-11-01', title='Story ' + str(n), path='articles/' + str(n),
                        description='Summary', category='TECH', body='<p>' + 'Detail &amp; facts ' * 100 + '</p>',
                        publishedAt=self.now.isoformat(), updatedAt=self.now.isoformat()) for n in range(125)]
        def render(maximum):
            payload = rss.rss_bytes(records, 'outlook', self.config['baseUrl'], maximum, articles=True, compatibility=True)
            self.assertLessEqual(len(payload), maximum)
            return ET.fromstring(payload).findall('channel/item')
        items = render(512 * 1024)
        self.assertEqual(len(items), 100)
        self.assertEqual(items[0].findtext('title'), '\u3010\u6280\u672f\u3011\u30102026/11/01\u3011Story 124')
        limited = render(16384)
        self.assertGreater(len(limited), 0)
        self.assertLess(len(limited), 100)
        for item in limited:
            self.assertEqual(item.findtext('description'), records[0]['body'])
        with self.assertRaisesRegex(ValueError, 'RSS_FEED_TOO_LARGE'):
            render(100)

    def test_renderer_upgrade_rebuilds_unchanged_reports_once(self):
        self.enable_articles()
        self.publish()
        site = (self.root / 'web/current').resolve()
        path = site.parent / 'manifest.json'
        state = json.loads(path.read_text())
        state['policy'].pop('rendererVersion')
        path.write_text(json.dumps(state))
        (site / 'rss/outlook.xml').unlink()
        self.assertEqual(self.publish()['status'], 'published')
        self.assertEqual(len(self.feed('outlook').findall('channel/item')), 40)
        self.assertEqual(self.publish()['status'], 'unchanged')

    def test_legacy_weekly_archive_uses_ranked_daily_limit_without_deleting_pages(self):
        self.enable_articles()
        # 模拟旧版本已发布的二十条榜单，升级时不重跑历史模型分析。
        with patch.object(public_news, 'MAX_CATEGORY_ITEMS', 20), patch.object(rss, 'MAX_CATEGORY_ITEMS', 20):
            self.publish()
        site = (self.root / 'web/current').resolve()
        before = json.loads((site.parent / 'manifest.json').read_text())
        self.assertEqual(len(self.feed('daily').findall('channel/item')), 80)
        expected = {a['key'] for r in before['records'].values() for a in r.get('articles', [])[:10]}
        self.publish()
        site = (self.root / 'web/current').resolve()
        reduced = json.loads((site.parent / 'manifest.json').read_text())
        self.assertEqual(len(self.feed('daily').findall('channel/item')), 40)
        for key, old in before['articles'].items():
            self.assertEqual(old, reduced['articles'][key])
            self.assertTrue((site / old['path']).is_file())
        self.data['cutoff'] = (self.now + timedelta(days=1)).isoformat()
        for section in self.data['sections']:
            for item in section['items']:
                item['url'] += '/next'
        self.publish(now=self.now + timedelta(days=1))
        site = (self.root / 'web/current').resolve()
        after = json.loads((site.parent / 'manifest.json').read_text())
        items = self.feed('daily').findall('channel/item')
        old_items = [i for i in items if '2026/11/01' in i.findtext('title')]
        self.assertEqual(len(items), 80)
        self.assertEqual({i.findtext('guid').split(':')[-1] for i in old_items}, expected)
        for key, old in before['articles'].items():
            self.assertEqual(old, after['articles'][key])
            self.assertTrue((site / old['path']).is_file())

    def test_top_ten_does_not_fill_missing_details_with_eleventh_story(self):
        self.enable_articles()
        self.data['sections'][0]['items'][0]['rssDetailStatus'] = 'insufficient'
        self.publish()
        ids = {i.findtext('guid').split(':')[-1] for i in self.feed('tech').findall('channel/item')}
        self.assertEqual(len(ids), 9)
        self.assertNotIn(public_news.article_identity(self.data['sections'][0]['items'][10]['url']), ids)

    def test_new_categories_have_dated_titles_and_independent_feeds(self):
        self.enable_articles()
        for category in list(rss.CATEGORIES)[4:]:
            section = copy.deepcopy(self.data['sections'][0])
            section['category'] = category
            for item in section['items']:
                item['eventId'] = category + str(item['rank'])
                item['url'] = 'https://example.com/' + item['eventId']
            self.data['sections'].append(section)
        self.publish()
        full = self.feed('daily').findall('channel/item')
        self.assertEqual(len(full), 100)
        for category, label in rss.CATEGORIES.items():
            slug = 'local/cn-js' if category == 'LOCAL' else category.lower()
            items = self.feed(slug).findall('channel/item')
            self.assertEqual(len(items), 10)
            self.assertTrue(all(i.findtext('title').startswith('\u3010' + label + '\u3011\u30102026/11/01\u3011') for i in items))
        self.assertEqual(len(ET.parse(self.root / 'web/current/rss/categories.opml').findall('body/outline')), 10)
        compatible = self.feed('outlook').findall('channel/item')
        self.assertEqual(len(compatible), 100)
        all_titles = {i.findtext('guid'): i.findtext('title') for i in full}
        self.assertTrue(all(i.findtext('title') == all_titles[i.findtext('guid')] for i in compatible))

    def test_title_prefix_is_idempotent_and_uses_report_day(self):
        record = {'category': 'WORLD', 'day': '2026-11-02', 'title': 'News', 'publishedAt': '2026-11-01T23:30:00+00:00'}
        expected = '\u3010\u56fd\u9645\u3011\u30102026/11/02\u3011News'
        self.assertEqual(rss.display_title(record), expected)
        record['title'] = expected
        self.assertEqual(rss.display_title(record), expected)
        record['day'] = '2026-11-03'
        self.assertEqual(rss.display_title(record), expected.replace('11/02', '11/03'))

    def test_repeated_original_url_across_days_keeps_one_article_and_guid(self):
        self.enable_articles()
        self.publish()
        before = {i.findtext('guid'): i.findtext('pubDate') for i in self.feed('daily').findall('channel/item')}
        self.data['cutoff'] = (self.now + timedelta(days=1)).isoformat()
        self.data['sections'][0]['items'][0]['analysis']['title'] = 'Revised title'
        self.publish(now=self.now + timedelta(days=1))
        after = {i.findtext('guid'): i.findtext('pubDate') for i in self.feed('daily').findall('channel/item')}
        self.assertEqual(before, after)

    def test_titles_without_details_are_excluded_from_article_feeds(self):
        self.enable_articles()
        self.data['sections'][0]['items'][0]['rssDetailStatus'] = 'insufficient'
        self.data['sections'][0]['items'][0]['detailAvailable'] = False
        self.publish()
        self.assertEqual(len(self.feed('daily').findall('channel/item')), 39)

    def test_article_feed_limit_cannot_silently_drop_week_history(self):
        self.enable_articles()
        self.publish()
        old = (self.root / 'web/current').resolve()
        self.config['maxFeedBytes'] = 16384
        with self.assertRaisesRegex(ValueError, 'RSS_FEED_TOO_LARGE'):
            self.publish()
        self.assertEqual((self.root / 'web/current').resolve(), old)

    def test_week_window_uses_report_timezone_at_morning_publication(self):
        self.enable_articles()
        self.publish(now=self.now - timedelta(minutes=30))
        current = (self.root / 'web/current').resolve()
        manifest = json.loads((current.parent / 'manifest.json').read_text())
        self.assertEqual(manifest['feedStart'], '2026-10-26')

    def source_picture(self, item, format='JPEG'):
        from PIL import Image
        source = self.root / 'input/report-input.json'
        source.parent.mkdir(exist_ok=True)
        (source.parent / 'images').mkdir(exist_ok=True)
        source.write_text(json.dumps({'region': self.data['region']}))
        extension = {'JPEG': 'jpg', 'PNG': 'png', 'GIF': 'gif'}[format]
        relative = 'images/source.' + extension
        Image.new('RGB', (160, 100), 'blue').save(source.parent / relative, format=format)
        item['image'] = {'path': relative, 'url': 'https://example.com/photo.' + extension,
                         'sourceUrl': item['url'], 'caption': 'Original <photo> & attribution'}
        return source

    def test_original_photo_replaces_infographic_with_source_attribution(self):
        self.enable_articles()
        item = self.data['sections'][0]['items'][0]
        source = self.source_picture(item)
        payload = (source.parent / item['image']['path']).read_bytes()
        self.publish(source=source)
        site = (self.root / 'web/current').resolve()
        record = json.loads((site.parent / 'manifest.json').read_text())['articles'][public_news.article_identity(item['url'])]
        self.assertEqual(record['imageKind'], 'SOURCE')
        self.assertEqual((site / 'assets' / record['images'][0]).read_bytes(), payload)
        self.assertIn('Original &lt;photo&gt; &amp; attribution', record['body'])
        self.assertIn(item['url'], record['body'])
        self.assertNotIn('\u975e\u73b0\u573a\u7167\u7247', record['body'])
        self.assertEqual(self.publish(source=source)['status'], 'unchanged')

    def test_tenth_news_retains_available_original_image(self):
        self.enable_articles()
        item = self.data['sections'][0]['items'][9]
        source = self.source_picture(item, 'PNG')
        self.publish(source=source)
        site = (self.root / 'web/current').resolve()
        articles = json.loads((site.parent / 'manifest.json').read_text())['articles']
        self.assertEqual(articles[public_news.article_identity(item['url'])]['imageKind'], 'SOURCE')
        self.assertEqual(len(articles), 40)

    def test_unsafe_missing_or_corrupt_original_falls_back_without_leaking_files(self):
        self.enable_articles()
        item = self.data['sections'][0]['items'][0]
        source = self.source_picture(item)
        valid = copy.deepcopy(item['image'])
        assets = self.root / 'assets'
        assets.mkdir()
        outside = self.root / 'secret.jpg'
        outside.write_bytes((source.parent / valid['path']).read_bytes())
        (source.parent / 'images/link.jpg').symlink_to(outside)
        for change in ({'path': '../secret.jpg'}, {'path': str(outside)}, {'path': 'images/link.jpg'},
                       {'path': 'images/missing.jpg'}, {'sourceUrl': 'https://unrelated.example/article'}):
            with self.subTest(change=change):
                item['image'] = dict(valid, **change)
                self.assertIsNone(public_news.original_image(item, source.parent, assets))
        item['image'] = valid
        (source.parent / valid['path']).write_bytes(b'not an image')
        self.publish(source=source)
        site = (self.root / 'web/current').resolve()
        record = json.loads((site.parent / 'manifest.json').read_text())['articles'][public_news.article_identity(item['url'])]
        self.assertEqual(record['imageKind'], 'INFOGRAPHIC')
        self.assertFalse(list(assets.iterdir()))


if __name__ == '__main__':
    unittest.main()
