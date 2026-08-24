"""Tests for post-deployment service acceptance checks."""

from __future__ import annotations

import importlib.util
import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("verify_deployment.py")
SPEC = importlib.util.spec_from_file_location("verify_deployment", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = verifier
SPEC.loader.exec_module(verifier)


class JsonHandler(BaseHTTPRequestHandler):
    """Serve configurable JSON responses without request logging."""

    routes: dict[str, tuple[int, object]] = {}

    def do_GET(self) -> None:  # noqa: N802
        """Return the configured response for the request path."""

        status, payload = self.routes.get(self.path, (404, {"code": "missing"}))
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        """Suppress HTTP server output in unit tests."""


class VerifyDeploymentTest(unittest.TestCase):
    """Verify strict health, topology, and default-off behavior."""

    @classmethod
    def setUpClass(cls) -> None:
        """Start one local JSON test server."""

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), JsonHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls) -> None:
        """Stop the local JSON test server."""

        cls.server.shutdown()
        cls.server.server_close()

    def test_accepts_explicit_up_health(self) -> None:
        service = {"name": "sample", "port": self.server.server_port, "runtime": "python"}
        JsonHandler.routes = {"/health": (200, {"status": "UP"})}

        self.assertEqual([], verifier.wait_for_services("127.0.0.1", [service], 0.2, 0.2))

    def test_requires_online_executor(self) -> None:
        JsonHandler.routes = {
            "/api/v1/execution-topology/nodes": (
                200, [{"name": "executor-a", "enabled": True, "status": "ONLINE"}]
            )
        }

        nodes = verifier.verify_executor("127.0.0.1", self.server.server_port, 0.2)

        self.assertEqual("executor-a", nodes[0]["name"])

    def test_accepts_gateway_disabled_error(self) -> None:
        JsonHandler.routes = {
            "/api/app/v1/catalog": (503, {"code": "GATEWAY_002", "message": "disabled"})
        }

        verifier.verify_gateway_default_off("127.0.0.1", self.server.server_port, 0.2)

    def test_rejects_gateway_route_that_is_enabled(self) -> None:
        JsonHandler.routes = {"/api/app/v1/catalog": (200, [])}

        with self.assertRaisesRegex(ValueError, "default-off"):
            verifier.verify_gateway_default_off("127.0.0.1", self.server.server_port, 0.2)


if __name__ == "__main__":
    unittest.main()
