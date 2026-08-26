#!/usr/bin/env python3
"""Record a verified download and registered asset in Download Ingestion."""

import json
import os
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen


def build_payload(context: dict) -> dict:
    """Combine immutable outputs from the download and asset registration steps."""
    outputs = context.get("stepOutputs") or {}
    download = dict(outputs.get("publish_asset") or outputs.get("download_asset") or {})
    asset = dict(outputs.get("register_asset") or {})
    required = ("itemId", "fileName", "contentSha256", "sizeBytes")
    if any(download.get(key) in (None, "") for key in required) or not asset.get("assetId"):
        raise ValueError("preceding download outputs are incomplete")
    storage_uri = download.get("storageUri")
    if not storage_uri and download.get("relativePath"):
        storage_uri = "download://executor/" + str(download["relativePath"]).lstrip("/")
    if not storage_uri:
        raise ValueError("preceding download storage output is incomplete")
    return {
        "sourceIndex": int((context.get("parameters") or {}).get("sourceIndex", 0)),
        "itemId": str(download["itemId"]),
        "fileName": str(download["fileName"]),
        "contentSha256": str(download["contentSha256"]).lower(),
        "sizeBytes": int(download["sizeBytes"]),
        "storageUri": str(storage_uri),
        "assetId": str(asset["assetId"]),
    }


def execute(context: dict, base_url: str, token: str, opener=urlopen) -> dict:
    """Send one idempotent internal result callback."""
    request_id = str(context["parameters"]["downloadRequestId"])
    body = json.dumps(build_payload(context), separators=(",", ":")).encode()
    request = Request(f"{base_url.rstrip('/')}/internal/v1/download-requests/{request_id}/result",
                      data=body, method="POST", headers={"Content-Type": "application/json",
                                                         "Authorization": f"Bearer {token}"})
    with opener(request, timeout=15) as response:
        return json.loads(response.read().decode())


def write_result(result: dict) -> None:
    """Atomically write the callback response for executor persistence."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Load executor context and record the result."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    write_result(execute(context, os.getenv("DOWNLOAD_INGESTION_URL", "http://127.0.0.1:23220"),
                         os.getenv("DOWNLOAD_INTERNAL_TOKEN", "")))


if __name__ == "__main__":
    main()
