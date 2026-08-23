"""Shared Storage Gateway client for task scripts."""

from __future__ import annotations

import http.client
import json
from pathlib import Path
import urllib.parse
import urllib.request
from uuid import UUID


class StorageGatewayClient:
    """Stream task inputs and artifacts through managed storage roots."""

    def __init__(self, base_url: str, token: str):
        if not token:
            raise ValueError("Storage Gateway token is missing")
        self._base_url = base_url.rstrip("/")
        self._token = token

    def download(self, storage_uri: str, target: Path, maximum_bytes: int) -> int:
        """Download one managed object into the task work directory with a hard size limit."""
        root_name, relative_path = parse_storage_uri(storage_uri)
        query = urllib.parse.urlencode({"rootName": root_name, "path": relative_path})
        request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/storage/objects/content?{query}", headers=self._headers(None))
        size = 0
        with urllib.request.urlopen(request, timeout=60) as response, target.open("wb") as output:
            while chunk := response.read(64 * 1024):
                size += len(chunk)
                if size > maximum_bytes:
                    raise ValueError("Storage object exceeds task limit")
                output.write(chunk)
        return size

    def download_remote(self, provider_id: str, relative_path: str,
                        target: Path, maximum_bytes: int) -> int:
        """通过服务端 Provider 路由下载一个远端对象并执行双重字节上限。"""
        provider = str(UUID(str(provider_id)))
        path = str(relative_path or "").strip()
        if maximum_bytes <= 0:
            raise ValueError("Remote storage byte limit is invalid")
        if not path or path.startswith("/") or "\\" in path or ".." in path.split("/"):
            raise ValueError("Remote storage path is invalid")
        query = urllib.parse.urlencode({"path": path, "maximumBytes": maximum_bytes})
        request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/storage/providers/{provider}/objects/content?{query}",
            headers=self._headers(None))
        size = 0
        with urllib.request.urlopen(request, timeout=300) as response, target.open("wb") as output:
            while chunk := response.read(64 * 1024):
                size += len(chunk)
                if size > maximum_bytes:
                    raise ValueError("Remote storage object exceeds task limit")
                output.write(chunk)
        return size

    def publish(self, path: Path, root_name: str, relative_path: str,
                idempotency_key: str, size: int, sha256: str) -> str:
        """Create an idempotent upload and stream one artifact."""
        body = json.dumps({"rootName": root_name, "relativePath": relative_path,
                           "expectedSize": size, "expectedSha256": sha256,
                           "idempotencyKey": idempotency_key}, separators=(",", ":")).encode()
        request = urllib.request.Request(f"{self._base_url}/api/internal/v1/storage/uploads", data=body,
                                         method="POST", headers=self._headers("application/json"))
        with urllib.request.urlopen(request, timeout=30) as response:
            upload = json.loads(response.read().decode("utf-8"))
        if upload.get("status") != "SUCCEEDED":
            self._stream(upload["id"], path, size)
        status_request = urllib.request.Request(
            f"{self._base_url}/api/internal/v1/storage/uploads/{upload['id']}", headers=self._headers(None))
        with urllib.request.urlopen(status_request, timeout=30) as response:
            completed = json.loads(response.read().decode("utf-8"))
        if completed.get("status") != "SUCCEEDED" or not completed.get("storageUri"):
            raise RuntimeError("Storage Gateway did not publish artifact")
        return str(completed["storageUri"])

    def _stream(self, upload_id: str, path: Path, size: int) -> None:
        parsed = urllib.parse.urlsplit(self._base_url)
        connection_type = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
        connection = connection_type(parsed.hostname, parsed.port, timeout=60)
        target = parsed.path.rstrip("/") + f"/api/internal/v1/storage/uploads/{upload_id}/content"
        connection.putrequest("PUT", target)
        connection.putheader("Authorization", f"Bearer {self._token}")
        connection.putheader("Content-Type", "application/octet-stream")
        connection.putheader("Content-Length", str(size))
        connection.endheaders()
        with path.open("rb") as source:
            while chunk := source.read(64 * 1024):
                connection.send(chunk)
        response = connection.getresponse()
        response.read()
        connection.close()
        if response.status < 200 or response.status >= 300:
            raise RuntimeError(f"Storage Gateway upload failed: {response.status}")

    def _headers(self, content_type: str | None) -> dict[str, str]:
        headers = {"Authorization": f"Bearer {self._token}", "Accept": "application/json"}
        if content_type:
            headers["Content-Type"] = content_type
        return headers


def parse_storage_uri(value: str) -> tuple[str, str]:
    """Parse a stable managed storage URI without accepting query or fragment components."""
    parsed = urllib.parse.urlsplit(str(value or ""))
    relative_path = parsed.path.lstrip("/")
    if parsed.scheme != "storage" or not parsed.netloc or not relative_path or parsed.query or parsed.fragment:
        raise ValueError("Storage URI is invalid")
    return parsed.netloc, relative_path
