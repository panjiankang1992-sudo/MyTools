#!/usr/bin/env python3
"""Download one HTTP asset with bounded streaming and atomic publication."""

from __future__ import annotations

import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import tempfile
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener, urlopen

CHUNK_BYTES = 1024 * 1024
DEFAULT_MAX_BYTES = 2 * 1024 * 1024 * 1024
MAX_CONFIGURED_BYTES = 20 * 1024 * 1024 * 1024
SAFE_NAME = re.compile(r"^[^/\\\x00]{1,255}$")
TRUSTED_HOST_SUFFIXES = {".twimg.com"}


def validated_proxy(value: object) -> str | None:
    """Allow an optional trusted loopback HTTP proxy for restricted networks."""
    proxy = str(value or "").strip()
    if not proxy:
        return None
    parsed = urlparse(proxy)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"} \
            or parsed.port is None or parsed.username or parsed.password \
            or parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
        raise ValueError("DOWNLOAD_HTTP_PROXY must be a loopback HTTP proxy")
    return proxy.rstrip("/")


def validated_name(value: object) -> str:
    """Reject path traversal and invalid destination file names."""
    name = str(value or "").strip()
    if not SAFE_NAME.fullmatch(name) or name in {".", ".."}:
        raise ValueError("fileName is invalid")
    return name


def trusted_host_suffix(value: object) -> str | None:
    """只允许任务声明由可信解析器生成的固定媒体域后缀。"""
    suffix = str(value or "").strip().lower()
    if not suffix:
        return None
    if suffix not in TRUSTED_HOST_SUFFIXES:
        raise ValueError("trustedHostSuffix is not allowed")
    return suffix


def validated_url(value: object, resolver=socket.getaddrinfo,
                  trusted_suffix: str | None = None) -> str:
    """Allow only absolute HTTP URLs resolving exclusively to public addresses."""
    url = str(value or "").strip()
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("url must be absolute HTTP or HTTPS")
    hostname = parsed.hostname.lower()
    if trusted_suffix is not None:
        if parsed.scheme != "https" or not hostname.endswith(trusted_suffix):
            raise ValueError("url host does not match trustedHostSuffix")
        return url
    try:
        addresses = resolver(hostname, parsed.port or (443 if parsed.scheme == "https" else 80),
                             type=socket.SOCK_STREAM)
    except OSError as exception:
        raise ValueError("url host cannot be resolved") from exception
    if not addresses:
        raise ValueError("url host cannot be resolved")
    for address in addresses:
        ip = ipaddress.ip_address(address[4][0])
        if not ip.is_global:
            raise ValueError("url host resolves to a non-public address")
    return url


class SafeRedirectHandler(HTTPRedirectHandler):
    """Revalidate every redirect target before urllib follows it."""

    def __init__(self, resolver, trusted_suffix=None):
        self._resolver = resolver
        self._trusted_suffix = trusted_suffix
        super().__init__()

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """Reject redirects that leave the public HTTP boundary."""
        validated_url(newurl, self._resolver, self._trusted_suffix)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def byte_limit(parameters: dict) -> int:
    """Resolve a positive bounded download size limit."""
    limit = int(parameters.get("maxBytes", DEFAULT_MAX_BYTES))
    if limit <= 0 or limit > MAX_CONFIGURED_BYTES:
        raise ValueError("maxBytes is outside the supported range")
    return limit


def stream_download(parameters: dict, destination_root: Path, opener=None,
                    resolver=socket.getaddrinfo, progress_reporter=None) -> dict:
    """Stream an asset to staging, validate it, and atomically publish it."""
    request_id = str(parameters["downloadRequestId"])
    item_id = str(parameters["itemId"])
    file_name = validated_name(parameters["fileName"])
    suffix = trusted_host_suffix(parameters.get("trustedHostSuffix"))
    url = validated_url(parameters["url"], resolver, suffix)
    limit = byte_limit(parameters)
    target_dir = destination_root / request_id
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / file_name
    digest = hashlib.sha256()
    size = 0
    temporary_path: Path | None = None
    request = Request(url, headers={"User-Agent": "MyTools-Download-Executor/1.0"})
    proxy = validated_proxy(os.environ.get("DOWNLOAD_HTTP_PROXY"))
    handlers = [SafeRedirectHandler(resolver, suffix)]
    if proxy is not None:
        handlers.insert(0, ProxyHandler({"http": proxy, "https": proxy}))
    request_opener = opener or build_opener(*handlers).open
    try:
        with request_opener(request, timeout=30) as response:
            declared = response.headers.get("Content-Length")
            if declared is not None and int(declared) > limit:
                raise ValueError("declared content length exceeds maxBytes")
            total = int(declared) if declared is not None else 0
            next_percent = 0
            if total > 10 * 1024 * 1024 and progress_reporter is not None:
                progress_reporter(request_id, item_id, 0, total, 0)
                next_percent = 5
            with tempfile.NamedTemporaryFile("wb", dir=target_dir, delete=False) as handle:
                temporary_path = Path(handle.name)
                while chunk := response.read(CHUNK_BYTES):
                    size += len(chunk)
                    if size > limit:
                        raise ValueError("download exceeds maxBytes")
                    digest.update(chunk)
                    handle.write(chunk)
                    percent = min(100, size * 100 // total) if total else 0
                    while next_percent and percent >= next_percent:
                        progress_reporter(request_id, item_id, size, total, next_percent)
                        next_percent += 5
        content_sha256 = digest.hexdigest()
        expected = str(parameters.get("expectedSha256") or "").lower()
        if expected and expected != content_sha256:
            raise ValueError("download checksum mismatch")
        temporary_path.replace(target)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    return {
        "requestId": request_id,
        "itemId": item_id,
        "fileName": file_name,
        "relativePath": f"{request_id}/{file_name}",
        "sizeBytes": size,
        "contentSha256": content_sha256,
    }


def report_progress(request_id: str, item_id: str, downloaded: int, total: int,
                    percent: int) -> None:
    """向 Download Ingestion 持久化一个进度里程碑。"""
    base = os.environ.get("DOWNLOAD_INGESTION_URL", "http://127.0.0.1:23220").rstrip("/")
    token = os.environ.get("DOWNLOAD_INTERNAL_TOKEN", "")
    payload = json.dumps({"itemId": item_id, "downloadedBytes": downloaded,
                          "totalBytes": total, "progressPercent": percent}).encode()
    request = Request(f"{base}/internal/v1/download-requests/{request_id}/progress",
                      data=payload, method="POST", headers={"Content-Type": "application/json",
                                                            "Authorization": f"Bearer {token}"})
    with urlopen(request, timeout=10):
        pass


def write_result(result: dict) -> None:
    """Atomically write the executor result document."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one HTTP asset download task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    destination_root = Path(os.environ["DOWNLOAD_DESTINATION_ROOT"]).resolve()
    destination_root.mkdir(parents=True, exist_ok=True)
    write_result(stream_download(context["parameters"], destination_root,
                                 progress_reporter=report_progress))


if __name__ == "__main__":
    main()
