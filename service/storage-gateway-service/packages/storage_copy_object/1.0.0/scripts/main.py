#!/usr/bin/env python3
"""复制一个由 Storage Gateway 服务端定义的原生远端对象。"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import UUID

CHUNK_BYTES = 1024 * 1024


class StorageClient:
    """仅调用操作专属的来源、目标和终态端点。"""

    def __init__(self, base_url: str, token: str, opener=urlopen):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.opener = opener

    def download(self, operation_id: str, role: str, target: Path, maximum_bytes: int) -> tuple[int, str]:
        """有界下载来源或目标并计算摘要。"""
        request = Request(self._url(operation_id, role),
                          headers={"Authorization": f"Bearer {self.token}"})
        digest = hashlib.sha256()
        total = 0
        with self.opener(request, timeout=3600) as response, target.open("wb") as output:
            declared = int(response.headers.get("Content-Length", "-1"))
            if declared < 0 or declared > maximum_bytes:
                raise RuntimeError("Storage object length is invalid")
            while chunk := response.read(CHUNK_BYTES):
                total += len(chunk)
                if total > maximum_bytes:
                    raise RuntimeError("Storage object exceeds maximumBytes")
                digest.update(chunk)
                output.write(chunk)
        if total != declared:
            raise RuntimeError("Storage object length changed during transfer")
        return total, digest.hexdigest()

    def upload(self, operation_id: str, source: Path, content_length: int, sha256: str) -> dict:
        """向操作定义的目标上传精确长度和摘要。"""
        query = urlencode({"contentLength": content_length, "sha256": sha256})
        with source.open("rb") as content:
            request = Request(self._url(operation_id, "target") + "?" + query, data=content, method="PUT",
                              headers={"Authorization": f"Bearer {self.token}",
                                       "Content-Type": "application/octet-stream",
                                       "Content-Length": str(content_length)})
            with self.opener(request, timeout=3600) as response:
                return json.loads(response.read().decode("utf-8"))

    def delete_target(self, operation_id: str) -> None:
        """幂等补偿删除目标对象。"""
        request = Request(self._url(operation_id, "target"), method="DELETE",
                          headers={"Authorization": f"Bearer {self.token}"})
        with self.opener(request, timeout=60):
            pass

    def _url(self, operation_id: str, role: str) -> str:
        return self.base_url + f"/api/internal/v1/storage/operations/{operation_id}/native-copy/{role}"


def execute(parameters: dict, client: StorageClient, workspace: Path,
            maximum_bytes: int = 20 * 1024 * 1024 * 1024) -> dict:
    """下载、写入、复读校验并完成一个不透明复制操作。"""
    operation_id = str(UUID(str(parameters["operationId"])))
    source = workspace / "source.bin"
    target = workspace / "target.bin"
    content_length, source_sha256 = client.download(operation_id, "source", source, maximum_bytes)
    write_started = False
    try:
        write_started = True
        written = client.upload(operation_id, source, content_length, source_sha256)
        if int(written.get("contentLength", -1)) != content_length \
                or written.get("sha256") != source_sha256:
            raise RuntimeError("Storage target write checkpoint mismatch")
        target_length, target_sha256 = client.download(operation_id, "target", target, maximum_bytes)
        if target_length != content_length or target_sha256 != source_sha256:
            raise RuntimeError("Storage target verification mismatch")
        return {"operationId": operation_id, "contentLength": content_length,
                "sha256": source_sha256, "status": "SUCCEEDED"}
    except Exception:
        if write_started:
            try:
                client.delete_target(operation_id)
            except Exception:
                pass
        raise


def write_result(result: dict) -> None:
    """原子写入任务结果。"""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """执行一个原生对象复制任务。"""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = StorageClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                           os.getenv("STORAGE_INTERNAL_TOKEN", ""))
    maximum = int(os.getenv("STORAGE_NATIVE_COPY_MAXIMUM_BYTES", str(20 * 1024 * 1024 * 1024)))
    write_result(execute(context["parameters"], client, Path(os.environ["TASK_WORK_DIR"]), maximum))


if __name__ == "__main__":
    main()
