"""公网验收必须识别拦截页、截断和不同客户端返回不同内容。"""
import hashlib
import unittest
from unittest.mock import patch
import check_public_rss as check

XML = b'<rss version="2.0"><channel><item><title>News</title><link>https://example.test/news/articles/example/r1.html</link><description>Details</description><pubDate>Wed, 04 Nov 2026 03:00:00 +0000</pubDate><guid>news-1</guid></item></channel></rss>'
HEADERS = {'content-type': 'application/rss+xml; charset=utf-8', 'etag': '"example"'}


class PublicRssCheckTest(unittest.TestCase):
    """覆盖真实故障形态，避免 curl 退出成功被当成客户端验收通过。"""

    def test_block_page_and_partial_xml_are_failures(self):
        with self.assertRaisesRegex(ValueError, 'RSS_HTTP_403'):
            check.validate_response(403, b'error code: 1010', {'content-type': 'text/plain'})
        with self.assertRaisesRegex(ValueError, 'RSS_CONTENT_TYPE_INVALID'):
            check.validate_response(200, b'<html>Challenge</html>', {'content-type': 'text/html'})
        with self.assertRaisesRegex(ValueError, 'RSS_XML_INVALID'):
            check.validate_response(200, XML[:-9], HEADERS)

    def test_empty_and_different_snapshots_are_failures(self):
        with self.assertRaisesRegex(ValueError, 'RSS_HTTP_304'):
            check.validate_response(304, b'', HEADERS)
        with self.assertRaisesRegex(ValueError, 'RSS_SNAPSHOT_MISMATCH'):
            check.validate_response(200, XML.replace(b'Details', b'Other'), HEADERS, hashlib.sha256(XML).hexdigest())

    def test_healthy_six_request_cycle(self):
        def response(url, name, agent, compressed, timeout, etag=None, maximum=None):
            if name == 'article':
                return {'name': name, 'status': 200}, b'<html>News</html>', {'content-type': 'text/html'}
            return {'name': name, 'status': 304 if etag else 200}, b'' if etag else XML, HEADERS
        with patch.object(check, 'request', side_effect=response):
            result = check.check('https://example.test/news/rss/outlook.xml', 30)
        self.assertTrue(result['ok'])
        self.assertEqual(len(result['checks']), 7)

    def test_browser_success_does_not_hide_windows_rss_block(self):
        def response(url, name, agent, compressed, timeout, etag=None, maximum=None):
            if name == 'article':
                return {'name': name, 'status': 200}, b'<html>News</html>', {'content-type': 'text/html'}
            if name.startswith('windows-rss'):
                return {'name': name, 'status': 403}, b'error code: 1010', {'content-type': 'text/plain'}
            return {'name': name, 'status': 304 if etag else 200}, b'' if etag else XML, HEADERS
        with patch.object(check, 'request', side_effect=response):
            result = check.check('https://example.test/news/rss/outlook.xml', 30)
        self.assertFalse(result['ok'])
        self.assertEqual([row['error'] for row in result['checks'] if 'error' in row], ['RSS_HTTP_403'] * 2)

    def test_feed_success_does_not_hide_linked_article_block(self):
        with patch.object(check, 'request', return_value=({'name': 'article', 'status': 403}, b'Blocked', {})):
            result = check.check_resources('https://example.test/news/rss/outlook.xml', XML, 30)
        self.assertEqual(result[0]['error'], 'RSS_RESOURCE_HTTP_403')

    def test_external_resource_is_not_requested(self):
        with patch.object(check, 'request') as request:
            result = check.check_resources('https://other.test/news/rss/outlook.xml', XML, 30)
        request.assert_not_called()
        self.assertEqual(result[0]['error'], 'RSS_RESOURCE_URL_INVALID')

    def test_plain_response_rejects_cache_compression_chunking_and_304(self):
        headers = {'content-type': HEADERS['content-type'], 'cache-control': 'no-store, no-transform',
                   'content-length': str(len(XML))}
        self.assertEqual(check.validate_response(200, XML, headers, plain_response=True)['items'], 1)
        for change in ({'cache-control': 'public, max-age=300'}, {'etag': '"old"'},
                       {'content-encoding': 'gzip'}, {'transfer-encoding': 'chunked'}, {'content-length': '1'}):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'RSS_PLAIN_RESPONSE_INVALID'):
                check.validate_response(200, XML, dict(headers, **change), plain_response=True)
        with self.assertRaisesRegex(ValueError, 'RSS_HTTP_304'):
            check.validate_response(304, b'', headers, conditional=True, plain_response=True)

    def test_plain_response_probes_previous_etag_and_modified_since_without_current_etag(self):
        conditional_requests = []
        def response(url, name, agent, compressed, timeout, etag=None, maximum=None, modified_since=None):
            if name == 'article':
                return {'name': name, 'status': 200}, b'<html>News</html>', {'content-type': 'text/html'}
            if etag or modified_since:
                conditional_requests.append((etag, modified_since))
            return {'name': name, 'status': 200}, XML, {
                'content-type': HEADERS['content-type'], 'cache-control': 'no-store, no-transform',
                'content-length': str(len(XML))}
        with patch.object(check, 'request', side_effect=response):
            result = check.check('https://example.test/news/rss/outlook.xml', 30, plain_response=True)
        self.assertTrue(result['ok'])
        self.assertEqual(len(conditional_requests), 2 * len(check.CLIENTS))
        self.assertTrue(all(bool(etag) != bool(modified_since) for etag, modified_since in conditional_requests))


if __name__ == '__main__':
    unittest.main()
