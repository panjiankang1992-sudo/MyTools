"""接收已由公网页面鉴权的分片，并将完整请求一次性转交本机 DSH。"""

import argparse
from dataclasses import dataclass
import fcntl
from hashlib import sha256
from http.client import HTTPConnection, HTTPException
from http.server import BaseHTTPRequestHandler
import json
import os
from pathlib import Path
import re
import shutil
import socket
import socketserver
import tempfile
import threading
import time
from urllib.parse import parse_qsl, urlencode


PART_SIZE = 256 * 1024
MAX_SIZE = 300 * 1024 * 1024
MAX_STAGED = 600 * 1024 * 1024
MAX_ACTIVE = 8
MAX_OWNER_ACTIVE = 2
MAX_RECORDS = 512
MAX_OWNER_RECORDS = 48
MAX_HANDLERS = 24
RESPONSE_LIMIT = 256 * 1024
STAGE_TTL = 30 * 60
RECEIPT_TTL = 60 * 60
PATHS = {
    "prompt": "/api/session/prompt",
    "file-json": "/api/fileUploads/upload",
    "file-binary": "/api/session/uploadFileBinary",
}
UPLOAD_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
GRANT = re.compile(r"__Host-mytools-dsh=([A-Za-z0-9_-]{43})")
UPSTREAM_COOKIE = re.compile(r"dsh-auth-[A-Za-z0-9_-]{43}=v1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}")
DECIMAL = re.compile(r"0|[1-9][0-9]{0,9}")
CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f]")
MEDIA_TYPE = re.compile(r"[A-Za-z0-9][A-Za-z0-9!#$&^_.+\-]{0,126}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+\-]{0,126}")


@dataclass(frozen=True)
class Reply:
    status: int
    body: bytes = b""
    content_type: str = "application/json"
    state: str = ""


class UploadError(Exception):
    def __init__(self, status, code, state=""):
        self.status = status
        self.code = code
        self.state = state


def persist(path, content):
    """原子替换且同步父目录，确保上游请求开始前已持久化提交意图。"""
    fd, temp = tempfile.mkstemp(dir=path.parent, prefix=".write-")
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
        sync_directory(path.parent)
    finally:
        Path(temp).unlink(missing_ok=True)


def sync_directory(path):
    directory = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def normalize_binary_query(raw):
    if (not raw or len(raw) > 4700 or not raw.isascii() or re.search(r"[\x00-\x20\x7f]", raw)
            or re.search(r"%(?![A-Fa-f0-9]{2})", raw)):
        raise UploadError(400, "upload_invalid_query")
    try:
        pairs = parse_qsl(raw, keep_blank_values=True, strict_parsing=True, encoding="utf-8",
                          errors="strict", max_num_fields=3)
    except ValueError:
        raise UploadError(400, "upload_invalid_query") from None
    fields = dict(pairs)
    allowed = {"sessionId": 256, "name": 1024, "mediaType": 255}
    if len(pairs) != len(fields) or not fields.get("sessionId") or fields.keys() - allowed.keys():
        raise UploadError(400, "upload_invalid_query")
    for key, value in fields.items():
        if len(value.encode("utf-8")) > allowed[key] or CONTROL.search(value):
            raise UploadError(400, "upload_invalid_query")
    if "mediaType" in fields and not MEDIA_TYPE.fullmatch(fields["mediaType"]):
        raise UploadError(400, "upload_invalid_query")
    return urlencode([(key, fields[key]) for key in allowed if key in fields])


def forward(target, parts, size, upstream_cookie, query=""):
    """只转发固定路由所需的请求头，并按顺序读取已核验的分片。"""
    def read_parts():
        for part in parts:
            yield part.read_bytes()

    connection = HTTPConnection("127.0.0.1", 3080, timeout=60)
    try:
        path = PATHS[target] + (f"?{query}" if query else "")
        media_type = "application/octet-stream" if target == "file-binary" else "application/json"
        connection.request("POST", path, body=read_parts(), headers={
            "Host": "dsh-app.yuyutian.top",
            "Origin": "https://dsh-app.yuyutian.top",
            "Content-Type": media_type,
            "Content-Length": str(size),
            "Cookie": upstream_cookie,
            "Connection": "close",
        })
        response = connection.getresponse()
        if not 200 <= response.status <= 599:
            raise OSError("Unexpected upstream status")
        expected = response.length
        if expected is not None and expected > RESPONSE_LIMIT:
            raise OSError("Upstream response exceeds limit")
        body = response.read(RESPONSE_LIMIT + 1)
        if len(body) > RESPONSE_LIMIT:
            raise OSError("Upstream response exceeds limit")
        if expected is not None and len(body) != expected:
            raise OSError("Incomplete upstream response")
        media_type = response.getheader("Content-Type", "").split(";", 1)[0].strip().lower()
        if media_type not in ("application/json", "text/plain"):
            media_type = "application/octet-stream"
        return Reply(response.status, body, media_type, "complete")
    finally:
        connection.close()


