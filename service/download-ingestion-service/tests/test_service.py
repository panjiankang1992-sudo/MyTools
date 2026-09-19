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
        self.get_calls = 0
        self.cancel_calls = 0

    def create_task(self, **request):
        """Return a stable task ID for idempotency tests."""
        self.calls.append(request)
        if self.failures_remaining > 0:
            self.failures_remaining -= 1
            raise RuntimeError("scheduler unavailable")
        return self.task_id

    def get_task(self, _task_id):
        """Return the configured scheduler state."""
        self.get_calls += 1
        return {"id": str(self.task_id), "status": self.status}

    def cancel_task(self, _task_id):
        """Move the fake task into cancellation."""
        self.cancel_calls += 1
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
            parameters={"itemId": "item-42", "url": "https://example.invalid/file",
                        "fileName": "file.bin", "mediaIndex": 0},
        )

        first = service.create(command)
        second = service.create(command)

        self.assertEqual(first.id, second.id)
        self.assertEqual(DownloadStatus.RUNNING, first.status)
        self.assertEqual(1, len(scheduler.calls))
        self.assertEqual("download_http_asset", scheduler.calls[0]["task_name"])

    def test_rejects_incomplete_http_request_before_scheduling(self):
        """缺少原子任务必需字段的 HTTP 请求不能进入永久失败队列。"""
        with self.assertRaisesRegex(ValueError, "HTTP asset parameters are incomplete"):
            CreateDownloadRequest(
                idempotency_key="http-incomplete",
                source_type="GATEWAY_HTTP",
                source_key="incomplete",
                request_kind="HTTP_ASSET",
                parameters={"url": "https://example.invalid/file", "fileName": "file.bin"},
            )

    def test_routes_message_attachment_to_controlled_stream_task(self):
        """Provider attachment content is fetched by opaque job id instead of a signed URL."""
        scheduler = FakeScheduler()
        service = DownloadRequestService(InMemoryDownloadRequestRepository(), scheduler)
        command = CreateDownloadRequest("message:key", "MESSAGE", "attachment-1",
                                        "MESSAGE_ATTACHMENT", {"attachmentJobId": "job-1"})
        service.create(command)
        self.assertEqual("download_message_attachment", scheduler.calls[0]["task_name"])

    def test_routes_managed_local_import_to_storage_task(self):
        """A local import is represented by a managed URI and a storage copy task."""
        scheduler = FakeScheduler()
        service = DownloadRequestService(InMemoryDownloadRequestRepository(), scheduler)
        command = CreateDownloadRequest(
            "local:object-1", "LOCAL_IMPORT", "storage://legacy/object-1", "LOCAL_IMPORT",
            {"itemId": "item-1", "sourceStorageUri": "storage://legacy/object-1",
             "fileName": "object.bin"})
        created = service.create(command)
        self.assertEqual("download_local_import", scheduler.calls[0]["task_name"])
        self.assertEqual(str(created.id), scheduler.calls[0]["parameters"]["downloadRequestId"])

    def test_owner_is_persisted_and_overrides_untrusted_scheduler_parameter(self):
        """The aggregate owner is authoritative for every task parameter."""
        scheduler = FakeScheduler()
        service = DownloadRequestService(InMemoryDownloadRequestRepository(), scheduler)
        command = CreateDownloadRequest(
            "http:owner-7", "HTTP", "owner-7", "HTTP_ASSET",
            {"itemId": "item-7", "url": "https://example.invalid/7",
             "fileName": "7.bin", "ownerId": 99},
            owner_id=7)

        created = service.create(command)

        self.assertEqual(7, created.owner_id)
        self.assertEqual(7, scheduler.calls[0]["parameters"]["ownerId"])

    def test_owner_bound_query_and_cancel_fail_before_scheduler_access(self):
        """A mismatched owner cannot observe or cancel another tenant task."""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        service = DownloadRequestService(repository, scheduler)
        created = service.create(CreateDownloadRequest(
            "http:owner-8", "HTTP", "owner-8", "HTTP_ASSET",
            {"itemId": "item-8", "url": "https://example.invalid/8",
             "fileName": "8.bin"}, owner_id=8))

        self.assertIsNone(service.get_for_owner(created.id, 9))
        self.assertIsNone(service.cancel_for_owner(created.id, 9))
        self.assertEqual(0, scheduler.get_calls)
        self.assertEqual(0, scheduler.cancel_calls)
        self.assertEqual(created.id, service.get_for_owner(created.id, 8).id)
        self.assertEqual(1, scheduler.get_calls)

    def test_routes_x_post_to_child_task_orchestrator(self):
        """An X request binds to the resolver parent instead of duplicating HTTP download logic."""
        scheduler = FakeScheduler()
        service = DownloadRequestService(InMemoryDownloadRequestRepository(), scheduler)
        created = service.create(CreateDownloadRequest(
            "x:123", "X", "123", "X_POST", {"url": "https://x.com/user/status/123"}))
        self.assertEqual("download_x_post", scheduler.calls[0]["task_name"])
        self.assertEqual(str(created.id), scheduler.calls[0]["parameters"]["downloadRequestId"])

    def test_routes_x_user_to_profile_orchestrator(self):
        """An X user page binds to the profile enumerator parent task."""
        scheduler = FakeScheduler()
        service = DownloadRequestService(InMemoryDownloadRequestRepository(), scheduler)
        created = service.create(CreateDownloadRequest(
            "x-user:example", "X", "example", "X_USER", {"url": "https://x.com/example/media"}))
        self.assertEqual("download_x_user", scheduler.calls[0]["task_name"])
        self.assertEqual(str(created.id), scheduler.calls[0]["parameters"]["downloadRequestId"])

    def test_routes_message_url_batch_to_message_orchestrator(self):
        """同一消息的多个链接必须先进入整批解析父任务。"""
        scheduler = FakeScheduler()
        service = DownloadRequestService(InMemoryDownloadRequestRepository(), scheduler)
        created = service.create(CreateDownloadRequest(
            "message:batch-1", "MESSAGE", "batch-1", "MESSAGE_URL_BATCH",
            {"messageBatchId": "batch-1", "receivedAt": "2026-08-26T07:53:08Z",
             "items": [{"url": "https://x.com/user/status/123", "fileName": "123"},
                       {"url": "https://example.invalid/a.jpg", "fileName": "a.jpg"}]},
            owner_id=7))
        self.assertEqual("download_message_url_batch", scheduler.calls[0]["task_name"])
        self.assertEqual(str(created.id), scheduler.calls[0]["parameters"]["downloadRequestId"])

    def test_routes_web_archive_to_resource_orchestrator(self):
        """A web archive request binds to the public-page resolver parent."""
        scheduler = FakeScheduler()
        service = DownloadRequestService(InMemoryDownloadRequestRepository(), scheduler)
        created = service.create(CreateDownloadRequest(
            "web:page-1", "WEB", "page-1", "WEB_ARCHIVE",
            {"url": "https://example.invalid/page"}))
        self.assertEqual("download_web_archive", scheduler.calls[0]["task_name"])
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
                                      {"itemId": "a", "url": "https://example.invalid/a", "fileName": "a"})
        second = CreateDownloadRequest("http:key", "HTTP", "source", "HTTP_ASSET",
                                       {"itemId": "b", "url": "https://example.invalid/b", "fileName": "b"})
        service.create(first)
        with self.assertRaisesRegex(ValueError, "idempotency conflict"):
            service.create(second)

    def test_rejects_reused_idempotency_key_for_another_owner(self):
        """Global idempotency cannot silently alias another tenant."""
        service = DownloadRequestService(InMemoryDownloadRequestRepository(), FakeScheduler())
        service.create(CreateDownloadRequest(
            "shared:key", "HTTP", "source", "HTTP_ASSET",
            {"itemId": "a", "url": "https://example.invalid/a", "fileName": "a"}, owner_id=7))

        with self.assertRaisesRegex(ValueError, "idempotency conflict"):
            service.create(CreateDownloadRequest(
                "shared:key", "HTTP", "source", "HTTP_ASSET",
                {"itemId": "a", "url": "https://example.invalid/a", "fileName": "a"}, owner_id=8))

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

    def test_success_terminalizes_pending_tags_before_returning_summary(self):
        """Scheduler 成功后必须立即把遗留 PENDING 标签收敛为失败终态。"""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        service = DownloadRequestService(repository, scheduler)
        created = service.create(CreateDownloadRequest(
            "http:terminal-tags", "HTTP", "terminal-tags", "HTTP_ASSET",
            {"itemId": "item", "url": "https://example.invalid/item", "fileName": "item.jpg"}))
        service.record_result(created.id, {
            "itemId": "item", "fileName": "item.jpg", "contentSha256": "a" * 64,
            "sizeBytes": 3, "storageUri": "storage://downloads/item.jpg", "assetId": str(uuid4())})
        scheduler.status = "SUCCEEDED"

        summary = service.result_summary(created.id)

        self.assertEqual("SUCCEEDED", summary["status"])
        self.assertEqual("FAILED", summary["items"][0]["tagStatus"])
        self.assertEqual([], summary["items"][0]["tags"])

    def test_every_scheduler_terminal_state_terminalizes_partial_result_tags(self):
        """Scheduler 任一终态都必须把已下载条目的 PENDING 标签同步封口。"""
        cases = {
            "FAILED": "FAILED",
            "TIMED_OUT": "FAILED",
            "CANCELLED": "CANCELLED",
        }
        for scheduler_status, expected_status in cases.items():
            with self.subTest(scheduler_status=scheduler_status):
                repository = InMemoryDownloadRequestRepository()
                scheduler = FakeScheduler()
                service = DownloadRequestService(repository, scheduler)
                created = service.create(CreateDownloadRequest(
                    f"http:terminal-{scheduler_status.lower()}", "HTTP", scheduler_status,
                    "HTTP_ASSET", {"itemId": "partial", "url": "https://example.invalid/partial",
                                   "fileName": "partial.jpg"}))
                service.record_result(created.id, {
                    "itemId": "partial", "fileName": "partial.jpg", "contentSha256": "c" * 64,
                    "sizeBytes": 5, "storageUri": "storage://downloads/partial.jpg",
                    "assetId": str(uuid4())})
                scheduler.status = scheduler_status

                summary = service.result_summary(created.id)

                self.assertEqual(expected_status, summary["status"])
                self.assertEqual("FAILED", summary["items"][0]["tagStatus"])
                self.assertEqual([], summary["items"][0]["tags"])

    def test_success_repairs_legacy_succeeded_request_and_preserves_real_tags(self):
        """历史成功聚合也应自愈，同时不得覆盖已经写入的真实标签。"""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        service = DownloadRequestService(repository, scheduler)
        created = service.create(CreateDownloadRequest(
            "http:legacy-tags", "HTTP", "legacy-tags", "HTTP_ASSET",
            {"itemId": "pending", "url": "https://example.invalid/item", "fileName": "item.jpg"}))
        for item_id, digest_character in (("pending", "a"), ("tagged", "b")):
            service.record_result(created.id, {
                "itemId": item_id, "fileName": f"{item_id}.jpg",
                "contentSha256": digest_character * 64,
                "sizeBytes": 3, "storageUri": f"storage://downloads/{item_id}.jpg",
                "assetId": str(uuid4())})
        tagged = {"itemId": "tagged", "tagStatus": "TAGGED",
                  "tags": [{"name": "photo", "type": "topic", "confidence": 0.9}]}
        service.record_tags(created.id, tagged)
        repository.update_status(created.id, DownloadStatus.SUCCEEDED)
        scheduler.status = "SUCCEEDED"

        first = service.result_summary(created.id)
        second = service.result_summary(created.id)

        statuses = {item["itemId"]: item["tagStatus"] for item in first["items"]}
        self.assertEqual({"pending": "FAILED", "tagged": "TAGGED"}, statuses)
        self.assertEqual(first, second)
        self.assertEqual(0, scheduler.get_calls)

    def test_late_result_after_every_terminal_state_has_terminal_tags(self):
        """任一终态封口后的迟到结果都不得重新制造 PENDING 标签。"""
        for scheduler_status in ("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"):
            with self.subTest(scheduler_status=scheduler_status):
                repository = InMemoryDownloadRequestRepository()
                scheduler = FakeScheduler()
                service = DownloadRequestService(repository, scheduler)
                created = service.create(CreateDownloadRequest(
                    f"http:late-{scheduler_status.lower()}", "HTTP", scheduler_status, "HTTP_ASSET",
                    {"itemId": "late", "url": "https://example.invalid/late",
                     "fileName": "late.jpg"}))
                scheduler.status = scheduler_status
                self.assertIn(service.get(created.id).status, {
                    DownloadStatus.SUCCEEDED, DownloadStatus.FAILED, DownloadStatus.CANCELLED})

                service.record_result(created.id, {
                    "itemId": "late", "fileName": "late.jpg", "contentSha256": "b" * 64,
                    "sizeBytes": 4, "storageUri": "storage://downloads/late.jpg",
                    "assetId": str(uuid4())})

                summary = service.result_summary(created.id)
                self.assertEqual("FAILED", summary["items"][0]["tagStatus"])

    def test_late_cancelling_update_cannot_regress_terminal_status(self):
        """取消线程的迟到写入不得覆盖另一线程已经完成的终态封口。"""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        service = DownloadRequestService(repository, scheduler)
        created = service.create(CreateDownloadRequest(
            "http:terminal-race", "HTTP", "terminal-race", "HTTP_ASSET",
            {"itemId": "item", "url": "https://example.invalid/item", "fileName": "item.jpg"}))
        service.record_result(created.id, {
            "itemId": "item", "fileName": "item.jpg", "contentSha256": "d" * 64,
            "sizeBytes": 6, "storageUri": "storage://downloads/item.jpg", "assetId": str(uuid4())})

        repository.complete_terminal(created.id, DownloadStatus.SUCCEEDED)
        after_late_cancel = repository.update_status(created.id, DownloadStatus.CANCELLING)

        self.assertEqual(DownloadStatus.SUCCEEDED, after_late_cancel.status)
        self.assertEqual("FAILED", repository.list_results(created.id)[0]["tagStatus"])

    def test_late_replayed_task_binding_cannot_regress_terminal_status(self):
        """并发创建请求的迟到任务绑定不得把已完成终态回退为运行中。"""
        repository = InMemoryDownloadRequestRepository()
        scheduler = FakeScheduler()
        service = DownloadRequestService(repository, scheduler)
        created = service.create(CreateDownloadRequest(
            "http:late-bind", "HTTP", "late-bind", "HTTP_ASSET",
            {"itemId": "item", "url": "https://example.invalid/item", "fileName": "item.jpg"}))
        completed = repository.complete_terminal(created.id, DownloadStatus.SUCCEEDED)

        rebound = repository.bind_task(created.id, completed.task_instance_id)

        self.assertEqual(DownloadStatus.SUCCEEDED, rebound.status)
        with self.assertRaisesRegex(ValueError, "task binding conflict"):
            repository.bind_task(created.id, uuid4())

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

    def test_records_terminal_tags_and_rejects_conflicting_replay(self):
        """Tag callbacks are bounded, terminal, and idempotent."""
        repository = InMemoryDownloadRequestRepository()
        service = DownloadRequestService(repository, FakeScheduler())
        created = service.create(CreateDownloadRequest(
            "http:tagged", "HTTP", "tagged", "HTTP_ASSET",
            {"itemId": "item", "url": "https://example.invalid/a", "fileName": "a.jpg"}))
        service.record_result(created.id, {
            "itemId": "item", "fileName": "a.jpg", "contentSha256": "a" * 64,
            "sizeBytes": 3, "storageUri": "storage://downloads/a.jpg", "assetId": str(uuid4())})
        result = {"itemId": "item", "tagStatus": "TAGGED",
                  "tags": [{"name": "cosplay", "type": "topic", "confidence": 0.98}]}

        self.assertEqual(result, service.record_tags(created.id, result))
        self.assertEqual(result, service.record_tags(created.id, result))
        self.assertEqual("TAGGED", service.result_summary(created.id)["items"][0]["tagStatus"])
        with self.assertRaisesRegex(ValueError, "idempotency conflict"):
            service.record_tags(created.id, {"itemId": "item", "tagStatus": "SKIPPED", "tags": []})


if __name__ == "__main__":
    unittest.main()
