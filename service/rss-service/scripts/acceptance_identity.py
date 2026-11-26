#!/usr/bin/env python3
"""仅在回环端口模拟测试身份校验，生产服务和抓取器保持原样。"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        size = int(self.headers.get('Content-Length', '0'))
        if self.path != '/internal/v1/gateway/tokens/validate' or size > 4096:
            self.send_error(404)
            return
        body = self.rfile.read(size)
        if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
            body = b''
            while True:
                chunk = int(self.rfile.readline(32).split(b';')[0], 16)
                if chunk == 0:
                    self.rfile.readline(2)
                    break
                if len(body) + chunk > 4096:
                    self.send_error(413)
                    return
                body += self.rfile.read(chunk)
                self.rfile.read(2)
        value = json.loads(body)
        active = value.get('accessToken') == 'rss-device-acceptance-only'
        body = json.dumps({'active': active, 'userId': 9001 if active else None,
                           'username': 'rss-device-acceptance', 'roles': [],
                           'sessionId': '11111111-1111-1111-1111-111111111111'}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 23461), Handler).serve_forever()
