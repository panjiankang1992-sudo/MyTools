"""OneBot Connector 应用边界。"""

from __future__ import annotations

from pathlib import Path, PurePosixPath
from datetime import UTC, datetime, timedelta
import fcntl
import hashlib
import json
import logging
import os
import re
import stat
import tempfile
from typing import Any, Callable, NoReturn
from urllib.parse import urlparse
from uuid import UUID, uuid4

from .connector import OneBotClient
from .models import (Account, ProviderFileRequest, ProviderPermanentRejectionError,
                     ProviderRequestNotStartedError, ProviderTransientRejectionError,
                     TextReply, TextReplyStatus)
from .repository import AccountRepository

SAFE_KEY = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
ATTACHMENT_TYPES = {"IMAGE", "VIDEO", "RECORD", "FILE"}
LOGGER = logging.getLogger(__name__)
TEXT_REPLY_MAX_ATTEMPTS = 10
TEXT_REPLY_MAX_BACKOFF_SECONDS = 60
TEXT_REPLY_STALE_SECONDS = 120
RELOGIN_RECEIPT_LIMIT = 64
RELOGIN_STATE_MAX_BYTES = 128 * 1024
RELOGIN_ACTION_MAX_ATTEMPTS = 3


def _atomic_write_private_json(target: Path, document: dict) -> None:
    """在固定真实目录中原子写入私有 JSON 文件。"""

    parent_metadata = target.parent.lstat()
    if not stat.S_ISDIR(parent_metadata.st_mode):
        raise RuntimeError("OneBot relogin control directory is invalid")
    if os.path.lexists(target) and not stat.S_ISREG(target.lstat().st_mode):
        raise RuntimeError("OneBot relogin control file is invalid")
    encoded = json.dumps(document, separators=(",", ":")).encode("utf-8")
    if len(encoded) > RELOGIN_STATE_MAX_BYTES:
        raise RuntimeError("OneBot relogin control state is too large")
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{target.name}.incoming-", dir=target.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(encoded)
            output.flush()
            os.fchmod(output.fileno(), 0o600)
            os.fsync(output.fileno())
        if not stat.S_ISREG(temporary.lstat().st_mode):
            raise RuntimeError("OneBot relogin temporary file is invalid")
        os.replace(temporary, target)
        directory_descriptor = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    finally:
        if os.path.lexists(temporary):
            temporary.unlink()


def _read_relogin_state(path: Path) -> tuple[list[dict[str, str]], dict[str, int]]:
    """读取有界的重登录 receipt 和动作次数。"""

    if not os.path.lexists(path):
        return [], {}
    metadata = path.lstat()
    if (not stat.S_ISREG(metadata.st_mode) or metadata.st_size > RELOGIN_STATE_MAX_BYTES
            or metadata.st_mode & 0o077):
        raise RuntimeError("OneBot relogin receipt state is invalid")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise RuntimeError("OneBot relogin receipt state is invalid") from exception
    if not isinstance(document, dict):
        raise RuntimeError("OneBot relogin receipt state is invalid")
    requests = document.get("requests")
    attempts = document.get("attempts", {})
    if set(document) - {"version", "requests", "attempts"} \
            or document.get("version") != 1 or not isinstance(requests, list) \
            or len(requests) > RELOGIN_RECEIPT_LIMIT:
        raise RuntimeError("OneBot relogin receipt state is invalid")
    normalized: list[dict[str, str]] = []
    for item in requests:
        if not isinstance(item, dict) or set(item) != {
                "key", "accountId", "requestId", "requestedAt"}:
            raise RuntimeError("OneBot relogin receipt state is invalid")
        values = {key: str(item[key]) for key in item}
        if (re.fullmatch(r"[a-f0-9]{64}", values["key"]) is None
                or SAFE_KEY.fullmatch(values["accountId"]) is None
                or SAFE_KEY.fullmatch(values["requestId"]) is None):
            raise RuntimeError("OneBot relogin receipt state is invalid")
        try:
            requested_at = datetime.fromisoformat(
                values["requestedAt"].replace("Z", "+00:00"))
        except ValueError as exception:
            raise RuntimeError("OneBot relogin receipt state is invalid") from exception
        if requested_at.tzinfo is None:
            raise RuntimeError("OneBot relogin receipt state is invalid")
        normalized.append(values)
    receipt_keys = {item["key"] for item in normalized}
    if not isinstance(attempts, dict) or len(attempts) > RELOGIN_RECEIPT_LIMIT:
        raise RuntimeError("OneBot relogin receipt state is invalid")
    normalized_attempts: dict[str, int] = {}
    for key, value in attempts.items():
        if re.fullmatch(r"[a-f0-9]{64}", str(key)) is None or str(key) not in receipt_keys \
                or type(value) is not int or value < 0 or value > RELOGIN_ACTION_MAX_ATTEMPTS:
            raise RuntimeError("OneBot relogin receipt state is invalid")
        normalized_attempts[str(key)] = value
    return normalized, normalized_attempts


