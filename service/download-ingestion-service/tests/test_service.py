"""Domain tests for download request orchestration."""

from pathlib import Path
import sys
import unittest
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from mytools_download_ingestion.models import CreateDownloadRequest, DownloadStatus
from mytools_download_ingestion.service import DownloadRequestService, InMemoryDownloadRequestRepository


class FakeScheduler:
    """Capture scheduler task creation calls."""

    def __init__(self):
        self.calls = []
        self.task_id = uuid4()
        self.failures_remaining = 0
        self.status = "QUEUED"

    def create_task(self, **request):
        """Return a stable task ID for idempotency tests."""
        self.calls.append(request)
        if self.failures_remaining > 0:
            self.failures_remaining -= 1
            raise RuntimeError("scheduler unavailable")
        return self.task_id

    def get_task(self, _task_id):
        """Return the configured scheduler state."""
        return {"id": str(self.task_id), "status": self.status}

    def cancel_task(self, _task_id):
        """Move the fake task into cancellation."""
        self.status = "CANCELLING"
        return self.get_task(_task_id)


class DownloadRequestServiceTest(unittest.TestCase):
    """Validate request-to-task orchestration invariants."""

    def test_creates_one_parent_task_for_replayed_request(self):
        """A replay should return the same aggregate without a second task."""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        service = DownloadRequestService(repository, scheduler)
        command = CreateDownloadRequest(
            idempotency_key="http:event-42:0",
            source_type="HTTP",
            source_key="https://example.invalid/file",
            request_kind="HTTP_ASSET",
            parameters={"mediaIndex": 0},
        )

        first = service.create(command)
        second = service.create(command)

        self.assertEqual(first.id, second.id)
        self.assertEqual(DownloadStatus.RUNNING, first.status)
        self.assertEqual(1, len(scheduler.calls))
        self.assertEqual("download_http_asset", scheduler.calls[0]["task_name"])

    def test_rejects_task_kind_without_registered_scheduler_definition(self):
        """Planned download kinds stay closed until their executable package is registered."""
        service = DownloadRequestService(InMemoryDownloadRequestRepository(), FakeScheduler())
        command = CreateDownloadRequest("message:key", "MESSAGE", "attachment-1",
                                        "MESSAGE_ATTACHMENT", {"mediaIndex": 0})
        with self.assertRaisesRegex(ValueError, "unsupported download request kind"):
            service.create(command)

    def test_routes_managed_local_import_to_storage_task(self):
        """A local import is represented by a managed URI and a storage copy task."""
        scheduler = FakeScheduler()
        service = DownloadRequestService(InMemoryDownloadRequestRepository(), scheduler)
        command = CreateDownloadRequest(
            "local:object-1", "LOCAL_IMPORT", "storage://legacy/object-1", "LOCAL_IMPORT",
            {"itemId": "item-1", "sourceStorageUri": "storage://legacy/object-1",
             "fileName": "object.bin"})
        created = service.create(command)
        self.assertEqual("download_storage_object", scheduler.calls[0]["task_name"])
        self.assertEqual(str(created.id), scheduler.calls[0]["parameters"]["downloadRequestId"])

    def test_retries_scheduler_binding_for_an_accepted_request(self):
        """A transient scheduler failure must not strand the accepted aggregate."""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        scheduler.failures_remaining = 1
        service = DownloadRequestService(repository, scheduler)
        command = CreateDownloadRequest(
            idempotency_key="http:event-7:0",
            source_type="HTTP",
            source_key="https://example.invalid/file",
            request_kind="HTTP_ASSET",
            parameters={"itemId": "item-7", "url": "https://example.invalid/file", "fileName": "file.bin"},
        )

        with self.assertRaises(RuntimeError):
            service.create(command)
        recovered = service.create(command)

        self.assertEqual(DownloadStatus.RUNNING, recovered.status)
        self.assertEqual(2, len(scheduler.calls))
        self.assertEqual(str(recovered.id), scheduler.calls[1]["parameters"]["downloadRequestId"])

    def test_rejects_reused_idempotency_key_with_different_payload(self):
        """An idempotency key cannot silently alias another download target."""
        repository = InMemoryDownloadRequestRepository()
        service = DownloadRequestService(repository, FakeScheduler())
        first = CreateDownloadRequest("http:key", "HTTP", "source", "HTTP_ASSET",
                                      {"url": "https://example.invalid/a", "fileName": "a"})
        second = CreateDownloadRequest("http:key", "HTTP", "source", "HTTP_ASSET",
                                       {"url": "https://example.invalid/b", "fileName": "b"})
        service.create(first)
        with self.assertRaisesRegex(ValueError, "idempotency conflict"):
            service.create(second)

    def test_reconciles_bound_scheduler_task(self):
        """Query must mirror scheduler state into the aggregate."""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        service = DownloadRequestService(repository, scheduler)
        command = CreateDownloadRequest(
            idempotency_key="http:event-8:0",
            source_type="HTTP",
            source_key="https://example.invalid/file-8",
            request_kind="HTTP_ASSET",
            parameters={"itemId": "item-8", "url": "https://example.invalid/file-8", "fileName": "file.bin"},
        )
        created = service.create(command)
        scheduler.status = "SUCCEEDED"

        reconciled = service.get(created.id)
        self.assertEqual(DownloadStatus.SUCCEEDED, reconciled.status)

    def test_cancels_bound_scheduler_task(self):
        """Cancellation must mirror the scheduler cancellation state."""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        service = DownloadRequestService(repository, scheduler)
        command = CreateDownloadRequest(
            idempotency_key="http:event-9:0",
            source_type="HTTP",
            source_key="https://example.invalid/file-9",
            request_kind="HTTP_ASSET",
            parameters={"itemId": "item-9", "url": "https://example.invalid/file-9", "fileName": "file.bin"},
        )
        created = service.create(command)

        cancelled = service.cancel(created.id)
        self.assertEqual(DownloadStatus.CANCELLING, cancelled.status)

    def test_does_not_regress_cancelling_request_to_running(self):
        """A delayed scheduler RUNNING snapshot cannot undo cancellation intent."""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        service = DownloadRequestService(repository, scheduler)
        command = CreateDownloadRequest("http:event-11", "HTTP", "source-11", "HTTP_ASSET",
                                        {"itemId": "11", "url": "https://example.invalid/11",
                                         "fileName": "11.bin"})
        created = service.create(command)
        repository.update_status(created.id, DownloadStatus.CANCELLING)
        scheduler.status = "RUNNING"

        self.assertEqual(DownloadStatus.CANCELLING, service.get(created.id).status)

    def test_result_summary_is_deterministic_and_excludes_request_parameters(self):
        """Reconciliation returns ordered digests without source URLs or credentials."""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        service = DownloadRequestService(repository, scheduler)
        created = service.create(CreateDownloadRequest(
            "http:event-12", "HTTP", "source-12", "HTTP_ASSET",
            {"itemId": "b", "url": "https://example.invalid/private", "fileName": "b.bin"},
        ))
        for item_id, size in (("b", 5), ("a", 3)):
            repository.record_result(created.id, {
                "itemId": item_id, "fileName": f"{item_id}.bin",
                "contentSha256": item_id * 64, "sizeBytes": size,
                "storageUri": f"download://executor/{item_id}.bin", "assetId": str(uuid4()),
            })

        summary = service.result_summary(created.id)

        self.assertEqual(2, summary["itemCount"])
        self.assertEqual(8, summary["totalBytes"])
        self.assertEqual(64, len(summary["collectionSha256"]))
        self.assertEqual(64, len(summary["contentSetSha256"]))
        self.assertEqual(["a", "b"], [item["itemId"] for item in summary["items"]])
        self.assertNotIn("parameters", summary)
        self.assertNotIn("https://example.invalid/private", str(summary))


if __name__ == "__main__":
    unittest.main()
