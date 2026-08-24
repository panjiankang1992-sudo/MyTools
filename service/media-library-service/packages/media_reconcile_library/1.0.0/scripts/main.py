#!/usr/bin/env python3
"""Build a deterministic Media Library reconciliation report."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import struct
import tempfile
import urllib.parse
import urllib.request

COUNT_FIELDS = ("itemCount", "sourceRelationCount", "sourceTagRelationCount",
                "readyCount", "missingCount", "analyzingCount",
                "succeededAnalysisCount", "failedAnalysisCount", "runningAnalysisCount",
                "tagRelationCount", "artifactCount", "readyDirectoryEntryCount",
                "missingDirectoryEntryCount")
GLOBAL_FIELDS = ("directoryCount", "completedScanCount", "stagingScanCount")


class Client:
    """Authenticated bounded reconciliation client."""

    def __init__(self, base_url: str, token: str, requester=urllib.request.urlopen) -> None:
        if not token:
            raise ValueError("Media Library internal token is missing")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.requester = requester

    def page(self, after_id: str | None) -> dict:
        """Read one ordered reconciliation page."""
        query = {"limit": 200}
        if after_id:
            query["afterId"] = after_id
        request = urllib.request.Request(
            self.base_url + "/internal/v1/media/reconciliation?" + urllib.parse.urlencode(query),
            headers={"Authorization": f"Bearer {self.token}", "Accept": "application/json"})
        with self.requester(request, timeout=60) as response:
            body = response.read()
        if len(body) > 1024 * 1024:
            raise ValueError("Media reconciliation page is too large")
        result = json.loads(body.decode())
        if not isinstance(result, dict) or len(str(result.get("pageDigestSha256") or "")) != 64:
            raise ValueError("Media reconciliation page is invalid")
        return result


def update(digest, value) -> None:
    """Append one length-delimited value to a digest."""
    encoded = str(value if value is not None else "").encode()
    digest.update(struct.pack(">I", len(encoded)))
    digest.update(encoded)


def execute(client: Client, require_quiescent: bool) -> dict:
    """Aggregate pages only when the library revision remains stable."""
    totals = {name: 0 for name in COUNT_FIELDS}
    revision = None
    globals_snapshot = None
    after_id = None
    digest = hashlib.sha256()
    while True:
        page = client.page(after_id)
        current_revision = int(page["libraryRevision"])
        current_globals = tuple(int(page[name]) for name in GLOBAL_FIELDS)
        if revision is None:
            revision = current_revision
            globals_snapshot = current_globals
            update(digest, revision)
            for value in current_globals:
                update(digest, value)
        elif current_revision != revision or current_globals != globals_snapshot:
            raise RuntimeError("Media Library changed during reconciliation")
        for name in COUNT_FIELDS:
            totals[name] += int(page[name])
            update(digest, page[name])
        update(digest, page["pageDigestSha256"])
        next_after = page.get("nextAfterId")
        if not next_after:
            break
        if next_after == after_id:
            raise RuntimeError("Media reconciliation cursor did not advance")
        after_id = str(next_after)
    result = {"libraryRevision": int(revision or 0),
              **dict(zip(GLOBAL_FIELDS, globals_snapshot or (0, 0, 0))), **totals,
              "digestSha256": digest.hexdigest()}
    if require_quiescent and (result["stagingScanCount"] > 0
                              or result["analyzingCount"] > 0
                              or result["runningAnalysisCount"] > 0):
        raise RuntimeError("Media Library is not quiescent")
    return result


def write_result(result: dict) -> None:
    """Atomically write the reconciliation report."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run one Media Library reconciliation."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    require_quiescent = bool(context.get("parameters", {}).get("requireQuiescent", True))
    client = Client(os.getenv("MEDIA_LIBRARY_URL", "http://127.0.0.1:23300"),
                    os.environ.get("MEDIA_LIBRARY_INTERNAL_TOKEN", ""))
    write_result(execute(client, require_quiescent))


if __name__ == "__main__":
    main()
