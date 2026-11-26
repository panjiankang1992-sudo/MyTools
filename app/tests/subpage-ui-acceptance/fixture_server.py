"""Read-only localhost fixture for the isolated native UI acceptance HAP."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import json

RESPONSES = {
    '/api/app/v1/download-tools/history': [],
    '/api/app/v1/rss/capabilities': {'pollingEnabled': False, 'opml': False, 'maxSubscriptions': 100},
    '/api/app/v1/rss/subscriptions': [],
    '/api/app/v1/rss/groups': [],
    '/api/app/v1/rss/counts': {'subscriptions': 0, 'unread': 0, 'starred': 0},
    '/api/app/v1/rss/entries': {'items': [], 'nextCursor': ''},
}


class Handler(BaseHTTPRequestHandler):
    """Only fixture reads are served; every write is rejected without side effects."""
    def log_message(self, *_args):
        pass

    def respond(self, status, data, code='0000'):
        body = json.dumps({'code': code, 'message': '', 'data': data}).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path not in RESPONSES:
            self.respond(404, None, 'FIXTURE_NOT_FOUND')
        elif self.headers.get('Authorization') != 'Bearer ui-subpage-acceptance-only':
            self.respond(401, None, 'FIXTURE_AUTH')
        else:
            self.respond(200, RESPONSES[path])

    def do_POST(self):
        self.respond(405, None, 'FIXTURE_READ_ONLY')
    do_PUT = do_POST
    do_DELETE = do_POST
    do_PATCH = do_POST


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 23460), Handler).serve_forever()
