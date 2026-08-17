import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

ACCESS = "header.payload.signature"
TICKET = "0123456789abcdef0123456789abcdef"
active = True


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format, *_args):
        return

    def send_json(self, status, data):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        global active
        parsed = urlsplit(self.path)
        if parsed.path == "/api/auth/login":
            length = int(self.headers.get("Content-Length", "0"))
            json.loads(self.rfile.read(length))
            active = True
            self.send_json(200, {"code": "0000", "data": {"accessToken": ACCESS}})
            return
        if parsed.path == "/api/auth/logout":
            active = False
            self.send_json(200, {"code": "0000", "data": None})
            return
        if parsed.path == "/api/app/v1/media/tickets":
            if self.headers.get("Authorization") != f"Bearer {ACCESS}":
                self.send_json(401, {"code": "20002"})
                return
            query = parse_qs(parsed.query)
            if query.get("accountId") != ["7"] or query.get("path") != ["/media/test.mp4"]:
                self.send_json(400, {"code": "400"})
                return
            self.send_json(200, {"code": "0000", "data": {"ticket": TICKET,
                "streamPath": f"/api/app/v1/media/tickets/{TICKET}",
                "expiresAt": "2099-01-01T00:00:00Z"}})
            return
        self.send_json(404, {"code": "404"})

    def do_GET(self):
        parsed = urlsplit(self.path)
        stream = f"/api/app/v1/media/tickets/{TICKET}"
        if parsed.path == f"{stream}/metrics":
            if self.headers.get("Authorization") != f"Bearer {ACCESS}" or not active:
                self.send_json(401, {"code": "90004"})
                return
            self.send_json(200, {"code": "0000", "data": {"transferredBytes": 1,
                "activeStreams": 0, "lastTransferTime": 1}})
            return
        if parsed.path == stream:
            if not active:
                self.send_json(401, {"code": "90004"})
                return
            if self.headers.get("Range") != "bytes=0-0":
                self.send_json(416, {"code": "416"})
                return
            self.send_response(206)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", "1")
            self.send_header("Content-Range", "bytes 0-0/10")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "private, no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(b"0")
            return
        self.send_json(404, {"code": "404"})


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
print(server.server_address[1], flush=True)
server.serve_forever()
