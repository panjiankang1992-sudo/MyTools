#!/usr/bin/env python3
"""Register verified task output as one unified content asset."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile

from mytools_task_sdk.asset import AssetRegistryClient


def import_output(context: dict) -> dict:
    """Resolve explicit input or the preceding Reader import step output."""
    parameters = context["parameters"]
    output = dict(parameters.get("assetOutput") or {})
    if not output:
        output = dict((context.get("stepOutputs") or {}).get("import_ebook") or {})
    required = ("storageUri", "sha256", "size")
    if any(output.get(key) in (None, "") for key in required):
        raise ValueError("verified asset output is missing")
    return output


def execute(context: dict, client: AssetRegistryClient) -> dict:
    """Build a stable registration payload from verified task output."""
    parameters = context["parameters"]
    output = import_output(context)
    request_id = str(parameters["requestId"])
    source_type = str(parameters.get("assetSourceType") or "READER_EBOOK")
    source_business_id = str(parameters.get("assetSourceBusinessId") or request_id)
    mime_type = str(parameters.get("assetMimeType") or "text/plain")
    provider_type = str(parameters.get("assetProviderType") or "STORAGE_GATEWAY")
    payload = {
        "ownerId": int(parameters["ownerId"]),
        "idempotencyKey": f"{source_type.lower()}:{source_business_id}",
        "sourceType": source_type,
        "sourceBusinessId": source_business_id,
        "contentSha256": str(output["sha256"]).lower(),
        "sizeBytes": int(output["size"]),
        "mimeType": mime_type,
        "location": {
            "idempotencyKey": f"{source_type.lower()}-location:{source_business_id}",
            "providerType": provider_type,
            "storageUri": str(output["storageUri"]),
            "providerVersion": str(parameters.get("assetProviderVersion") or "v1")
        }
    }
    result = client.register(payload)
    return {"assetId": str(result["id"]), "version": int(result["version"])}


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one asset registration task step."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client = AssetRegistryClient(os.getenv("ASSET_REGISTRY_URL", "http://127.0.0.1:23270"),
                                 os.environ.get("ASSET_REGISTRY_INTERNAL_TOKEN", ""))
    write_result(execute(context, client))


if __name__ == "__main__":
    main()
