"""持久化后处理和下载状态对账；网络调用期间不持有数据库事务。"""
from __future__ import annotations

import json
import logging
from threading import Event, Thread
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import UUID

LOGGER = logging.getLogger(__name__)
TERMINAL = {'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'}


class DownloadBackgroundWorker:
    """独立后台循环，重启后从到期租约继续，不创建轮询执行任务。"""

    def __init__(self, repository, service, scheduler, messaging_url: str, messaging_token: str,
                 opener=urlopen, *, enrichment_paused: bool = False):
        self.repository, self.service, self.scheduler = repository, service, scheduler
        self.messaging_url, self.messaging_token = messaging_url.rstrip('/'), messaging_token
        self.opener = opener
        self.enrichment_paused = enrichment_paused
        self.stop_event = Event()
        self.threads: list[Thread] = []

    def start(self) -> None:
        """分离状态对账和后处理提交，模型队列不阻塞用户状态更新。"""
        for kind in ('RECONCILE', 'ENRICHMENT'):
            thread = Thread(target=self._loop, args=(kind,), daemon=True, name='download-' + kind.lower())
            thread.start()
            self.threads.append(thread)

    def stop(self) -> None:
        """停止领取，未完成租约由后续进程接管。"""
        self.stop_event.set()
        for thread in self.threads:
            thread.join(timeout=2)

    def _loop(self, kind: str) -> None:
        while not self.stop_event.is_set():
            try:
                self.run_once(kind)
            except Exception as exception:
                LOGGER.warning('Download background claim failed: kind=%s errorType=%s', kind, type(exception).__name__)
            self.stop_event.wait(0.25)

    def run_once(self, kind: str) -> None:
        """处理有界小批次，并把失败退避持久化；不会打印来源 URL 或凭据。"""
        # 维护期间只暂停后处理领取，下载终态对账仍持续运行。
        if kind == 'ENRICHMENT' and self.enrichment_paused:
            return
        for _ in range(10):
            if self.stop_event.is_set():
                break
            jobs = self.repository.claim_background(kind, limit=1)
            if not jobs:
                break
            job = jobs[0]
            try:
                if kind == 'RECONCILE':
                    self._reconcile(job)
                else:
                    self._enrich(job)
            except Exception as exception:
                delay = min(300, 2 ** min(int(job['attempts']) + 1, 8))
                # 保留原状态及任务绑定；提交响应丢失时重复使用同一幂等键。
                self.repository.finish_background(job, job['state'],
                    error=type(exception).__name__, delay=delay)
                LOGGER.warning('Download background deferred: kind=%s errorType=%s', kind, type(exception).__name__)

    def _reconcile(self, job: dict) -> None:
        request = self.service.get(UUID(job['download_request_id']))
        if request is None:
            raise LookupError('download request is missing')
        if request.status.value not in TERMINAL:
            if request.task_instance_id is None:
                from .models import CreateDownloadRequest
                try:
                    self.service.create(CreateDownloadRequest(request.idempotency_key, request.source_type,
                        request.source_key, request.request_kind, request.parameters, request.owner_id))
                except HTTPError as error:
                    # 历史无绑定请求可能已不符合当前参数协议；明确拒绝不能靠退避恢复。
                    if error.code in (400, 422):
                        self.repository.finish_background(job, 'FAILED', error='BACKGROUND_REQUEST_INVALID',
                                                          fail_unbound_request=True)
                        return
                    raise
            self.repository.finish_background(job, 'PENDING')
            return
        if request.source_type == 'MESSAGE_ATTACHMENT':
            value = request.parameters.get('attachmentJobId') or request.source_key.rsplit(':', 1)[-1]
            identifier = str(UUID(str(value)))
            if not self.messaging_token:
                raise ValueError('messaging credentials are missing')
            query = Request(self.messaging_url + '/internal/v1/attachment-downloads/' + identifier,
                            headers={'Authorization': 'Bearer ' + self.messaging_token, 'Accept': 'application/json'})
            with self.opener(query, timeout=15) as response:
                message = json.load(response)
            # 历史包装任务可能抢先失败且未绑定；保留原失败证据，不误报完成或无限轮询。
            if (str(message.get('id')) == identifier and message.get('status') in TERMINAL
                    and message.get('downloadRequestId') is None):
                self.repository.finish_background(job, 'FAILED', error='MESSAGE_JOB_UNBOUND_TERMINAL')
                return
            if (str(message.get('id')) != identifier or message.get('status') not in TERMINAL
                    or str(message.get('downloadRequestId')) != str(request.id)):
                raise ValueError('attachment reconciliation is incomplete')
        self.repository.finish_background(job, 'SUCCEEDED')

    def _enrich(self, job: dict) -> None:
        if not job['task_instance_id']:
            task_id = self.scheduler.create_task(task_name='download_enrich_file',
                idempotency_key='download-' + job['id'] + ':v1', business_id=job['download_request_id'],
                parameters=job['payload_json'], priority=10)
            self.repository.finish_background(job, 'SUBMITTED', task_id=str(task_id))
            return
        task = self.scheduler.get_task(UUID(job['task_instance_id']))
        status = str(task['status'])
        if status not in TERMINAL:
            self.repository.finish_background(job, 'SUBMITTED')
            return
        self.repository.finish_background(job, 'SUCCEEDED' if status == 'SUCCEEDED' else 'FAILED',
                                           error=None if status == 'SUCCEEDED' else status)