class Store:
    """使用同一浏览器授权隔离分片，用持久化提交意图防止重复下发。"""

    def __init__(self, root, relay=forward, clock=time.time):
        self.root = root
        self.relay = relay
        self.clock = clock
        self.lock = threading.Lock()
        self.records = {}
        self.running = set()
        self.next_cleanup = 0
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.process_lock = (root / ".lock").open("a+b")
        try:
            fcntl.flock(self.process_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            for entry in root.iterdir():
                if entry.is_dir() and UPLOAD_ID.fullmatch(entry.name):
                    self.load(entry)
            self.cleanup(self.clock())
        except BaseException:
            self.process_lock.close()
            raise

    def close(self):
        self.process_lock.close()

    def load(self, entry):
        for temporary in entry.glob(".write-*"):
            temporary.unlink()
        metadata = entry / "metadata.json"
        if not metadata.exists():
            # 分片和提交都以元数据写入成功为前提。
            shutil.rmtree(entry)
            return
        record = json.loads(metadata.read_bytes())
        self.records[entry.name] = record
        if record["state"] != "open":
            self.discard_parts(entry)

    def save(self, upload_id, record):
        persist(self.root / upload_id / "metadata.json", json.dumps(record).encode())
        self.records[upload_id] = record

    def discard_parts(self, directory):
        for part in directory.glob("*.part"):
            part.unlink(missing_ok=True)

    def cleanup(self, now):
        if now < self.next_cleanup:
            return
        self.next_cleanup = now + 10
        for upload_id, record in list(self.records.items()):
            # 在后台转发中的请求，即使超过保留期也不能被另一个请求复用。
            if upload_id in self.running:
                continue
            ttl = STAGE_TTL if record["state"] == "open" else RECEIPT_TTL
            if now - record["updated"] >= ttl:
                shutil.rmtree(self.root / upload_id)
                del self.records[upload_id]

    def open_record(self, upload_id, owner, size, now):
        active = [record for identity, record in self.records.items()
                  if record["state"] == "open" or identity in self.running]
        if (len(active) >= MAX_ACTIVE or len(self.records) >= MAX_RECORDS
                or sum(record["size"] for record in active) + size > MAX_STAGED
                or sum(record["owner"] == owner for record in active) >= MAX_OWNER_ACTIVE
                or sum(record["owner"] == owner for record in self.records.values()) >= MAX_OWNER_RECORDS):
            raise UploadError(503, "upload_capacity")
        directory = self.root / upload_id
        directory.mkdir(mode=0o700, exist_ok=True)
        sync_directory(self.root)
        record = {"owner": owner, "size": size, "state": "open", "updated": now}
        self.save(upload_id, record)
        return record

    @staticmethod
    def check_record(record, owner, size):
        if record["owner"] != owner:
            raise UploadError(403, "upload_owner")
        if record["size"] != size:
            raise UploadError(409, "upload_size_conflict")

    def put(self, upload_id, owner, size, index, body):
        with self.lock:
            now = self.clock()
            self.cleanup(now)
            record = self.records.get(upload_id)
            if record is None:
                record = self.open_record(upload_id, owner, size, now)
            self.check_record(record, owner, size)
            if record["state"] != "open":
                raise UploadError(409, "upload_already_committing")
            part = self.root / upload_id / f"{index}.part"
            if part.exists():
                if part.read_bytes() != body:
                    raise UploadError(409, "upload_part_conflict")
            else:
                persist(part, body)
            if now - record["updated"] >= 10:
                self.save(upload_id, {**record, "updated": now})
            return Reply(204)

    def commit(self, upload_id, owner, size, target, upstream_cookie, query):
        with self.lock:
            now = self.clock()
            self.cleanup(now)
            record = self.records.get(upload_id)
            if record is None:
                raise UploadError(404, "upload_not_found")
            self.check_record(record, owner, size)
            directory = self.root / upload_id
            # 查询指纹绑定会话和文件元数据，暂存目录不额外保留明文文件名。
            query_digest = sha256(query.encode("ascii")).hexdigest() if query else ""
            if record["state"] != "open" and (record["target"] != target
                                              or record.get("query_digest", "") != query_digest):
                raise UploadError(409, "upload_target_conflict")
            if record["state"] == "complete":
                return Reply(record["status"], (directory / "response.bin").read_bytes(),
                             record["content_type"], "complete")
            if upload_id in self.running:
                return Reply(202, state="pending")
            if record["state"] != "open":
                # 重启或连接状态不明时，不能假定上游尚未收到上一笔请求。
                raise UploadError(409, "upload_confirmation_unknown", "unknown")
            count = (size + PART_SIZE - 1) // PART_SIZE
            parts = tuple(directory / f"{index}.part" for index in range(count))
            for index, part in enumerate(parts):
                expected = min(PART_SIZE, size - index * PART_SIZE)
                if not part.is_file() or part.stat().st_size != expected:
                    raise UploadError(409, "upload_incomplete")
            record = {**record, "state": "sending", "target": target, "query_digest": query_digest, "updated": now}
            self.save(upload_id, record)
            self.running.add(upload_id)
            worker = threading.Thread(target=self.finish, daemon=True,
                                      args=(upload_id, record, parts, upstream_cookie, query))
            try:
                worker.start()
            except RuntimeError:
                self.running.discard(upload_id)
                raise UploadError(409, "upload_confirmation_unknown", "unknown") from None
            return Reply(202, state="pending")

    def finish(self, upload_id, record, parts, upstream_cookie, query):
        directory = self.root / upload_id
        started = time.monotonic()
        status = 0
        try:
            # 上游进入发送区间后，任何异常都保留原提交意图，防止重复发消息。
            result = self.relay(record["target"], parts, record["size"], upstream_cookie, query)
            persist(directory / "response.bin", result.body)
            with self.lock:
                self.save(upload_id, {**record, "state": "complete", "updated": self.clock(),
                                      "status": result.status, "content_type": result.content_type})
                status = result.status
        except (OSError, HTTPException, ValueError):
            pass
        finally:
            with self.lock:
                try:
                    self.discard_parts(directory)
                except OSError:
                    pass
                finally:
                    self.running.discard(upload_id)
            elapsed = round((time.monotonic() - started) * 1000)
            print(f'dsh_upload target={record["target"]} bytes={record["size"]} upstream={status} elapsed_ms={elapsed}',
                  flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    def log_message(self, _format, *_args):
        """此服务不记录请求头、地址及请求正文。"""

    def do_GET(self):
        self.handle_route("GET")

    def do_PUT(self):
        self.handle_route("PUT")

    def do_POST(self):
        self.handle_route("POST")

    def field(self, key):
        values = self.headers.get_all(key, [])
        if len(values) != 1:
            raise UploadError(400, "upload_invalid_header")
        return values[0]

    def integer(self, key):
        value = self.field(key)
        if not DECIMAL.fullmatch(value):
            raise UploadError(400, "upload_invalid_number")
        return int(value)

    def identity(self):
        cookie_headers = self.headers.get_all("Cookie", [])
        upstream_headers = self.headers.get_all("X-MyTools-Dsh-Upstream", [])
        if len(cookie_headers) != 1 or len(upstream_headers) != 1:
            raise UploadError(401, "upload_unauthorized")
        grants = [GRANT.fullmatch(item.strip()) for item in cookie_headers[0].split(";")
                  if item.strip().startswith("__Host-mytools-dsh=")]
        upstream = upstream_headers[0]
        if len(grants) != 1 or not grants[0] or len(upstream) > 4096 or not UPSTREAM_COOKIE.fullmatch(upstream):
            raise UploadError(401, "upload_unauthorized")
        return sha256(grants[0][1].encode("ascii")).hexdigest(), upstream

    def route(self, method):
        routes = {"/_mytools/upload/status": "GET", "/_mytools/upload/part": "PUT",
                  "/_mytools/upload/commit": "POST"}
        if self.path not in routes:
            raise UploadError(404, "upload_route_not_found")
        if routes[self.path] != method:
            raise UploadError(405, "upload_method")
        owner, upstream = self.identity()
        if self.headers.get_all("Transfer-Encoding"):
            raise UploadError(400, "upload_transfer_encoding")
        if method == "GET":
            if self.headers.get_all("Content-Length") not in (None, [], ["0"]):
                raise UploadError(400, "upload_unexpected_body")
            return Reply(204)
        upload_id = self.field("X-Dsh-Upload-Id")
        if not UPLOAD_ID.fullmatch(upload_id):
            raise UploadError(400, "upload_invalid_id")
        size = self.integer("X-Dsh-Upload-Size")
        if size > MAX_SIZE:
            raise UploadError(413, "upload_too_large")
        if size == 0:
            raise UploadError(400, "upload_invalid_size")
        if method == "POST":
            if self.headers.get_all("Content-Length") not in (None, [], ["0"]):
                raise UploadError(400, "upload_unexpected_body")
            target = self.field("X-Dsh-Upload-Target")
            if target not in PATHS:
                raise UploadError(400, "upload_invalid_target")
            query = ""
            if target == "file-binary":
                query = normalize_binary_query(self.field("X-Dsh-Upload-Query"))
            elif self.headers.get_all("X-Dsh-Upload-Query"):
                raise UploadError(400, "upload_unexpected_query")
            return self.server.store.commit(upload_id, owner, size, target, upstream, query)
        index = self.integer("X-Dsh-Upload-Index")
        length = self.integer("Content-Length")
        offset = index * PART_SIZE
        if offset >= size or length != min(PART_SIZE, size - offset):
            raise UploadError(400, "upload_part_length")
        body = self.rfile.read(length)
        if len(body) != length:
            raise UploadError(400, "upload_incomplete_part")
        return self.server.store.put(upload_id, owner, size, index, body)

    def handle_route(self, method):
        self.close_connection = True
        try:
            reply = self.route(method)
        except UploadError as exc:
            reply = Reply(exc.status, json.dumps({"error": exc.code}).encode(), state=exc.state)
        except OSError:
            reply = Reply(503, b'{"error":"upload_unavailable"}')
        try:
            self.send_response(reply.status)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.send_header("X-MyTools-Dsh-Upload", "1")
            if reply.state:
                self.send_header("X-Dsh-Upload-State", reply.state)
            if reply.status not in (204, 304):
                self.send_header("Content-Type", reply.content_type)
                self.send_header("Content-Length", str(len(reply.body)))
            self.end_headers()
            if reply.status not in (204, 304):
                self.wfile.write(reply.body)
        except OSError:
            pass


class LocalServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    request_queue_size = 32

    def __init__(self, store, address="", inherited_socket=None):
        self.store = store
        self.handlers = threading.BoundedSemaphore(MAX_HANDLERS)
        if inherited_socket is None:
            super().__init__(address, Handler)
        else:
            super().__init__(address, Handler, bind_and_activate=False)
            self.socket.close()
            self.socket = inherited_socket
            self.server_address = inherited_socket.getsockname()

    def process_request(self, request, client_address):
        if not self.handlers.acquire(blocking=False):
            # 拒绝超出处理容量的连接，不为慢客户端无限创建线程。
            request.setblocking(False)
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nRetry-After: 1\r\n"
                                b"Cache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except OSError:
                pass
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.handlers.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.handlers.release()

    def service_actions(self):
        with self.store.lock:
            try:
                self.store.cleanup(self.store.clock())
            except OSError:
                print("dsh_upload cleanup_error=1", flush=True)

    def handle_error(self, _request, _client_address):
        """断连或外部输入错误不向日志输出请求内容。"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--state-dir", required=True, type=Path)
    arguments = parser.parse_args()
    if os.environ.get("LISTEN_PID") != str(os.getpid()) or os.environ.get("LISTEN_FDS") != "1":
        parser.exit(2, "One systemd-activated UNIX socket is required\n")
    inherited = socket.socket(fileno=3)
    if inherited.family != socket.AF_UNIX or inherited.getsockopt(socket.SOL_SOCKET, socket.SO_ACCEPTCONN) != 1:
        parser.exit(2, "The activated socket must be a listening UNIX socket\n")
    os.umask(0o077)
    store = Store(arguments.state_dir)
    try:
        with LocalServer(store, inherited_socket=inherited) as server:
            server.serve_forever()
    finally:
        store.close()


if __name__ == "__main__":
    main()
