"""MySQL 下载仓储事务语义测试。"""

from datetime import UTC, datetime
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from mytools_download_ingestion.models import DownloadRequest, DownloadStatus
from mytools_download_ingestion.mysql_repository import MySqlDownloadRequestRepository


class MySqlDownloadRequestRepositoryTest(unittest.TestCase):
    """验证终态封口与迟到结果使用一致的请求行锁。"""

    def test_unfinished_app_ids_use_owner_source_and_active_statuses(self):
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        request_id = str(uuid4())
        cursor.fetchall.return_value = [{"id": request_id}]
        repository = MySqlDownloadRequestRepository(lambda: connection)

        self.assertEqual(repository.unfinished_app_request_ids(42), [request_id])
        sql, params = cursor.execute.call_args.args
        self.assertIn("SELECT id FROM download_request", sql)
        self.assertIn("owner_id = %s AND source_type = %s AND status IN", sql)
        self.assertEqual(params, (42, "APP_TOOL", "ACCEPTED", "PLANNING", "RUNNING", "CANCELLING"))
        connection.close.assert_called_once()

    def test_complete_terminal_seals_tags_with_every_scheduler_terminal_state(self):
        """成功、失败、超时映射和取消状态都必须与标签封口在一次提交中完成。"""
        for status in (DownloadStatus.SUCCEEDED, DownloadStatus.FAILED, DownloadStatus.CANCELLED):
            with self.subTest(status=status):
                connection = MagicMock()
                cursor = connection.cursor.return_value.__enter__.return_value
                cursor.fetchone.return_value = {"status": "RUNNING"}
                request_id = uuid4()
                expected = self.request(request_id, status)
                repository = MySqlDownloadRequestRepository(lambda: connection)

                with patch.object(repository, "find_by_id", return_value=expected):
                    actual = repository.complete_terminal(request_id, status)

                self.assertEqual(expected, actual)
                self.assertEqual(1, connection.commit.call_count)
                connection.rollback.assert_not_called()
                statements = [call.args[0] for call in cursor.execute.call_args_list]
                self.assertIn("SELECT status FROM download_request WHERE id = %s FOR UPDATE", statements[0])
                self.assertTrue(any("status = 'COMPLETED' AND tag_status = %s" in sql
                                    for sql in statements))
                status_update = next(call for call in cursor.execute.call_args_list
                                     if "UPDATE download_request SET status = %s" in call.args[0])
                self.assertEqual(status.value, status_update.args[1][0])

    def test_late_result_is_inserted_with_failed_tag_status(self):
        """已经成功的请求收到迟到结果时不得重新写入 PENDING。"""
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.side_effect = [{"status": "SUCCEEDED"}, None]
        repository = MySqlDownloadRequestRepository(lambda: connection)
        request_id = uuid4()
        result = {
            "sourceIndex": 0,
            "itemId": "late",
            "fileName": "late.jpg",
            "contentSha256": "a" * 64,
            "sizeBytes": 3,
            "storageUri": "storage://downloads/late.jpg",
            "assetId": str(uuid4()),
        }

        repository.record_result(request_id, result)

        insert = next(call for call in cursor.execute.call_args_list
                      if "INSERT INTO download_item" in call.args[0])
        self.assertEqual("FAILED", insert.args[1][9])
        self.assertEqual("[]", insert.args[1][10])
        self.assertEqual(1, connection.commit.call_count)

    def test_non_terminal_update_cannot_overwrite_committed_terminal_status(self):
        """迟到的取消中状态不得覆盖并发线程已经提交的成功终态。"""
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = {"status": "SUCCEEDED"}
        request_id = uuid4()
        expected = self.request(request_id, DownloadStatus.SUCCEEDED)
        repository = MySqlDownloadRequestRepository(lambda: connection)

        with patch.object(repository, "find_by_id", return_value=expected):
            actual = repository.update_status(request_id, DownloadStatus.CANCELLING)

        self.assertEqual(expected, actual)
        self.assertEqual(1, connection.commit.call_count)
        connection.rollback.assert_not_called()
        statements = [call.args[0] for call in cursor.execute.call_args_list]
        self.assertEqual(["SELECT status FROM download_request WHERE id = %s FOR UPDATE"], statements)

    def test_late_replayed_binding_preserves_terminal_status(self):
        """并发创建请求的迟到任务绑定不得把成功终态回退为运行中。"""
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        request_id = uuid4()
        task_id = uuid4()
        cursor.fetchone.return_value = {"task_instance_id": str(task_id), "status": "SUCCEEDED"}
        expected = self.request(request_id, DownloadStatus.SUCCEEDED)
        repository = MySqlDownloadRequestRepository(lambda: connection)

        with patch.object(repository, "find_by_id", return_value=expected):
            actual = repository.bind_task(request_id, task_id)

        self.assertEqual(expected, actual)
        self.assertEqual(1, connection.commit.call_count)
        connection.rollback.assert_not_called()
        statements = [call.args[0] for call in cursor.execute.call_args_list]
        self.assertEqual([
            "SELECT task_instance_id, status FROM download_request WHERE id = %s FOR UPDATE"
        ], statements)

    def test_replayed_binding_rejects_another_scheduler_task(self):
        """同一请求不得静默绑定到两个不同的 Scheduler 任务。"""
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = {"task_instance_id": str(uuid4()), "status": "RUNNING"}
        repository = MySqlDownloadRequestRepository(lambda: connection)

        with self.assertRaisesRegex(ValueError, "task binding conflict"):
            repository.bind_task(uuid4(), uuid4())

        connection.commit.assert_not_called()
        self.assertEqual(1, connection.rollback.call_count)

    @staticmethod
    def request(request_id, status):
        """构造仓储返回的稳定下载请求。"""
        now = datetime.now(UTC)
        return DownloadRequest(
            id=request_id,
            idempotency_key="test-key",
            source_type="HTTP",
            source_key="test-source",
            request_kind="HTTP_ASSET",
            parameters={},
            status=status,
            task_instance_id=uuid4(),
            created_at=now,
            updated_at=now,
        )


