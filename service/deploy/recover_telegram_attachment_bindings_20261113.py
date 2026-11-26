#!/usr/bin/env python3
"""验证历史 Telegram 附件的已归档文件并修复孤立绑定，不触发回复。"""
from __future__ import annotations

import argparse
from collections import Counter
from contextlib import ExitStack, closing
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from uuid import UUID

import pymysql


RECOVERY = "telegram-attachment-binding-recovery-20261113-v1"
EVIDENCE_ROOT = Path("/opt/yuyutian/mytools/secure-evidence")
EXPECTED_COUNT = 28
EXPECTED_BYTES = 4_571_399
ORIGINAL_WRAPPER_ERROR = "ATTACHMENT_TASK_FAILED"
ORIGINAL_RECONCILIATION_ERROR = "MESSAGE_JOB_UNBOUND_TERMINAL"


class RefuseRedirect(HTTPRedirectHandler):
    """内部存储验证不得把授权头带到重定向目标。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """拒绝重定向。"""
        return None


def require(condition, code):
    """拒绝已变化或无法证明的恢复范围，只返回稳定错误码。"""
    if not condition:
        raise RuntimeError(code)


def service_environment(service):
    """在远程进程中使用已有服务凭据。"""
    pid = subprocess.check_output(
        ["systemctl", "show", f"mytools-{service}-service", "-p", "MainPID", "--value"],
        text=True,
    ).strip()
    require(pid.isdecimal() and int(pid) > 0, "SERVICE_NOT_RUNNING")
    return dict(
        value.split("=", 1)
        for value in Path(f"/proc/{pid}/environ").read_text().split("\0")
        if "=" in value
    )


def connect(service, prefix, schema):
    """每个数据库只使用对应服务账号。"""
    env = service_environment(service)
    return pymysql.connect(
        host=env.get(f"{prefix}_DB_HOST", "127.0.0.1"),
        port=int(env.get(f"{prefix}_DB_PORT", "3306")),
        user=env.get(f"{prefix}_DB_USER") or env[f"{prefix}_DB_USERNAME"],
        password=env[f"{prefix}_DB_PASSWORD"],
        database=env.get(f"{prefix}_DB_NAME", schema),
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=True,
        read_timeout=15,
    )


def rows(connection, statement, parameters=()):
    """执行参数化查询。"""
    with connection.cursor() as cursor:
        cursor.execute(statement, parameters)
        return cursor.fetchall()


def one(connection, statement, parameters=()):
    """要求同一身份只对应一条记录。"""
    result = rows(connection, statement, parameters)
    require(len(result) == 1, "IDENTITY_NOT_UNIQUE")
    return result[0]


def as_uuid(value):
    """拒绝非标准的关联标识。"""
    require(isinstance(value, str) and str(UUID(value)) == value, "IDENTITY_NOT_UUID")
    return value


def utc_native(value):
    """按数据库字段精度保存时间快照，用于比较并保留原值。"""
    return value.isoformat(sep=" ", timespec="microseconds")


def verify_stored_bytes(storage_env, storage_uri, expected_size, expected_sha):
    """从受控存储接口流式校验文件，不保存内容。"""
    location = urlsplit(storage_uri)
    relative_path = location.path.lstrip("/")
    require(
        location.scheme == "storage"
        and re.fullmatch(r"[A-Za-z0-9_-]+", location.netloc)
        and relative_path
        and not location.query
        and not location.fragment
        and "\\" not in relative_path
        and all(part not in {"", ".", ".."} for part in relative_path.split("/")),
        "INVALID_STORAGE_LOCATION",
    )
    port = int(storage_env.get("STORAGE_HTTP_PORT", "23240"))
    query = urlencode({"rootName": location.netloc, "path": relative_path})
    token = storage_env["STORAGE_INTERNAL_TOKEN"]
    require(bool(token), "STORAGE_TOKEN_MISSING")
    request = Request(
        f"http://127.0.0.1:{port}/api/internal/v1/storage/objects/content?{query}",
        headers={"Authorization": f"Bearer {token}"},
    )
    digest = hashlib.sha256()
    size = 0
    with build_opener(ProxyHandler({}), RefuseRedirect()).open(request, timeout=12) as response:
        require(
            response.status == 200 and int(response.headers.get("Content-Length", "-1")) == expected_size,
            "STORAGE_LENGTH_MISMATCH",
        )
        while chunk := response.read(64 * 1024):
            size += len(chunk)
            require(size <= expected_size, "STORAGE_OBJECT_TOO_LARGE")
            digest.update(chunk)
    require(size == expected_size and digest.hexdigest() == expected_sha, "STORAGE_DIGEST_MISMATCH")


def read_attachment_evidence(databases, storage_env, request_id, previous=None):
    """核对消息、任务、下载结果、资产以及历史回执，生成最小备份。"""
    download, messaging, scheduler, assets, automation = databases
    request = one(
        download,
        """SELECT r.id,r.owner_id,r.source_type,r.source_key,r.request_kind,r.idempotency_key,
                  r.status,r.parameters_json,r.task_instance_id,b.id background_id,b.state,
                  b.last_error_code background_error,b.attempts,b.updated_at background_updated,
                  b.claim_token IS NULL AND b.claim_until IS NULL background_idle
           FROM download_request r JOIN download_background_job b ON b.download_request_id=r.id
           WHERE r.id=%s AND b.kind=%s""",
        (request_id, "RECONCILE"),
    )
    require(
        request["source_type"] == "MESSAGE_ATTACHMENT"
        and request["request_kind"] == "MESSAGE_ATTACHMENT"
        and request["status"] == "SUCCEEDED"
        and request["owner_id"] > 0
        and request["background_id"] == f"request:{request_id}"
        and request["background_idle"],
        "UNEXPECTED_DOWNLOAD_STATE",
    )
    part_id, job_id = map(as_uuid, request["source_key"].split(":"))
    parameters = json.loads(request["parameters_json"])
    owner_id = request["owner_id"]
    require(
        parameters.get("ownerId") == owner_id
        and parameters.get("itemId") == part_id
        and parameters.get("attachmentJobId") == job_id
        and re.fullmatch(rf"message_attachment:{job_id}:v[0-9]+", request["idempotency_key"]),
        "DOWNLOAD_IDENTITY_MISMATCH",
    )
    job = one(
        messaging,
        """SELECT j.inbound_message_id,j.message_part_id,j.status,j.last_error_code,j.updated_at,
                  j.resolution_mode,j.task_instance_id,j.download_request_id,m.owner_id,m.channel_type,p.part_type
           FROM attachment_download_job j JOIN inbound_message m ON m.id=j.inbound_message_id
           JOIN inbound_message_part p ON p.id=j.message_part_id AND p.inbound_message_id=m.id
           WHERE j.id=%s""",
        (job_id,),
    )
    require(
        job["owner_id"] == owner_id
        and job["channel_type"] == "TELEGRAM"
        and job["message_part_id"] == part_id
        and job["part_type"] == "ATTACHMENT"
        and job["resolution_mode"] == "STREAM",
        "MESSAGING_IDENTITY_MISMATCH",
    )
    require(
        not rows(
            messaging,
            "SELECT id FROM attachment_download_job WHERE download_request_id=%s AND id<>%s",
            (request_id, job_id),
        ),
        "DOWNLOAD_ALREADY_BOUND_ELSEWHERE",
    )

    # 调度历史仅用于核对，不作为修复目标。
    for task_id, task_name, business_type, business_id, state, key, identities in (
        (job["task_instance_id"], "message_download_attachment", "MESSAGE_ATTACHMENT", job_id,
         "FAILED", f"message_attachment_download:{job_id}:v1", {"attachmentJobId": job_id}),
        (request["task_instance_id"], "download_message_attachment", "DOWNLOAD_REQUEST", request_id,
         "SUCCEEDED", f"download:{request['idempotency_key']}",
         {"ownerId": owner_id, "itemId": part_id, "attachmentJobId": job_id, "downloadRequestId": request_id}),
    ):
        task = one(
            scheduler,
            """SELECT task_name,business_type,business_id,status,idempotency_key,parameters_json
               FROM task_instance WHERE id=%s""",
            (as_uuid(task_id),),
        )
        task_parameters = json.loads(task["parameters_json"])
        require(
            (task["task_name"], task["business_type"], task["business_id"], task["status"], task["idempotency_key"])
            == (task_name, business_type, business_id, state, key)
            and all(task_parameters.get(name) == value for name, value in identities.items()),
            "SCHEDULER_IDENTITY_MISMATCH",
        )

    item = one(
        download,
        """SELECT id,external_item_id,status,tag_status,asset_id,size_bytes,content_sha256,storage_uri
           FROM download_item WHERE download_request_id=%s""",
        (request_id,),
    )
    require(
        item["external_item_id"] == part_id
        and item["status"] == "COMPLETED"
        and item["tag_status"] == "TAGGED"
        and isinstance(item["size_bytes"], int)
        and 0 < item["size_bytes"] <= EXPECTED_BYTES
        and re.fullmatch(r"[0-9a-f]{64}", item["content_sha256"] or ""),
        "DOWNLOAD_FILE_NOT_VERIFIED",
    )
    asset_id = as_uuid(item["asset_id"])
    one(
        assets,
        """SELECT a.id FROM asset a JOIN asset_source s ON s.asset_id=a.id
           WHERE a.id=%s AND a.status=%s AND a.size_bytes=%s AND a.content_sha256=%s
             AND s.owner_id=%s AND s.source_type=%s AND s.source_business_id IN (%s,%s)""",
        (asset_id, "ACTIVE", item["size_bytes"], item["content_sha256"], owner_id, "DOWNLOAD",
         request_id, f"{request_id}:{part_id}"),
    )
    exact_location = one(
        assets,
        """SELECT storage_uri FROM asset_location
           WHERE asset_id=%s AND storage_uri=%s AND availability=%s""",
        (asset_id, item["storage_uri"], "AVAILABLE"),
    )
    require(exact_location["storage_uri"] == item["storage_uri"], "ASSET_LOCATION_MISMATCH")
    verify_stored_bytes(storage_env, item["storage_uri"], item["size_bytes"], item["content_sha256"])

    action = one(
        automation,
        """SELECT a.id,a.status,a.source_url,r.id run_id,r.status run_status,r.inbound_message_id
           FROM automation_action a JOIN automation_run r ON r.id=a.automation_run_id
           WHERE a.external_request_id=%s AND a.action_type=%s""",
        (job_id, "ATTACHMENT_DOWNLOAD"),
    )
    require(
        action["source_url"] == part_id
        and action["inbound_message_id"] == job["inbound_message_id"]
        and action["status"] == "FAILED"
        and action["run_status"] in {"FAILED", "PARTIAL_FAILED"},
        "AUTOMATION_IDENTITY_MISMATCH",
    )
    completion = one(
        automation,
        """SELECT id,published_at,dead_at,delivery_page_cursor FROM automation_outbox
           WHERE aggregate_id=%s AND event_type=%s""",
        (action["run_id"], "AutomationRunCompleted"),
    )
    require(
        completion["published_at"] is not None
        and completion["dead_at"] is None
        and completion["delivery_page_cursor"] == 1,
        "COMPLETION_HISTORY_CHANGED",
    )
    identity = {
        "requestId": request_id,
        "downloadTaskId": request["task_instance_id"],
        "backgroundId": request["background_id"],
        "attachmentJobId": job_id,
        "wrapperTaskId": job["task_instance_id"],
        "ownerId": owner_id,
        "partId": part_id,
        "messageId": as_uuid(job["inbound_message_id"]),
        "itemId": as_uuid(item["id"]),
        "assetId": asset_id,
        "sizeBytes": item["size_bytes"],
        "contentSha256": item["content_sha256"],
        "actionId": action["id"],
        "runId": action["run_id"],
        "runStatus": action["run_status"],
        "completionEventId": completion["id"],
        "completionPublishedAt": utc_native(completion["published_at"]),
    }
    before_messaging = {
        "status": job["status"],
        "downloadRequestId": job["download_request_id"],
        "lastErrorCode": job["last_error_code"],
        "updatedAt": utc_native(job["updated_at"]),
    }
    before_download = {
        "state": request["state"],
        "lastErrorCode": request["background_error"],
        "attempts": request["attempts"],
        "updatedAt": utc_native(request["background_updated"]),
    }
    messaging_done = (
        job["status"] == "SUCCEEDED"
        and job["last_error_code"] is None
        and job["download_request_id"] == request_id
    )
    download_done = request["state"] == "SUCCEEDED" and request["background_error"] is None
    if previous is None:
        require(
            (job["status"], job["last_error_code"], job["download_request_id"])
            == ("FAILED", ORIGINAL_WRAPPER_ERROR, None)
            and (request["state"], request["background_error"])
            == ("FAILED", ORIGINAL_RECONCILIATION_ERROR),
            "SOURCE_STATE_CHANGED",
        )
        previous = {"identity": identity, "messaging": before_messaging, "download": before_download}
    else:
        require(
            previous["identity"] == identity
            and (before_messaging == previous["messaging"] or messaging_done)
            and (before_download == previous["download"] or download_done)
            and (not download_done or messaging_done)
            and before_download["attempts"] == previous["download"]["attempts"],
            "RECOVERY_STATE_CHANGED",
        )
    return previous, messaging_done, download_done


def canonical_bytes(value):
    """以稳定序列化表示生成审批校验摘要。"""
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode()


def protected_directory(path, permissions, create=False):
    """核对证据目录所有者和权限，拒绝软链接和可写共享目录。"""
    if create:
        path.mkdir(mode=permissions, exist_ok=True)
    st = path.lstat()
    require(
        stat.S_ISDIR(st.st_mode)
        and st.st_uid == os.geteuid()
        and not stat.S_IMODE(st.st_mode) & ~permissions,
        "UNSAFE_EVIDENCE_DIRECTORY",
    )


def protected_json(path, value):
    """持久化备份后再公开文件名，且不会覆盖已有证据。"""
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(canonical_bytes(value) + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
            os.link(temporary, path)
            directory_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            temporary.unlink()


def load_existing_plan(journal):
    """读取受保护且可重入的原始恢复计划。"""
    if not journal.exists():
        return None
    protected_directory(journal.parent, 0o700)
    with os.fdopen(os.open(journal, os.O_RDONLY | os.O_NOFOLLOW), "rb") as stream:
        st = os.fstat(stream.fileno())
        require(stat.S_ISREG(st.st_mode) and st.st_uid == os.geteuid()
                and stat.S_IMODE(st.st_mode) == 0o600 and st.st_size < 256 * 1024,
                "UNSAFE_EVIDENCE_FILE")
        plan = json.load(stream)
    require(plan.get("recovery") == RECOVERY, "WRONG_RECOVERY_JOURNAL")
    return plan


def apply_messaging(messaging, plan_states):
    """按原始终态和更新时间修复已验证的附件投影。"""
    count = 0
    messaging.begin()
    try:
        with messaging.cursor() as cursor:
            for entry, messaging_done, _ in plan_states:
                if messaging_done:
                    continue
                keys, before = entry["identity"], entry["messaging"]
                cursor.execute(
                    """UPDATE attachment_download_job j
                       JOIN inbound_message m ON m.id=j.inbound_message_id
                       JOIN inbound_message_part p ON p.id=j.message_part_id AND p.inbound_message_id=m.id
                       SET j.download_request_id=%s,j.status=%s,j.last_error_code=NULL,
                           j.updated_at=CURRENT_TIMESTAMP(6)
                       WHERE j.id=%s AND j.inbound_message_id=%s AND j.message_part_id=%s
                         AND j.task_instance_id=%s AND j.status=%s AND j.last_error_code=%s
                         AND j.updated_at=%s AND j.download_request_id IS NULL
                         AND j.resolution_mode=%s AND m.owner_id=%s AND m.channel_type=%s AND p.part_type=%s""",
                    (keys["requestId"], "SUCCEEDED", keys["attachmentJobId"], keys["messageId"],
                     keys["partId"], keys["wrapperTaskId"], before["status"], before["lastErrorCode"],
                     before["updatedAt"], "STREAM", keys["ownerId"], "TELEGRAM", "ATTACHMENT"),
                )
                require(cursor.rowcount == 1, "MESSAGING_COMPARE_AND_SET_FAILED")
                count += 1
        messaging.commit()
    except Exception:
        messaging.rollback()
        raise
    return count


def apply_download(download, plan_states):
    """只完成已验证的后台绑定对账，保留尝试次数和原下载终态。"""
    count = 0
    download.begin()
    try:
        with download.cursor() as cursor:
            for entry, _, download_done in plan_states:
                if download_done:
                    continue
                keys, before = entry["identity"], entry["download"]
                cursor.execute(
                    """UPDATE download_background_job b JOIN download_request r ON r.id=b.download_request_id
                       JOIN download_item i ON i.download_request_id=r.id AND i.id=%s
                       SET b.state=%s,b.last_error_code=NULL,b.updated_at=UTC_TIMESTAMP(6)
                       WHERE b.id=%s AND b.kind=%s AND b.state=%s AND b.last_error_code=%s
                         AND b.attempts=%s AND b.updated_at=%s AND b.claim_token IS NULL AND b.claim_until IS NULL
                         AND r.id=%s AND r.owner_id=%s AND r.source_type=%s AND r.source_key=%s
                         AND r.request_kind=%s AND r.task_instance_id=%s AND r.status=%s
                         AND i.status=%s AND i.tag_status=%s AND i.external_item_id=%s
                         AND i.asset_id=%s AND i.content_sha256=%s AND i.size_bytes=%s""",
                    (keys["itemId"], "SUCCEEDED", keys["backgroundId"], "RECONCILE", before["state"],
                     before["lastErrorCode"], before["attempts"], before["updatedAt"], keys["requestId"],
                     keys["ownerId"], "MESSAGE_ATTACHMENT", f"{keys['partId']}:{keys['attachmentJobId']}",
                     "MESSAGE_ATTACHMENT", keys["downloadTaskId"], "SUCCEEDED", "COMPLETED", "TAGGED",
                     keys["partId"], keys["assetId"], keys["contentSha256"], keys["sizeBytes"]),
                )
                require(cursor.rowcount == 1, "DOWNLOAD_COMPARE_AND_SET_FAILED")
                count += 1
        download.commit()
    except Exception:
        download.rollback()
        raise
    return count


def main():
    """默认只读演练，显式确认计划摘要后才允许应用。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--expect-plan-sha256")
    options = parser.parse_args()
    require(not options.apply or bool(options.expect_plan_sha256), "EXPECTED_PLAN_DIGEST_REQUIRED")
    os.umask(0o077)
    journal = EVIDENCE_ROOT / RECOVERY / "plan.json"
    with ExitStack() as stack:
        database_specs = (
            ("download-ingestion", "DOWNLOAD", "mytools_download"),
            ("messaging", "MESSAGING", "mytools_messaging"),
            ("task-scheduler", "TASK", "mytools_task"),
            ("asset-registry", "ASSET", "mytools_asset"),
            ("message-automation", "MESSAGE_AUTOMATION", "mytools_message_automation"),
        )
        databases = [stack.enter_context(closing(connect(*spec))) for spec in database_specs]
        download, messaging, _, _, _ = databases
        storage_env = service_environment("storage-gateway")
        saved = load_existing_plan(journal)
        if saved is None:
            candidates = rows(
                download,
                """SELECT r.id FROM download_background_job b JOIN download_request r ON r.id=b.download_request_id
                   WHERE b.kind=%s AND b.state=%s AND b.last_error_code=%s
                     AND r.source_type=%s AND r.request_kind=%s AND r.status=%s
                     AND r.created_at >= %s AND r.created_at < %s
                   ORDER BY r.id""",
                ("RECONCILE", "FAILED", ORIGINAL_RECONCILIATION_ERROR, "MESSAGE_ATTACHMENT",
                 "MESSAGE_ATTACHMENT", "SUCCEEDED", "2026-09-29 00:00:00", "2026-10-06 00:00:00"),
            )
            require(len(candidates) == EXPECTED_COUNT, "UNEXPECTED_CANDIDATE_COUNT")
            plan_states = [
                read_attachment_evidence(databases, storage_env, as_uuid(candidate["id"]))
                for candidate in candidates
            ]
            plan = {"recovery": RECOVERY, "entries": [entry for entry, _, _ in plan_states]}
        else:
            require(len(saved.get("entries", ())) == EXPECTED_COUNT, "UNEXPECTED_CANDIDATE_COUNT")
            plan = saved
            plan_states = [
                read_attachment_evidence(databases, storage_env, as_uuid(entry["identity"]["requestId"]), entry)
                for entry in plan["entries"]
            ]
        identity = [entry["identity"] for entry, _, _ in plan_states]
        require(
            all(len({item[key] for item in identity}) == EXPECTED_COUNT
                for key in ("requestId", "attachmentJobId", "partId", "itemId", "actionId"))
            and sum(item["sizeBytes"] for item in identity) == EXPECTED_BYTES
            and len({item["runId"] for item in identity}) == 4,
            "UNEXPECTED_VERIFIED_SCOPE",
        )
        digest = hashlib.sha256(canonical_bytes(plan)).hexdigest()
        if options.expect_plan_sha256:
            require(digest == options.expect_plan_sha256, "PLAN_DIGEST_MISMATCH")
        result = {
            "recovery": RECOVERY,
            "mode": "apply" if options.apply else "dry-run",
            "planSha256": digest,
            "verifiedFiles": len(identity),
            "verifiedBytes": sum(item["sizeBytes"] for item in identity),
            "automationRuns": len({item["runId"] for item in identity}),
            "publishedCompletions": len({item["completionEventId"] for item in identity}),
            "originalRunStatuses": dict(Counter(
                {item["runId"]: item["runStatus"] for item in identity}.values())),
            "alreadyBound": sum(messaging_done for _, messaging_done, _ in plan_states),
            "alreadyReconciled": sum(download_done for _, _, download_done in plan_states),
        }
        if options.apply:
            protected_directory(EVIDENCE_ROOT, 0o755)
            protected_directory(journal.parent, 0o700, create=True)
            if saved is None:
                protected_json(journal, plan)
            result["messagingUpdates"] = apply_messaging(messaging, plan_states)
            # 第一库提交后复核已绑定的身份，再推进第二库，崩溃时依据同一备份重入。
            for entry in plan["entries"]:
                keys = entry["identity"]
                one(
                    messaging,
                    """SELECT id FROM attachment_download_job
                       WHERE id=%s AND download_request_id=%s AND status=%s AND last_error_code IS NULL""",
                    (keys["attachmentJobId"], keys["requestId"], "SUCCEEDED"),
                )
            result["reconciliationUpdates"] = apply_download(download, plan_states)
            for entry in plan["entries"]:
                keys = entry["identity"]
                one(
                    download,
                    """SELECT id FROM download_background_job
                       WHERE id=%s AND download_request_id=%s AND kind=%s AND state=%s AND last_error_code IS NULL""",
                    (keys["backgroundId"], keys["requestId"], "RECONCILE", "SUCCEEDED"),
                )
            result["backup"] = str(journal)
        print(json.dumps(result, ensure_ascii=True, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        # 禁止异常栈把内部地址、凭据或文件路径回显到调用端。
        code = str(exception)
        safe_code = code if re.fullmatch(r"[A-Z][A-Z0-9_]{2,80}", code) else type(exception).__name__
        raise SystemExit(safe_code) from None
