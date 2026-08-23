#!/usr/bin/env python3
"""比较旧 DownloadBot 与新 Download Ingestion 的内容结果。"""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen
from uuid import UUID


class Client:
    """读取旧证据和新下载结果摘要。"""

    def __init__(self, adapter_url: str, adapter_token: str, ingestion_url: str,
                 ingestion_token: str, opener=urlopen):
        if not adapter_token or not ingestion_token:
            raise ValueError("download reconciliation tokens are missing")
        self.adapter_url = adapter_url.rstrip("/")
        self.adapter_token = adapter_token
        self.ingestion_url = ingestion_url.rstrip("/")
        self.ingestion_token = ingestion_token
        self.opener = opener

    def legacy(self, snapshot_id: str, event_id: str) -> dict:
        """读取一个事件在封存快照中的旧内容证据。"""
        url = (self.adapter_url + "/internal/v1/reconciliation/downloadbot/events/"
               + quote(event_id, safe="") + "?" + urlencode({"snapshotId": snapshot_id}))
        return self._get(url, self.adapter_token)

    def current(self, request_id: str) -> dict:
        """读取新下载请求的稳定内容摘要。"""
        return self._get(self.ingestion_url + "/api/v1/download-requests/"
                         + quote(request_id, safe="") + "/result-summary", self.ingestion_token)

    def _get(self, url: str, token: str) -> dict:
        request = Request(url, headers={"Authorization": f"Bearer {token}",
                                        "Accept": "application/json"})
        with self.opener(request, timeout=30) as response:
            body = response.read()
        if len(body) > 4 * 1024 * 1024:
            raise RuntimeError("download reconciliation response is too large")
        value = json.loads(body.decode())
        if not isinstance(value, dict):
            raise RuntimeError("download reconciliation response is invalid")
        return value


def execute(client: Client, snapshot_id: str, event_id: str) -> dict:
    """比较状态、文件数、字节数和执行标识无关的内容摘要。"""
    UUID(snapshot_id)
    if not event_id or len(event_id) > 255:
        raise ValueError("event id is invalid")
    legacy = client.legacy(snapshot_id, event_id)
    if legacy.get("snapshotId") != snapshot_id or legacy.get("eventId") != event_id:
        raise RuntimeError("legacy reconciliation identity does not match")
    request_id = str(legacy.get("downloadRequestId") or "")
    UUID(request_id)
    current = client.current(request_id)
    if str(current.get("downloadRequestId")) != request_id:
        raise RuntimeError("current reconciliation identity does not match")
    reasons = []
    if str(legacy.get("legacyStatus")) != "COMPLETED" or str(current.get("status")) != "SUCCEEDED":
        reasons.append("STATUS_MISMATCH")
    if int(legacy.get("itemCount", -1)) != int(current.get("itemCount", -2)):
        reasons.append("ITEM_COUNT_MISMATCH")
    if int(legacy.get("totalBytes", -1)) != int(current.get("totalBytes", -2)):
        reasons.append("TOTAL_BYTES_MISMATCH")
    if str(legacy.get("contentSetSha256")) != str(current.get("contentSetSha256")):
        reasons.append("CONTENT_SET_MISMATCH")
    legacy_result = {key: legacy[key] for key in
                     ("legacyJobId", "legacyStatus", "itemCount", "totalBytes", "contentSetSha256")}
    current_result = {key: current[key] for key in
                      ("status", "itemCount", "totalBytes", "contentSetSha256")}
    return {"sourceSnapshotId": snapshot_id, "eventId": event_id,
            "downloadRequestId": request_id, "matched": not reasons,
            "mismatchReasons": reasons, "legacy": legacy_result, "current": current_result}


def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent,
                                     delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行一次显式的新旧下载结果对账。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("DOWNLOADBOT_ADAPTER_URL", "http://127.0.0.1:23221"),
                    os.getenv("DOWNLOADBOT_SNAPSHOT_EXPORT_TOKEN", ""),
                    os.getenv("DOWNLOAD_INGESTION_URL", "http://127.0.0.1:23220"),
                    os.getenv("DOWNLOAD_INGESTION_TOKEN", ""))
    write_result(execute(client, str(parameters["sourceSnapshotId"]),
                         str(parameters["eventId"])))


if __name__ == "__main__":
    main()