def _write_relogin_state(path: Path, receipts: list[dict[str, str]],
                         attempts: dict[str, int]) -> None:
    """原子写入兼容旧版读取器的重登录状态。"""
    _atomic_write_private_json(
        path, {"version": 1, "requests": receipts, "attempts": attempts})


def _read_relogin_marker(path: Path) -> dict[str, Any] | None:
    """读取固定重登录标记并拒绝非私有文件。"""
    if not os.path.lexists(path):
        return None
    metadata = path.lstat()
    if (not stat.S_ISREG(metadata.st_mode) or metadata.st_size > RELOGIN_STATE_MAX_BYTES
            or metadata.st_mode & 0o077):
        raise RuntimeError("OneBot relogin marker is invalid")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise RuntimeError("OneBot relogin marker is invalid") from exception
    legacy_fields = {"accountId", "requestId", "requestedAt"}
    current_fields = {*legacy_fields, "attempt", "attemptedAt"}
    if not isinstance(document, dict) \
            or (set(document) != legacy_fields and set(document) != current_fields):
        raise RuntimeError("OneBot relogin marker is invalid")
    account_id = document.get("accountId")
    request_id = document.get("requestId")
    requested_at = document.get("requestedAt")
    attempt = document.get("attempt", 1)
    attempted_at = document.get("attemptedAt", requested_at)
    if not isinstance(account_id, str) or SAFE_KEY.fullmatch(account_id) is None \
            or not isinstance(request_id, str) or SAFE_KEY.fullmatch(request_id) is None \
            or not isinstance(requested_at, str) or not isinstance(attempted_at, str) \
            or type(attempt) is not int or attempt < 1 \
            or attempt > RELOGIN_ACTION_MAX_ATTEMPTS:
        raise RuntimeError("OneBot relogin marker is invalid")
    try:
        requested_time = datetime.fromisoformat(requested_at.replace("Z", "+00:00"))
        attempted_time = datetime.fromisoformat(attempted_at.replace("Z", "+00:00"))
    except ValueError as exception:
        raise RuntimeError("OneBot relogin marker is invalid") from exception
    if requested_time.tzinfo is None or attempted_time.tzinfo is None:
        raise RuntimeError("OneBot relogin marker is invalid")
    return {"accountId": account_id, "requestId": request_id,
            "requestedAt": requested_at, "attempt": attempt,
            "attemptedAt": attempted_at}


def _same_relogin_request(marker: dict[str, Any] | None,
                          account_id: str, request_id: str,
                          requested_at: str) -> bool:
    """确认固定标记是当前幂等请求。"""
    return marker is not None and marker["accountId"] == account_id \
        and marker["requestId"] == request_id \
        and marker["requestedAt"] == requested_at


