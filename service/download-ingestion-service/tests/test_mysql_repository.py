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
