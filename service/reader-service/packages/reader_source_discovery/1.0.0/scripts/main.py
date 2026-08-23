#!/usr/bin/env python3
"""Discover public book-source JSON and persist bounded batches through Reader Service."""

from __future__ import annotations

import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import tempfile
import urllib.error
import urllib.parse
import urllib.request

MAX_BYTES = 20 * 1024 * 1024
MAX_SOURCES = 2000
MAX_REDIRECTS = 3
BATCH_SIZE = 100
SOURCE_PATTERN = re.compile(r"(?i)/yuedu/shuyuans?/(?:content|json)/id/(\d+)\.(?:html|json)")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Keep redirects visible so every target can be validated."""

    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        """Disable urllib automatic redirects."""
        return None


def validate_public_url(value: str) -> str:
    """Reject non-HTTP, credential-bearing, fragmented, and private network URLs."""
    parsed = urllib.parse.urlsplit(str(value or "").strip())
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
        raise ValueError("discovery URL is invalid")
    if parsed.username or parsed.password or parsed.fragment:
        raise ValueError("discovery URL is invalid")
    port = parsed.port or (443 if parsed.scheme.lower() == "https" else 80)
    for address in socket.getaddrinfo(parsed.hostname, port, type=socket.SOCK_STREAM):
        candidate = ipaddress.ip_address(address[4][0])
        if not candidate.is_global:
            raise ValueError("discovery URL is not public")
    return urllib.parse.urlunsplit(parsed)


def fetch(url: str, accept: str, maximum_bytes: int = MAX_BYTES) -> tuple[bytes, str]:
    """Fetch a bounded public resource and revalidate every redirect."""
    opener = urllib.request.build_opener(NoRedirect())
    current = url
    for _ in range(MAX_REDIRECTS + 1):
        current = validate_public_url(current)
        request = urllib.request.Request(current, headers={
            "Accept": accept,
            "User-Agent": "MyTools-Reader-Discovery/1.0",
        })
        try:
            response = opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            if error.code not in {301, 302, 303, 307, 308}:
                raise
            location = error.headers.get("Location")
            if not location:
                raise ValueError("redirect location is missing") from error
            current = urllib.parse.urljoin(current, location)
            continue
        with response:
            content = response.read(maximum_bytes + 1)
            if len(content) > maximum_bytes:
                raise ValueError("discovery response exceeds limit")
            return content, response.headers.get("Content-Type", "")
    raise ValueError("too many redirects")


def valid_source(value: object) -> dict | None:
    """Return a minimally valid source snapshot without rewriting its rules."""
    if not isinstance(value, dict):
        return None
    source_url = value.get("bookSourceUrl")
    source_name = value.get("bookSourceName")
    if not isinstance(source_url, str) or not isinstance(source_name, str):
        return None
    parsed = urllib.parse.urlsplit(source_url)
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname or parsed.username:
        return None
    return value


def parse_sources(payload: bytes) -> tuple[list[dict], int]:
    """Parse one object or array and retain bounded valid source snapshots."""
    root = json.loads(payload.decode("utf-8-sig"))
    values = root if isinstance(root, list) else [root]
    if len(values) > MAX_SOURCES:
        raise ValueError("source repository exceeds limit")
    return [source for value in values if (source := valid_source(value)) is not None], len(values)


def yckceo_json_url(origin: str, identifier: str, collection: bool) -> str:
    """Build the stable yckceo JSON resource URL."""
    parsed = urllib.parse.urlsplit(origin)
    namespace = "shuyuans" if collection else "shuyuan"
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc,
                                    f"/yuedu/{namespace}/json/id/{identifier}.json", "", ""))


def discover(url: str, fetcher=fetch) -> tuple[list[dict], int]:
    """Discover direct JSON or supported yckceo repository pages."""
    parsed = urllib.parse.urlsplit(url)
    path = parsed.path.lower()
    host = (parsed.hostname or "").lower()
    if path.endswith(".json"):
        payload, _ = fetcher(url, "application/json,text/plain;q=0.8", MAX_BYTES)
        return parse_sources(payload)
    if host != "yckceo.com" and not host.endswith(".yckceo.com"):
        raise ValueError("unsupported discovery site")
    direct = SOURCE_PATTERN.fullmatch(parsed.path)
    collection = path.startswith("/yuedu/shuyuans/")
    if direct:
        payload, _ = fetcher(yckceo_json_url(url, direct.group(1), collection),
                             "application/json,text/plain;q=0.8", MAX_BYTES)
        return parse_sources(payload)
    if collection:
        raise ValueError("collection page must identify one source set")
    html, _ = fetcher(url, "text/html,application/xhtml+xml", 512_000)
    identifiers = list(dict.fromkeys(SOURCE_PATTERN.findall(html.decode("utf-8", errors="ignore"))))
    if not identifiers or len(identifiers) > MAX_SOURCES:
        raise ValueError("repository page does not contain bounded source links")
    discovered = []
    processed = 0
    for identifier in identifiers:
        payload, _ = fetcher(yckceo_json_url(url, identifier, False),
                             "application/json,text/plain;q=0.8", MAX_BYTES)
        sources, source_count = parse_sources(payload)
        processed += source_count
        discovered.extend(sources)
        if len(discovered) > MAX_SOURCES:
            raise ValueError("discovered source count exceeds limit")
    return discovered, processed


def save_batch(base_url: str, token: str, request_id: str, sources: list[dict]) -> tuple[int, int]:
    """Persist one source batch through the Reader Service internal API."""
    body = json.dumps({"sources": sources}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/internal/v1/source-discoveries/{request_id}/sources",
        data=body, method="POST", headers={
            "Content-Type": "application/json", "Accept": "application/json",
            "Authorization": f"Bearer {token}",
        })
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.loads(response.read().decode("utf-8"))
    return int(result["saved"]), int(result["rejected"])


def execute(parameters: dict, saver=save_batch, fetcher=fetch) -> dict:
    """Discover and persist all valid source snapshots in bounded batches."""
    request_id = str(parameters["requestId"])
    sources, processed = discover(str(parameters["url"]), fetcher)
    base_url = os.getenv("READER_SERVICE_URL", "http://127.0.0.1:23230")
    token = os.environ.get("READER_INTERNAL_TOKEN", "")
    if not token:
        raise ValueError("Reader Service internal token is missing")
    saved = 0
    rejected = max(0, processed - len(sources))
    for offset in range(0, len(sources), BATCH_SIZE):
        batch_saved, batch_rejected = saver(base_url, token, request_id, sources[offset:offset + BATCH_SIZE])
        saved += batch_saved
        rejected += batch_rejected
    return {"requestId": request_id, "processed": processed, "saved": saved, "rejected": rejected}


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one source discovery task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    write_result(execute(context["parameters"]))


if __name__ == "__main__":
    main()