if __name__ == "__main__":
    unittest.main()


class AsyncRepositoryTransactionTest(unittest.TestCase):
    """覆盖原子入队、终态隔离和过期工作租约的关键事务边界。"""

    def setup_repository(self):
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        return MySqlDownloadRequestRepository(lambda: connection, background_enabled=True), connection, cursor

    def result(self):
        return {'itemId': 'one', 'fileName': 'one.png', 'contentSha256': 'a' * 64, 'sizeBytes': 20,
                'storageUri': 'storage://managed/one.png', 'assetId': str(uuid4()), 'enrichmentMode': 'ASYNC',
                'mimeType': 'image/png', 'mediaRegistered': True, 'ownerId': 999}

    def test_item_and_queue_are_committed_together_with_trusted_owner(self):
        import json
        repository, connection, cursor = self.setup_repository()
        cursor.fetchone.side_effect = [{'status': 'RUNNING', 'owner_id': 17}, None]
        repository.record_result(uuid4(), self.result())
        sql = [call.args[0] for call in cursor.execute.call_args_list]
        self.assertTrue(any('INSERT INTO download_item' in statement for statement in sql))
        queue = next(call for call in cursor.execute.call_args_list if 'INSERT INTO download_background_job' in call.args[0])
        self.assertEqual(17, json.loads(queue.args[1][4])['ownerId'])
        self.assertNotIn('sourcePath', queue.args[1][4])
        self.assertEqual(1, connection.commit.call_count)

    def test_queue_failure_rolls_back_result(self):
        repository, connection, cursor = self.setup_repository()
        cursor.fetchone.side_effect = [{'status': 'RUNNING', 'owner_id': 17}, None]
        def execute(sql, *_args):
            if 'INSERT INTO download_background_job' in sql:
                raise RuntimeError('queue unavailable')
        cursor.execute.side_effect = execute
        with self.assertRaises(RuntimeError):
            repository.record_result(uuid4(), self.result())
        connection.commit.assert_not_called()
        connection.rollback.assert_called_once()

    def test_core_terminal_does_not_seal_asynchronous_pending_items(self):
        repository, connection, cursor = self.setup_repository()
        cursor.fetchone.return_value = {'status': 'RUNNING'}
        expected = MagicMock()
        with patch.object(repository, 'find_by_id', return_value=expected):
            repository.complete_terminal(uuid4(), DownloadStatus.SUCCEEDED)
        update = next(call.args[0] for call in cursor.execute.call_args_list if 'UPDATE download_item' in call.args[0])
        self.assertIn('NOT EXISTS', update)
        self.assertIn("bg.kind = 'ENRICHMENT'", update)
        connection.commit.assert_called_once()

    def test_stale_worker_cannot_ack_or_fail_new_claim(self):
        repository, connection, cursor = self.setup_repository()
        cursor.fetchone.return_value = {'claim_token': 'new-worker'}
        repository.finish_background({'id': 'job', 'claim_token': 'old-worker', 'kind': 'RECONCILE'}, 'SUCCEEDED')
        self.assertFalse(any(call.args[0].lstrip().startswith('UPDATE ') for call in cursor.execute.call_args_list))
        connection.rollback.assert_called_once()

    def test_rejected_reconciliation_only_fails_an_unbound_request(self):
        for bound in (False, True):
            with self.subTest(bound=bound):
                repository, connection, cursor = self.setup_repository()
                cursor.fetchone.side_effect = [
                    {'status': 'RUNNING' if bound else 'ACCEPTED', 'task_instance_id': str(uuid4()) if bound else None},
                    {'claim_token': 'current-worker'},
                ]
                repository.finish_background({'id': 'job', 'claim_token': 'current-worker', 'kind': 'RECONCILE',
                                              'download_request_id': str(uuid4())}, 'FAILED',
                                             error='BACKGROUND_REQUEST_INVALID', fail_unbound_request=True)
                updates = [call for call in cursor.execute.call_args_list if call.args[0].lstrip().startswith('UPDATE ')]
                self.assertEqual(not bound, any('UPDATE download_request' in call.args[0] for call in updates))
                self.assertEqual('PENDING' if bound else 'FAILED', updates[-1].args[1][0])
                self.assertEqual(None if bound else 'BACKGROUND_REQUEST_INVALID', updates[-1].args[1][4])
                connection.commit.assert_called_once()

    def test_queue_claims_skip_locks_and_release_transaction_before_work(self):
        repository, connection, cursor = self.setup_repository()
        cursor.fetchall.return_value = [{'id': 'job', 'payload_json': '{}'}]
        jobs = repository.claim_background('RECONCILE')
        self.assertEqual({}, jobs[0]['payload_json'])
        self.assertTrue(jobs[0]['claim_token'])
        self.assertIn('FOR UPDATE SKIP LOCKED', cursor.execute.call_args_list[0].args[0])
        connection.commit.assert_called_once()

    def test_enrichment_terminal_only_updates_tags_and_job(self):
        repository, connection, cursor = self.setup_repository()
        cursor.fetchone.side_effect = [{'status': 'SUCCEEDED'}, {'claim_token': 'one'}, {'tag_status': 'FAILED'}]
        repository.finish_background({'id': 'job', 'claim_token': 'one', 'kind': 'ENRICHMENT',
                                      'download_request_id': str(uuid4()), 'external_item_id': 'file'}, 'FAILED')
        updates = [call.args[0] for call in cursor.execute.call_args_list if 'UPDATE ' in call.args[0]]
        self.assertFalse(any('UPDATE download_request' in sql for sql in updates))
        self.assertTrue(any('UPDATE download_item' in sql for sql in updates))
        self.assertTrue(any('UPDATE download_background_job' in sql for sql in updates))
