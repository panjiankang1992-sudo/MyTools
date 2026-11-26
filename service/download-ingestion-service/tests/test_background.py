"""验证异步下载持久队列的恢复、状态隔离和失败重试。"""
import io
import json
import unittest
from types import SimpleNamespace
from unittest.mock import Mock
from uuid import uuid4

from mytools_download_ingestion.background import DownloadBackgroundWorker
from mytools_download_ingestion.models import CreateDownloadRequest, DownloadStatus
from mytools_download_ingestion.service import DownloadRequestService, InMemoryDownloadRequestRepository


class DownloadBackgroundTest(unittest.TestCase):
    """不用真实渠道发送，验证核心下载与标签完全独立。"""

    def worker(self, job, request=None, opener=None):
        repository = Mock()
        repository.claim_background.side_effect = [[job], []]
        service = Mock()
        service.get.return_value = request
        scheduler = Mock()
        scheduler.create_task.return_value = uuid4()
        worker = DownloadBackgroundWorker(repository, service, scheduler, 'http://messaging', 'token', opener)
        return worker, repository, service, scheduler

    def job(self, kind='ENRICHMENT', task_id=None):
        return {'id': 'enrich:' + str(uuid4()), 'kind': kind, 'state': 'SUBMITTED' if task_id else 'PENDING',
                'task_instance_id': task_id, 'download_request_id': str(uuid4()), 'payload_json': {'ownerId': 17},
                'claim_token': str(uuid4()), 'attempts': 0}

    def test_enqueue_only_submits_one_independent_task_without_waiting(self):
        job = self.job()
        worker, repository, _, scheduler = self.worker(job)
        worker.run_once('ENRICHMENT')
        sent = scheduler.create_task.call_args.kwargs
        self.assertEqual('download_enrich_file', sent['task_name'])
        self.assertEqual(10, sent['priority'])
        self.assertEqual('download-' + job['id'] + ':v1', sent['idempotency_key'])
        scheduler.get_task.assert_not_called()
        self.assertEqual('SUBMITTED', repository.finish_background.call_args.args[1])

    def test_maintenance_pause_leaves_durable_jobs_unclaimed(self):
        worker, repository, _, scheduler = self.worker(self.job())
        worker.enrichment_paused = True
        worker.run_once('ENRICHMENT')
        repository.claim_background.assert_not_called()
        scheduler.create_task.assert_not_called()
        worker.run_once('RECONCILE')
        repository.claim_background.assert_called()

    def test_lost_submit_response_uses_identical_key_after_lease_recovery(self):
        job = self.job()
        worker, repository, _, scheduler = self.worker(job)
        scheduler.create_task.side_effect = [TimeoutError(), uuid4()]
        worker.run_once('ENRICHMENT')
        self.assertEqual('PENDING', repository.finish_background.call_args.args[1])
        repository.claim_background.side_effect = [[job], []]
        worker.run_once('ENRICHMENT')
        calls = scheduler.create_task.call_args_list
        self.assertEqual(calls[0].kwargs, calls[1].kwargs)

    def test_enrichment_failure_does_not_mutate_request_status(self):
        worker, repository, service, scheduler = self.worker(self.job(task_id=str(uuid4())))
        scheduler.get_task.return_value = {'status': 'FAILED'}
        worker.run_once('ENRICHMENT')
        self.assertEqual('FAILED', repository.finish_background.call_args.args[1])
        service.get.assert_not_called()

    def test_terminal_attachment_callback_must_be_confirmed_before_ack(self):
        identifier = str(uuid4())
        request = SimpleNamespace(id=uuid4(), task_instance_id=uuid4(), status=DownloadStatus.SUCCEEDED,
                                  source_type='MESSAGE_ATTACHMENT', parameters={'attachmentJobId': identifier})
        opener = Mock(return_value=io.BytesIO(json.dumps({'id': identifier, 'status': 'SUCCEEDED', 'downloadRequestId': str(request.id)}).encode()))
        worker, repository, _, _ = self.worker(self.job('RECONCILE'), request, opener)
        worker.run_once('RECONCILE')
        self.assertEqual('SUCCEEDED', repository.finish_background.call_args.args[1])
        self.assertTrue(opener.call_args.args[0].full_url.endswith(identifier))

    def test_failed_callback_is_retried_even_when_download_already_succeeded(self):
        request = SimpleNamespace(id=uuid4(), task_instance_id=uuid4(), status=DownloadStatus.SUCCEEDED,
                                  source_type='MESSAGE_ATTACHMENT', parameters={'attachmentJobId': str(uuid4())})
        worker, repository, _, _ = self.worker(self.job('RECONCILE'), request, Mock(side_effect=OSError()))
        worker.run_once('RECONCILE')
        self.assertEqual('PENDING', repository.finish_background.call_args.args[1])
        self.assertEqual('OSError', repository.finish_background.call_args.kwargs['error'])

    def test_historical_terminal_unbound_message_is_not_acknowledged(self):
        identifier = str(uuid4())
        request = SimpleNamespace(id=uuid4(), status=DownloadStatus.SUCCEEDED,
            source_type='MESSAGE_ATTACHMENT', parameters={'attachmentJobId': identifier})
        response = io.BytesIO(json.dumps({'id': identifier, 'status': 'FAILED', 'downloadRequestId': None}).encode())
        worker, repository, _, _ = self.worker(self.job('RECONCILE'), request, Mock(return_value=response))
        worker.run_once('RECONCILE')
        self.assertEqual('FAILED', repository.finish_background.call_args.args[1])
        self.assertEqual('MESSAGE_JOB_UNBOUND_TERMINAL', repository.finish_background.call_args.kwargs['error'])

    def test_invalid_unbound_request_is_not_submitted_forever(self):
        from urllib.error import HTTPError
        request = SimpleNamespace(task_instance_id=None, status=DownloadStatus.ACCEPTED,
            idempotency_key='old', source_type='APP_TOOL', source_key='old',
            request_kind='MESSAGE_URL_BATCH', parameters={}, owner_id=17)
        worker, repository, service, _ = self.worker(self.job('RECONCILE'), request)
        service.create.side_effect = HTTPError('http://scheduler', 400, 'invalid', {}, None)
        worker.run_once('RECONCILE')
        self.assertEqual('FAILED', repository.finish_background.call_args.args[1])
        self.assertEqual('BACKGROUND_REQUEST_INVALID', repository.finish_background.call_args.kwargs['error'])
        self.assertTrue(repository.finish_background.call_args.kwargs['fail_unbound_request'])

    def test_queue_wait_has_no_expiry_and_does_not_start_new_download(self):
        request = SimpleNamespace(task_instance_id=uuid4(), status=DownloadStatus.RUNNING)
        worker, repository, service, scheduler = self.worker(self.job('RECONCILE'), request)
        worker.run_once('RECONCILE')
        self.assertEqual('PENDING', repository.finish_background.call_args.args[1])
        service.create.assert_not_called()
        scheduler.create_task.assert_not_called()

    def test_core_success_keeps_pending_tags_and_accepts_late_tag_callback(self):
        repository = InMemoryDownloadRequestRepository(background_enabled=True)
        scheduler = Mock()
        scheduler.create_task.return_value = uuid4()
        scheduler.get_task.return_value = {'status': 'RUNNING'}
        service = DownloadRequestService(repository, scheduler)
        request = service.create(CreateDownloadRequest('async-one', 'APP_TOOL', 'one', 'MESSAGE_URL_BATCH', {}, 17))
        result = {'itemId': 'one', 'fileName': 'one.png', 'contentSha256': 'a' * 64,
                  'sizeBytes': 20, 'storageUri': 'storage://managed/one.png', 'assetId': str(uuid4()),
                  'enrichmentMode': 'ASYNC', 'mimeType': 'image/png', 'mediaRegistered': True}
        service.record_result(request.id, result)
        scheduler.get_task.return_value = {'status': 'SUCCEEDED'}
        summary = service.result_summary(request.id)
        self.assertEqual('SUCCEEDED', summary['status'])
        self.assertEqual('PENDING', summary['postProcessingStatus'])
        self.assertEqual('PENDING', summary['items'][0]['tagStatus'])
        service.record_tags(request.id, {'itemId': 'one', 'tagStatus': 'TAGGED',
                                        'tags': [{'name': 'test', 'type': 'topic', 'confidence': .9}]})
        self.assertEqual('SUCCEEDED', service.result_summary(request.id)['status'])
        self.assertEqual('SUCCEEDED', service.result_summary(request.id)['postProcessingStatus'])
        self.assertIsNone(service.result_snapshot_for_owner(request.id, 18))

    def test_background_history_snapshot_never_calls_scheduler(self):
        repository = InMemoryDownloadRequestRepository()
        scheduler = Mock(); scheduler.create_task.return_value = uuid4()
        service = DownloadRequestService(repository, scheduler)
        request = service.create(CreateDownloadRequest('snapshot', 'APP_TOOL', 'snapshot', 'MESSAGE_URL_BATCH', {}, 17))
        self.assertEqual('RUNNING', service.result_snapshot_for_owner(request.id, 17)['status'])
        scheduler.get_task.assert_not_called()