class OneBotConnectorService:
    """管理账户路由并隔离渠道凭据操作。"""

    def __init__(self, repository: AccountRepository, client: OneBotClient,
                 enabled: bool, maximum_bytes: int,
                 relogin_request_path: str | None = None,
                 qr_path: str | None = None,
                 maximum_qr_bytes: int = 2 * 1024 * 1024,
                 clock: Callable[[], datetime] | None = None,
                 text_reply_max_attempts: int = TEXT_REPLY_MAX_ATTEMPTS,
                 text_reply_stale_seconds: int = TEXT_REPLY_STALE_SECONDS) -> None:
        if maximum_bytes <= 0:
            raise ValueError("OneBot Connector maximumBytes must be positive")
        self._repository = repository
        self._client = client
        self._enabled = enabled
        self._maximum_bytes = maximum_bytes
        self._relogin_request_path = Path(relogin_request_path) if relogin_request_path else None
        self._qr_path = Path(qr_path) if qr_path else None
        self._maximum_qr_bytes = maximum_qr_bytes
        if maximum_qr_bytes <= 0:
            raise ValueError("OneBot Connector maximumQrBytes must be positive")
        if text_reply_max_attempts <= 0 or text_reply_stale_seconds <= 0:
            raise ValueError("OneBot text reply retry configuration must be positive")
        self._clock = clock or (lambda: datetime.now(UTC))
        self._text_reply_max_attempts = text_reply_max_attempts
        self._text_reply_stale_after = timedelta(seconds=text_reply_stale_seconds)

    def register(self, payload: dict) -> dict:
        """注册仅限回环地址的账户且不返回凭据引用。"""
        account = validated_account(payload)
        saved = self._repository.save(account)
        return {"id": str(saved.id), "externalKey": saved.external_key, "enabled": saved.enabled}

    def resolve(self, payload: dict) -> dict:
        """将文件解析为 PUBLIC_URL 或 STREAM 模式。"""
        request, account = self._authorized_request(payload)
        source = self._client.prepare(account, request.provider_file_id)
        public = self._client.public_url(source)
        return {"mode": "PUBLIC_URL", "downloadUrl": public} if public else {"mode": "STREAM"}

    def prepare_content(self, payload: dict):
        """准备 STREAM 内容源并拒绝误用公开模式。"""
        request, account = self._authorized_request(payload)
        source = self._client.prepare(account, request.provider_file_id)
        if self._client.public_url(source) is not None:
            raise ValueError("public provider content must use PUBLIC_URL mode")
        return source

    def stream_content(self, source, output) -> int:
        """在连接器配置上限内传输已准备的内容。"""
        return self._client.stream(source, output, self._maximum_bytes)

    def request_relogin(self, payload: dict) -> dict:
        """按请求标识幂等确认在线状态或写入可确认的重登录请求。"""
        if set(payload) != {"accountKey", "requestId"}:
            raise ValueError("OneBot relogin request is invalid")
        account = self._control_account(payload)
        request_id = str(payload.get("requestId", ""))
        if not SAFE_KEY.fullmatch(request_id):
            raise ValueError("OneBot relogin request id is invalid")
        try:
            if self._client.is_online(account):
                return {"accountKey": account.external_key, "requestId": request_id,
                        "status": "ALREADY_ONLINE"}
        except (ProviderRequestNotStartedError, ProviderTransientRejectionError, OSError):
            # 未启动或瞬时不可用表示需要进入受控重启流程。
            pass
        if self._relogin_request_path is None:
            raise RuntimeError("OneBot relogin control is not configured")
        target = self._relogin_request_path
        target.parent.mkdir(parents=True, exist_ok=True)
        parent_metadata = target.parent.lstat()
        if not stat.S_ISDIR(parent_metadata.st_mode):
            raise RuntimeError("OneBot relogin control directory is invalid")
        lock_path = target.with_name(f"{target.name}.lock")
        state_path = target.with_name(f"{target.name}.state.json")
        lock_descriptor = os.open(
            lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        with os.fdopen(lock_descriptor, "r+b", closefd=True) as lock_file:
            receipt_key = hashlib.sha256(
                f"{account.external_key}\0{request_id}".encode("utf-8")).hexdigest()
            try:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                # systemd 动作持有同一文件锁时，重放必须快速返回而不卡住任务。
                receipts, _attempts = _read_relogin_state(state_path)
                existing = next(
                    (item for item in receipts if item["key"] == receipt_key), None)
                if existing is None:
                    raise RuntimeError("another OneBot relogin action is in progress")
                return self._relogin_response(
                    account.external_key, request_id, existing["requestedAt"], "REQUESTED")
            receipts, attempts = _read_relogin_state(state_path)
            existing = next((item for item in receipts if item["key"] == receipt_key), None)
            if existing is not None:
                if (existing["accountId"] != account.external_key
                        or existing["requestId"] != request_id):
                    raise RuntimeError("OneBot relogin receipt identity is invalid")
                requested_at_text = existing["requestedAt"]
            else:
                requested_at_text = self._clock().astimezone(UTC).isoformat()
                receipt = {"key": receipt_key, "accountId": account.external_key,
                           "requestId": request_id, "requestedAt": requested_at_text}
                receipts = [*receipts, receipt][-RELOGIN_RECEIPT_LIMIT:]
                retained_keys = {item["key"] for item in receipts}
                attempts = {key: value for key, value in attempts.items()
                            if key in retained_keys}

            active = _read_relogin_marker(target)
            acknowledged = _read_relogin_marker(target.with_name(f"{target.name}.ack"))
            failed = _read_relogin_marker(target.with_name(f"{target.name}.failed"))
            if active is not None:
                if not _same_relogin_request(
                        active, account.external_key, request_id, requested_at_text):
                    raise RuntimeError("another OneBot relogin action is pending")
                return self._relogin_response(
                    account.external_key, request_id, requested_at_text, "REQUESTED")
            if _same_relogin_request(
                    acknowledged, account.external_key, request_id, requested_at_text):
                return self._relogin_response(
                    account.external_key, request_id, requested_at_text, "RESTARTED")

            attempt_count = attempts.get(receipt_key, 0)
            if _same_relogin_request(
                    failed, account.external_key, request_id, requested_at_text):
                attempt_count = max(attempt_count, int(failed["attempt"]))
            if attempt_count >= RELOGIN_ACTION_MAX_ATTEMPTS:
                return self._relogin_response(
                    account.external_key, request_id, requested_at_text, "FAILED")

            previous_receipts = [dict(item) for item in receipts]
            previous_attempts = dict(attempts)
            next_attempt = attempt_count + 1
            attempts[receipt_key] = next_attempt
            _write_relogin_state(state_path, receipts, attempts)
            attempted_at_text = self._clock().astimezone(UTC).isoformat()
            document = {"accountId": account.external_key, "requestId": request_id,
                        "requestedAt": requested_at_text, "attempt": next_attempt,
                        "attemptedAt": attempted_at_text}
            try:
                _atomic_write_private_json(target, document)
            except Exception:
                # 同步写失败时撤销本次动作次数，便于同键安全恢复。
                _write_relogin_state(state_path, previous_receipts, previous_attempts)
                raise
        return self._relogin_response(
            account.external_key, request_id, requested_at_text, "REQUESTED")

    @staticmethod
    def _relogin_response(account_key: str, request_id: str,
                          requested_at: str, status: str) -> dict:
        return {"accountKey": account_key, "requestId": request_id,
                "requestedAt": requested_at, "status": status}

    def send_text(self, payload: dict) -> dict:
        """以持久化幂等边界向已登记 OneBot 账户发送原会话文本。"""
        required = {"accountKey", "messageType", "targetId", "messageId", "text", "idempotencyKey"}
        LOGGER.debug("OneBot text request received: fields=%s, idempotencyKeyLength=%d, "
                     "textLength=%d", sorted(payload),
                     len(str(payload.get("idempotencyKey", ""))),
                     len(str(payload.get("text", ""))))
        if set(payload) != required:
            raise ValueError("OneBot text request is invalid")
        if any(not isinstance(payload[field], str) for field in required):
            raise ValueError("OneBot text request is invalid")
        account = self._control_account(payload)
        message_type = payload["messageType"]
        target_id = payload["targetId"]
        message_id = payload["messageId"]
        text = payload["text"]
        idempotency_key = payload["idempotencyKey"]
        if message_type not in {"private", "group"} \
                or re.fullmatch(r"[0-9]{1,64}", target_id) is None \
                or not message_id or len(message_id) > 512 \
                or not text or len(text) > 4000 \
                or not idempotency_key.strip() or len(idempotency_key) > 255:
            raise ValueError("OneBot text request is invalid")
        candidate = TextReply.create(account.external_key, idempotency_key, message_type,
                                     target_id, message_id, text, self._clock())
        claim = self._repository.claim_text_reply(candidate)
        reply = claim.reply
        if reply.status is not TextReplyStatus.PREPARED:
            return self._replayed_text_reply(reply)
        now = self._clock()
        if reply.next_attempt_at > now:
            raise RuntimeError("OneBot text delivery was not sent; retry later with the same key")
        attempt_token = uuid4()
        try:
            active = self._repository.start_text_reply(
                reply.id, reply.attempt_count, attempt_token, now)
        except Exception as exception:
            # 若提交响应丢失，只在数据库明确持有本次唯一令牌时继续调用渠道。
            active = self._recover_started_text_reply(reply.id, attempt_token)
            if active is None:
                raise RuntimeError(
                    "OneBot text delivery was not started; retry later with the same key"
                ) from exception
        if active is None:
            current = self._repository.find_text_reply(reply.id)
            if current is None:
                raise RuntimeError("OneBot text reply does not exist")
            return self._replayed_text_reply(current)
        try:
            result = self._client.send_text(account, message_type, target_id, text)
            provider_message_id = str(result.get("message_id") or "")
            if not provider_message_id or len(provider_message_id) > 512:
                raise RuntimeError("OneBot provider did not return a message id")
        except Exception as exception:
            if isinstance(exception, ProviderPermanentRejectionError):
                return self._reschedule_text_reply(active, exception, force_exhausted=True)
            if provider_definitely_not_sent(exception):
                return self._reschedule_text_reply(active, exception)
            # 只有无法证明请求未发出的异常才进入不可判定终态。
            self._mark_text_reply_uncertain(active.id, attempt_token)
            raise RuntimeError(
                "OneBot text delivery outcome is uncertain; manual provider verification is required"
            ) from exception
        try:
            persisted = self._repository.mark_text_reply_sent(
                active.id, attempt_token, provider_message_id)
        except Exception as exception:
            # 提交响应丢失时先读取或固化持久状态；只有数据库明确为 SENT 才可返回成功。
            recovered = self._mark_text_reply_uncertain(active.id, attempt_token)
            if recovered is not None and recovered.status is TextReplyStatus.SENT \
                    and recovered.provider_message_id == provider_message_id:
                return self._sent_text_reply(recovered)
            raise RuntimeError(
                "OneBot text delivery outcome is uncertain; manual provider verification is required"
            ) from exception
        return self._sent_text_reply(persisted)

    def expand_forward(self, payload: dict) -> dict:
        """展开一条合并转发消息供入站适配器标准化。"""
        if set(payload) != {"accountKey", "forwardId"}:
            raise ValueError("OneBot forward request is invalid")
        account = self._control_account(payload)
        messages = self._client.get_forward_message(account, str(payload["forwardId"]))
        return {"messages": messages[:500]}

    def prepare_qr(self, payload: dict) -> tuple[Path, int]:
        """校验并准备固定二维码文件，不接受调用方提供路径。"""
        self._control_account(payload)
        if set(payload) != {"accountKey", "requestedAt"}:
            raise ValueError("OneBot QR request is invalid")
        try:
            requested_at = datetime.fromisoformat(str(payload["requestedAt"]).replace("Z", "+00:00"))
        except ValueError as exception:
            raise ValueError("OneBot QR request time is invalid") from exception
        if requested_at.tzinfo is None:
            raise ValueError("OneBot QR request time is invalid")
        if self._qr_path is None:
            raise RuntimeError("OneBot QR control is not configured")
        try:
            path = self._qr_path.resolve(strict=True)
            stat = path.stat()
        except FileNotFoundError as exception:
            raise RuntimeError("fresh OneBot login QR is not available") from exception
        generated_at = datetime.fromtimestamp(stat.st_mtime, UTC)
        if generated_at < requested_at.astimezone(UTC):
            raise RuntimeError("fresh OneBot login QR is not available")
        if stat.st_size <= 0 or stat.st_size > self._maximum_qr_bytes:
            raise ValueError("OneBot login QR size is invalid")
        with path.open("rb") as source:
            if source.read(8) != b"\x89PNG\r\n\x1a\n":
                raise ValueError("OneBot login QR format is invalid")
        return path, stat.st_size

    def stream_qr(self, source: Path, output) -> int:
        """在二维码专用上限内传输固定 PNG 文件。"""
        total = 0
        with source.open("rb") as input_stream:
            while chunk := input_stream.read(64 * 1024):
                total += len(chunk)
                if total > self._maximum_qr_bytes:
                    raise ValueError("OneBot login QR exceeds configured maximum")
                output.write(chunk)
        return total

    def _control_account(self, payload: dict) -> Account:
        if not self._enabled:
            raise RuntimeError("OneBot Connector is disabled")
        account_key = str(payload.get("accountKey", ""))
        if not SAFE_KEY.fullmatch(account_key):
            raise ValueError("OneBot control account is invalid")
        account = self._repository.find_by_external_key(account_key)
        if account is None or not account.enabled:
            raise ValueError("OneBot account is unavailable")
        return account

    def _replayed_text_reply(self, reply: TextReply) -> dict:
        if reply.status is TextReplyStatus.SENT and reply.provider_message_id:
            return self._sent_text_reply(reply)
        if reply.status is TextReplyStatus.UNCERTAIN:
            raise RuntimeError(
                "OneBot text delivery outcome is uncertain; manual provider verification is required")
        if reply.status is TextReplyStatus.FAILED:
            raise RuntimeError(
                "OneBot text delivery was not sent; retry limit is exhausted")
        if reply.status is TextReplyStatus.PREPARED:
            raise RuntimeError("OneBot text delivery was not sent; retry later with the same key")
        if reply.updated_at <= self._clock() - self._text_reply_stale_after \
                and reply.attempt_token is not None:
            recovered = self._mark_text_reply_uncertain(reply.id, reply.attempt_token)
            if recovered is not None and recovered.status is TextReplyStatus.SENT \
                    and recovered.provider_message_id:
                return self._sent_text_reply(recovered)
            raise RuntimeError(
                "OneBot text delivery outcome is uncertain; manual provider verification is required")
        raise RuntimeError(
            "OneBot text delivery is in progress; retry with the same key later")

    @staticmethod
    def _sent_text_reply(reply: TextReply) -> dict:
        return {"status": "SENT", "providerMessageId": str(reply.provider_message_id)}

    def _mark_text_reply_uncertain(self, reply_id: UUID,
                                   attempt_token: UUID) -> TextReply | None:
        try:
            return self._repository.mark_text_reply_uncertain(reply_id, attempt_token)
        except Exception:
            # 不记录渠道或消息载荷，只保留内部回复标识用于人工核查。
            LOGGER.exception("Could not persist uncertain OneBot text reply: replyId=%s", reply_id)
            return None

    def _recover_started_text_reply(self, reply_id: UUID,
                                    attempt_token: UUID) -> TextReply | None:
        try:
            reply = self._repository.find_text_reply(reply_id)
        except Exception:
            return None
        if reply is not None and reply.status is TextReplyStatus.IN_FLIGHT \
                and reply.attempt_token == attempt_token:
            return reply
        return None

    def _reschedule_text_reply(self, reply: TextReply, exception: Exception,
                               force_exhausted: bool = False) -> NoReturn:
        if reply.attempt_token is None:
            raise RuntimeError("OneBot text reply attempt token is missing") from exception
        now = self._clock()
        exhausted = force_exhausted or reply.attempt_count >= self._text_reply_max_attempts
        delay = min(2 ** max(0, reply.attempt_count - 1), TEXT_REPLY_MAX_BACKOFF_SECONDS)
        try:
            rescheduled = self._repository.reschedule_text_reply(
                reply.id, reply.attempt_token, now + timedelta(seconds=delay), exhausted, now)
        except Exception as persistence_exception:
            raise RuntimeError(
                "OneBot text delivery was not sent, but retry state could not be persisted; "
                "manual verification is required") from persistence_exception
        if rescheduled.status is TextReplyStatus.FAILED:
            raise RuntimeError(
                "OneBot text delivery was not sent; retry limit is exhausted") from exception
        raise RuntimeError(
            "OneBot text delivery was not sent; retry later with the same key") from exception

    def _authorized_request(self, payload: dict) -> tuple[ProviderFileRequest, Account]:
        if not self._enabled:
            raise RuntimeError("OneBot Connector is disabled")
        if set(payload) != {"channelType", "accountKey", "attachmentType", "providerFileId"} \
                or payload.get("channelType") != "ONEBOT":
            raise ValueError("provider file request is invalid")
        request = ProviderFileRequest(str(payload["accountKey"]), str(payload["attachmentType"]),
                                      str(payload["providerFileId"]))
        if not SAFE_KEY.fullmatch(request.account_key) or request.attachment_type not in ATTACHMENT_TYPES \
                or not request.provider_file_id or len(request.provider_file_id) > 512:
            raise ValueError("provider file request is invalid")
        account = self._repository.find_by_external_key(request.account_key)
        if account is None or not account.enabled:
            raise ValueError("OneBot account is unavailable")
        return request, account


def provider_definitely_not_sent(exception: Exception) -> bool:
    """识别请求未开始或渠道已明确拒绝的安全重试异常。"""
    return isinstance(exception, (ProviderRequestNotStartedError,
                                  ProviderTransientRejectionError))


def validated_account(payload: dict) -> Account:
    """校验账户路由字段并强制使用明确的安全值。"""
    required = {"externalKey", "httpBaseUrl", "secretRef", "hostQqRoot", "containerQqRoot", "enabled"}
    if set(payload) != required:
        raise ValueError("OneBot account fields are invalid")
    external_key = str(payload["externalKey"])
    base_url = str(payload["httpBaseUrl"]).rstrip("/")
    secret_ref = str(payload["secretRef"])
    host_root = str(payload["hostQqRoot"])
    container_root = str(payload["containerQqRoot"])
    parsed = urlparse(base_url)
    if not SAFE_KEY.fullmatch(external_key) or parsed.scheme not in {"http", "https"} \
            or parsed.hostname not in {"127.0.0.1", "::1", "localhost"} \
            or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("OneBot account route is invalid")
    if not re.fullmatch(r"env://[A-Z][A-Z0-9_]{0,127}", secret_ref):
        raise ValueError("OneBot account secret reference is invalid")
    if not isinstance(payload["enabled"], bool):
        raise ValueError("OneBot account enabled flag is invalid")
    if not Path(host_root).is_absolute() or not PurePosixPath(container_root).is_absolute() \
            or len(host_root) > 1024 or len(container_root) > 1024:
        raise ValueError("OneBot account path mapping is invalid")
    return Account.create(external_key, base_url, secret_ref, host_root, container_root,
                          payload["enabled"])
