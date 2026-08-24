#!/usr/bin/env python3
"""Register verified task output as one unified content asset."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import urllib.parse

from mytools_task_sdk.asset import AssetRegistryClient


def verified_output(context: dict) -> tuple[dict, str]:
    """Resolve explicit, Reader, or HTTP download verified output."""
    parameters = context["parameters"]
    output = dict(parameters.get("assetOutput") or {})
    producer = "explicit"
    if not output:
        output = dict((context.get("stepOutputs") or {}).get("publish_asset") or {})
        producer = "download"
    if not output:
        output = dict((context.get("stepOutputs") or {}).get("import_ebook") or {})
        producer = "reader"
    if not output:
        output = dict((context.get("stepOutputs") or {}).get("download_asset") or {})
        producer = "download"
    if not output and (context.get("stepOutputs") or {}).get("probe"):
        source = Path(str(parameters.get("sourcePath") or ""))
        if not source.is_file():
            raise ValueError("verified media source does not exist")
        output = {
            "storageUri": "media://legacy/" + urllib.parse.quote(str(parameters["assetId"]), safe=""),
            "sha256": parameters.get("contentSha256"),
            "size": source.stat().st_size,
        }
        producer = "media"
    if output.get("contentSha256") and not output.get("sha256"):
        output["sha256"] = output["contentSha256"]
    if output.get("sizeBytes") is not None and output.get("size") is None:
        output["size"] = output["sizeBytes"]
    if not output.get("storageUri") and output.get("relativePath"):
        path = urllib.parse.quote(str(output["relativePath"]).lstrip("/"), safe="/")
        output["storageUri"] = "download://executor/" + path
    required = ("storageUri", "sha256", "size")
    if any(output.get(key) in (None, "") for key in required):
        raise ValueError("verified asset output is missing")
    return output, producer


def execute(context: dict, client: AssetRegistryClient) -> dict:
    """Build a stable registration payload from verified task output."""
    parameters = context["parameters"]
    output, producer = verified_output(context)
    request_id = str(parameters.get("requestId") or parameters.get("downloadRequestId")
                     or parameters.get("assetId"))
    default_source = {"download": "DOWNLOAD", "media": "MEDIA_FILE"}.get(producer, "READER_EBOOK")
    source_type = str(parameters.get("assetSourceType") or default_source)
    source_business_id = str(parameters.get("assetSourceBusinessId") or request_id)
    default_mime = {"download": "application/octet-stream", "media": "application/octet-stream"}.get(
        producer, "text/plain")
    default_provider = {"download": "DOWNLOAD_EXECUTOR", "media": "LEGACY_MEDIA"}.get(
        producer, "STORAGE_GATEWAY")
    if producer == "download" and str(output.get("storageUri") or "").startswith("storage://"):
        default_provider = "STORAGE_GATEWAY"
    mime_type = str(parameters.get("assetMimeType") or default_mime)
    provider_type = str(parameters.get("assetProviderType") or default_provider)
    payload = {
        "ownerId": int(parameters.get("ownerId") or 0),
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
