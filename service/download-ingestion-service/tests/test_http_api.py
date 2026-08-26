"""HTTP contract tests for the Download Ingestion service."""

from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import sys
from threading import Thread
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from mytools_download_ingestion.http_api import create_handler
from mytools_download_ingestion.migration import (InMemoryLegacyHistoryRepository,
                                                  LegacyHistoryMigrationService)
from mytools_download_ingestion.service import DownloadRequestService, InMemoryDownloadRequestRepository


class FakeScheduler:
    """Provide scheduler behavior for the HTTP boundary."""

    def __init__(self):
        self.task_id = uuid4()
        self.status = "QUEUED"

    def create_task(self, **_request):
        """Return the stable task identifier."""
        return self.task_id

    def get_task(self, _task_id):
        """Return the current task state."""
        return {"id": str(self.task_id), "status": self.status}

    def cancel_task(self, _task_id):
        """Return a cancelling task state."""
        self.status = "CANCELLING"
        return self.get_task(_task_id)


class DownloadHttpApiTest(unittest.TestCase):
    """Validate create, query, and cancellation HTTP contracts."""

    def setUp(self):
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        self.history_repository = InMemoryLegacyHistoryRepository()
        handler = create_handler(DownloadRequestService(repository, scheduler), repository, "test-token",
                                 LegacyHistoryMigrationService(self.history_repository))
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def test_request_lifecycle_contract(self):
        """A caller can create, query, and cancel one idempotent request."""
        payload = {
            "idempotencyKey": "downloadbot:event-10:0",
            "sourceType": "HTTP",
            "sourceKey": "https://example.invalid/file-10",
            "requestKind": "HTTP_ASSET",
            "parameters": {
                "itemId": "item-10",
                "url": "https://example.invalid/file-10",
                "fileName": "file.bin",
            },
        }
        created = self._request("POST", "/api/v1/download-requests", payload)
        queried = self._request("GET", f"/api/v1/download-requests/{created['id']}")
        cancelled = self._request("POST", f"/api/v1/download-requests/{created['id']}/cancel", {})

        self.assertEqual("RUNNING", created["status"])
        self.assertEqual("PLANNING", queried["status"])
        self.assertEqual("CANCELLING", cancelled["status"])
        self.assertEqual(created["task_instance_id"], queried["task_instance_id"])

    def test_owner_bound_routes_hide_other_tenant_requests(self):
        """Internal owner-bound routes return not found across tenant boundaries."""
        created = self._request("POST", "/api/v1/download-requests", {
            "ownerId": 55, "idempotencyKey": "gateway:55:event-1", "sourceType": "GATEWAY",
            "sourceKey": "55:event-1", "requestKind": "HTTP_ASSET",
            "parameters": {"url": "https://example.invalid/file", "fileName": "file.bin"}})

        owned = self._request(
            "GET", f"/internal/v1/download-requests/{created['id']}?ownerId=55")
        with self.assertRaises(HTTPError) as query_error:
            self._request("GET", f"/internal/v1/download-requests/{created['id']}?ownerId=56")
        with self.assertRaises(HTTPError) as cancel_error:
            self._request("POST", f"/internal/v1/download-requests/{created['id']}/cancel?ownerId=56", {})

        self.assertEqual(55, owned["owner_id"])
        self.assertEqual(404, query_error.exception.code)
        self.assertEqual(404, cancel_error.exception.code)

    def test_create_rejects_conflicting_owner_fields(self):
        """Top-level and legacy nested ownership must not disagree."""
        with self.assertRaises(HTTPError) as raised:
            self._request("POST", "/api/v1/download-requests", {
                "ownerId": 55, "idempotencyKey": "gateway:55:event-2", "sourceType": "GATEWAY",
                "sourceKey": "55:event-2", "requestKind": "HTTP_ASSET",
                "parameters": {"ownerId": 56, "url": "https://example.invalid/file",
                               "fileName": "file.bin"}})
        self.assertEqual(400, raised.exception.code)

    def test_rejects_missing_internal_token(self):
        """Business endpoints are closed when no internal bearer token is supplied."""
        request = Request(f"{self.base_url}/api/v1/download-requests", data=b"{}", method="POST",
                          headers={"Content-Type": "application/json"})
        with self.assertRaises(__import__("urllib.error").error.HTTPError) as raised:
            urlopen(request, timeout=2)
        self.assertEqual(401, raised.exception.code)

    def test_records_executor_result_idempotently(self):
        """The internal callback accepts a verified logical asset result."""
        created = self._request("POST", "/api/v1/download-requests", {
            "idempotencyKey": "http:result-1", "sourceType": "HTTP", "sourceKey": "result-1",
            "requestKind": "HTTP_ASSET", "parameters": {"itemId": "item-1", "url":
            "https://example.invalid/file", "fileName": "file.bin"}})
        payload = {"itemId": "item-1", "fileName": "file.bin", "contentSha256": "a" * 64,
                   "sizeBytes": 3, "storageUri": "download://executor/r/file.bin",
                   "assetId": str(uuid4())}
        path = f"/internal/v1/download-requests/{created['id']}/result"
        self.assertEqual(payload, self._request("POST", path, payload))
        self.assertEqual(payload, self._request("POST", path, payload))
        summary = self._request("GET", f"/api/v1/download-requests/{created['id']}/result-summary")
        self.assertEqual(created["id"], summary["downloadRequestId"])
        self.assertEqual(1, summary["itemCount"])
        self.assertEqual(3, summary["totalBytes"])
        self.assertEqual(64, len(summary["collectionSha256"]))
        self.assertEqual(64, len(summary["contentSetSha256"]))
        self.assertEqual([{**payload, "tagStatus": "PENDING", "tags": []}], summary["items"])
        self.assertNotIn("url", summary)

        tags = {"itemId": "item-1", "tagStatus": "TAGGED",
                "tags": [{"name": "cosplay", "type": "topic", "confidence": 0.98}]}
        tag_path = f"/internal/v1/download-requests/{created['id']}/tags"
        self.assertEqual(tags, self._request("POST", tag_path, tags))
        tagged = self._request("GET", f"/api/v1/download-requests/{created['id']}/result-summary")
        self.assertEqual("TAGGED", tagged["items"][0]["tagStatus"])

    def test_imports_sanitized_downloadbot_history_batch(self):
        """The protected migration endpoint supports dry-run and apply modes."""
        import hashlib
        payload = {"legacyJobId": "9", "uriSha256": "a" * 64, "requestKind": "HTTP",
                   "strategy": "DIRECT", "sourceType": "MESSAGE", "sourceKey": "message-9",
                   "status": "COMPLETED", "expectedFiles": 1,
                   "createdAt": None, "completedAt": None}
        digest = hashlib.sha256(json.dumps(payload, sort_keys=True,
                                           separators=(",", ":")).encode()).hexdigest()
        item = {"itemType": "LINK_JOB", "legacyId": "9", "sourceKey": "link:9",
                "payload": payload, "payloadSha256": digest}
        path = "/internal/v1/migrations/downloadbot-history/batches"
        dry_run = self._request("POST", path, {"migrationKey": "download-v1",
                                                "sourceSystem": "DownloadBot",
                                                "dryRun": True, "items": [item]})
        applied = self._request("POST", path, {"migrationKey": "download-v1",
                                                "sourceSystem": "DownloadBot",
                                                "dryRun": False, "items": [item]})
        self.assertEqual(1, dry_run["accepted"])
        self.assertEqual(1, applied["accepted"])

    def _request(self, method, path, payload=None):
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        request = Request(
            f"{self.base_url}{path}", data=body, method=method,
            headers={"Content-Type": "application/json"},
        )
        request.add_header("Authorization", "Bearer test-token")
        with urlopen(request, timeout=2) as response:
            return json.loads(response.read().decode("utf-8"))
