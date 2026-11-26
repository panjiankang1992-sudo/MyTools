"""Verify acceptance isolation and that the fixture can never execute a write."""
from __future__ import annotations
import copy
import importlib.util
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from http.server import ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fixture = load('fixture_server')
preparer = load('prepare_build')


class FixtureIsolationTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), fixture.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, path, method='GET', authenticated=True):
        headers = {'Authorization': 'Bearer ui-subpage-acceptance-only'} if authenticated else {}
        request = urllib.request.Request(self.base + path, data=b'{}' if method != 'GET' else None,
                                         method=method, headers=headers)
        try:
            response = self.opener.open(request, timeout=2)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read())

    def test_reads_are_empty_and_local(self):
        self.assertEqual('127.0.0.1', self.server.server_address[0])
        status, body = self.request('/api/app/v1/download-tools/history')
        self.assertEqual(200, status)
        self.assertEqual([], body['data'])
        status, body = self.request('/api/app/v1/rss/entries?view=all&q=sample')
        self.assertEqual(200, status)
        self.assertEqual({'items': [], 'nextCursor': ''}, body['data'])

    def test_unknown_routes_and_wrong_identity_are_rejected(self):
        self.assertEqual(404, self.request('/api/app/v1/identity/login')[0])
        self.assertEqual(401, self.request('/api/app/v1/download-tools/history', authenticated=False)[0])

    def test_every_write_is_rejected_and_data_is_unchanged(self):
        before = copy.deepcopy(fixture.RESPONSES)
        for method in ('POST', 'PUT', 'PATCH', 'DELETE'):
            status, body = self.request('/api/app/v1/download-tools/messages', method)
            self.assertEqual(405, status)
            self.assertEqual('FIXTURE_READ_ONLY', body['code'])
        self.assertEqual(before, fixture.RESPONSES)

    def test_preparation_refuses_unowned_or_existing_target(self):
        with self.assertRaises(ValueError):
            preparer.prepare(Path('/tmp/not-an-owned-probe-directory'))
        with tempfile.TemporaryDirectory(prefix='mytools-ui-subpage-probe-', dir='/private/tmp') as directory:
            with self.assertRaises(ValueError):
                preparer.prepare(Path(directory))
            self.assertEqual([], list(Path(directory).iterdir()))


if __name__ == '__main__':
    unittest.main()
