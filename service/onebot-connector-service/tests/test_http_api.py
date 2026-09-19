from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
import json
from threading import Thread

from mytools_onebot_connector.http_api import create_handler


class FakeService:
    def register(self, payload):
        return {"id": "account-id", "externalKey": payload["externalKey"], "enabled": False}

    def resolve(self, _payload):
        return {"mode": "STREAM"}

    def prepare_content(self, _payload):
        return object()

    def stream_content(self, _source, output):
        output.write(b"provider-content")
        return 16

    def request_relogin(self, payload):
        return {"requestId": payload["requestId"], "status": "REQUESTED"}

    def prepare_qr(self, _payload):
        return object(), 8

    def stream_qr(self, _source, output):
        output.write(b"png-data")
        return 8

    def send_text(self, _payload):
        return {"status": "SENT"}

    def expand_forward(self, _payload):
        return {"messages": []}


def request(server, path, token, payload):
    connection = HTTPConnection("127.0.0.1", server.server_port)
    connection.request("POST", path, json.dumps(payload), {"Authorization": f"Bearer {token}"})
    response = connection.getresponse()
    body = response.read()
    content_type = response.getheader("Content-Type")
    status = response.status
    connection.close()
    return status, content_type, body


def get_request(server, path):
    connection = HTTPConnection("127.0.0.1", server.server_port)
    connection.request("GET", path)
    response = connection.getresponse()
    body = response.read()
    status = response.status
    connection.close()
    return status, json.loads(body)


def chunked_request(server, path, token, payload):
    connection = HTTPConnection("127.0.0.1", server.server_port)
    body = json.dumps(payload).encode()
    connection.request("POST", path, [body],
                       {"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                       encode_chunked=True)
    response = connection.getresponse()
    content = response.read()
    status = response.status
    connection.close()
    return status, content


def start_server(health_provider=None):
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0),
        create_handler(FakeService(), "internal", "admin", health_provider))
    Thread(target=server.serve_forever, daemon=True).start()
    return server


def test_internal_and_admin_tokens_are_not_interchangeable():
    server = start_server()
    try:
        assert request(server, "/internal/v1/accounts", "internal", {"externalKey": "a"})[0] == 401
        assert request(server, "/internal/v1/accounts", "admin", {"externalKey": "a"})[0] == 201
        assert request(server, "/internal/v1/provider-files/resolve", "admin", {})[0] == 401
        assert request(server, "/internal/v1/provider-files/resolve", "internal", {})[0] == 200
    finally:
        server.shutdown()
        server.server_close()


def test_content_endpoint_streams_binary_without_provider_credentials():
    server = start_server()
    try:
        status, content_type, body = request(server, "/internal/v1/provider-files/content", "internal", {})
        assert status == 200
        assert content_type == "application/octet-stream"
        assert body == b"provider-content"
    finally:
        server.shutdown()
        server.server_close()


def test_relogin_and_qr_endpoints_require_internal_token():
    server = start_server()
    try:
        payload = {"accountKey": "qq_primary", "requestId": "request_1"}
        assert request(server, "/internal/v1/control/relogin", "admin", payload)[0] == 401
        assert request(server, "/internal/v1/control/relogin", "internal", payload)[0] == 202
        status, content_type, body = request(
            server, "/internal/v1/control/login-qr/content", "internal",
            {"accountKey": "qq_primary", "requestedAt": "2026-08-25T10:00:00+00:00"})
        assert status == 200
        assert content_type == "image/png"
        assert body == b"png-data"
    finally:
        server.shutdown()
        server.server_close()


def test_message_routes_require_internal_token():
    server = start_server()
    try:
        assert request(server, "/internal/v1/messages/text", "admin", {})[0] == 401
        assert request(server, "/internal/v1/messages/text", "internal", {})[0] == 200
        assert request(server, "/internal/v1/messages/forward/expand", "internal", {})[0] == 200
    finally:
        server.shutdown()
        server.server_close()


def test_text_message_route_accepts_bounded_chunked_json():
    server = start_server()
    try:
        status, body = chunked_request(server, "/internal/v1/messages/text", "internal", {})
        assert status == 200
        assert json.loads(body) == {"status": "SENT"}
    finally:
        server.shutdown()
        server.server_close()


def test_liveness_stays_up_while_readiness_is_down():
    server = start_server(lambda: {
        "status": "DOWN",
        "consumerConnected": False,
        "walDeadEvents": 1,
        "rejectedInboundEvents": 2,
    })
    try:
        assert get_request(server, "/health/live") == (200, {"status": "UP"})
        assert get_request(server, "/health") == (503, {
            "status": "DOWN",
            "consumerConnected": False,
            "walDeadEvents": 1,
            "rejectedInboundEvents": 2,
        })
    finally:
        server.shutdown()
        server.server_close()


def test_readiness_filters_unknown_fields_and_hides_probe_errors():
    server = start_server(lambda: {
        "status": "UP",
        "inboundEnabled": True,
        "payload": {"secret": "must-not-leak"},
    })
    try:
        assert get_request(server, "/health") == (200, {
            "status": "UP",
            "inboundEnabled": True,
        })
    finally:
        server.shutdown()
        server.server_close()

    def failed_probe():
        raise RuntimeError("secret provider response")

    server = start_server(failed_probe)
    try:
        status, response = get_request(server, "/health")
        assert status == 503
        assert response == {
            "status": "DOWN",
            "healthProbeReady": False,
            "healthProbeErrorCode": "RuntimeError",
        }
        assert "secret" not in json.dumps(response)
    finally:
        server.shutdown()
        server.server_close()
