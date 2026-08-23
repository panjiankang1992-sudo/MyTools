#!/usr/bin/env python3
"""Page bounded Asset Registry evidence into one reconciliation report."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen

PAGE_SIZE = 200
DIGEST = re.compile(r"^[a-f0-9]{64}$")
COUNTS = ("assetCount", "sourceCount", "availableLocationCount", "invalidLocationCount",
          "artifactCount", "bundleReferenceCount")
COUNTS = COUNTS + ("legacyMappingCount",)


class Client:
    """Read protected bounded reconciliation pages."""

    def __init__(self, base_url: str, token: str, opener=urlopen):
        """Create a read-only Asset Registry client."""
        if not token:
            raise ValueError("Asset Registry reconciliation token is missing")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.opener = opener

    def page(self, after_id: str | None) -> dict:
        """Read one bounded evidence page."""
        query = {"limit": PAGE_SIZE}
        if after_id:
            query["afterId"] = after_id
        request = Request(self.base_url + "/internal/v1/assets/reconciliation?" + urlencode(query),
                          headers={"Authorization": f"Bearer {self.token}",
                                   "Accept": "application/json"})
        with self.opener(request, timeout=30) as response:
            body = response.read()
        if len(body) > 1024 * 1024:
            raise RuntimeError("Asset reconciliation response is too large")
        value = json.loads(body.decode("utf-8"))
        if not isinstance(value, dict):
            raise RuntimeError("Asset reconciliation response is invalid")
        return value


def execute(client: Client, start_after_id: str | None = None) -> dict:
    """Aggregate all page counts and deterministic digests."""
    totals = {name: 0 for name in COUNTS}
    digest = hashlib.sha256()
    after_id = start_after_id
    last_after_id = start_after_id
    page_count = 0
    registry_revision = None
    while True:
        page = client.page(after_id)
        page_count += 1
        page_revision = count(page, "registryRevision")
        if registry_revision is None:
            registry_revision = page_revision
        elif page_revision != registry_revision:
            raise RuntimeError("Asset Registry changed during reconciliation")
        for name in COUNTS:
            totals[name] += count(page, name)
        page_digest = str(page.get("digestSha256", ""))
        if not DIGEST.fullmatch(page_digest):
            raise RuntimeError("Asset reconciliation digest is invalid")
        digest.update(bytes.fromhex(page_digest))
        next_after_id = page.get("nextAfterId")
        if next_after_id is None:
            break
        next_after_id = str(next_after_id)
        if not next_after_id or next_after_id == after_id:
            raise RuntimeError("Asset reconciliation cursor did not advance")
        after_id = next_after_id
        last_after_id = next_after_id
    return {**totals, "registryRevision": registry_revision, "pageCount": page_count,
            "digestSha256": digest.hexdigest(),
            "lastAfterId": last_after_id}


def count(page: dict, name: str) -> int:
    """Read one non-negative integer count without accepting booleans."""
    value = page.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise RuntimeError("Asset reconciliation count is invalid")
    return value


def write_result(result: dict) -> None:
    """Atomically write the reconciliation report."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run one explicit full registry reconciliation."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    parameters = context["parameters"]
    client = Client(os.getenv("ASSET_REGISTRY_URL", "http://127.0.0.1:23270"),
                    os.getenv("ASSET_REGISTRY_INTERNAL_TOKEN", ""))
    after_id = parameters.get("afterId")
    write_result(execute(client, None if after_id is None else str(after_id)))


if __name__ == "__main__":
    main()
