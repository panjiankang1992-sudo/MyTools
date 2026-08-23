#!/usr/bin/env python3
"""Publish and register a media thumbnail as a derived content asset."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile

from mytools_task_sdk.asset import AssetRegistryClient
from mytools_task_sdk.storage import StorageGatewayClient


def execute(context: dict, storage: StorageGatewayClient, assets: AssetRegistryClient) -> dict:
    """Publish the generated thumbnail and link it to its original media asset."""
    parameters = context["parameters"]
    generated = dict((context.get("stepOutputs") or {}).get("generate_thumbnail") or {})
    artifact_path = Path(str(generated.get("artifactPath") or ""))
    if not artifact_path.is_file():
        raise ValueError("generated thumbnail does not exist")
    media_id = str(parameters["assetId"])
    parent = assets.register({
        "ownerId": int(parameters.get("ownerId") or 0),
        "idempotencyKey": f"media_file:{media_id}",
        "sourceType": "MEDIA_FILE",
        "sourceBusinessId": media_id,
        "contentSha256": str(parameters["contentSha256"]).lower(),
        "sizeBytes": Path(str(parameters["sourcePath"])).stat().st_size,
        "mimeType": str(parameters.get("assetMimeType") or parameters.get("mimeType")
                        or "application/octet-stream"),
        "location": {
            "idempotencyKey": f"media_file-location:{media_id}",
            "providerType": "LEGACY_MEDIA",
            "storageUri": f"media://legacy/{media_id}",
            "providerVersion": "v1",
        },
    })
    requested_parent = parameters.get("assetRegistryId")
    if requested_parent and str(parent["id"]) != str(requested_parent):
        raise ValueError("registered parent asset identity conflicts with Media Library")
    generator_version = str(parameters.get("analysisVersion") or "1.0.0")
    artifact_sha = str(generated["artifactSha256"]).lower()
    relative_path = f"media/thumbnails/{parameters['contentSha256']}/{artifact_sha}.jpg"
    storage_uri = storage.publish(artifact_path, str(parameters.get("storageRoot") or "managed"),
                                  relative_path, f"media-thumbnail:{media_id}:{artifact_sha}",
                                  int(generated["size"]), artifact_sha)
    artifact = assets.register({
        "ownerId": int(parameters.get("ownerId") or 0),
        "idempotencyKey": f"media_thumbnail:{media_id}:{generator_version}:{artifact_sha}",
        "sourceType": "MEDIA_THUMBNAIL",
        "sourceBusinessId": f"{media_id}:{generator_version}:{artifact_sha}",
        "contentSha256": artifact_sha,
        "sizeBytes": int(generated["size"]),
        "mimeType": "image/jpeg",
        "location": {
            "idempotencyKey": f"media_thumbnail-location:{media_id}:{generator_version}:{artifact_sha}",
            "providerType": "STORAGE_GATEWAY",
            "storageUri": storage_uri,
            "providerVersion": "v1",
        },
    })
    linked = assets.register_artifact(str(parent["id"]), {
        "expectedAssetVersion": int(parent["version"]),
        "artifactAssetId": str(artifact["id"]),
        "idempotencyKey": f"media_thumbnail-artifact:{media_id}:{generator_version}:{artifact_sha}",
        "artifactKind": "THUMBNAIL",
        "generatorName": "media_generate_thumbnail",
        "generatorVersion": generator_version,
    })
    return {"parentAssetId": str(parent["id"]), "artifactAssetId": str(artifact["id"]),
            "parentVersion": int(linked["version"]), "storageUri": storage_uri}


def write_result(result: dict) -> None:
    """Atomically write the executor result file."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one media thumbnail asset registration step."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    storage = StorageGatewayClient(os.getenv("STORAGE_GATEWAY_URL", "http://127.0.0.1:23240"),
                                   os.environ.get("STORAGE_INTERNAL_TOKEN", ""))
    assets = AssetRegistryClient(os.getenv("ASSET_REGISTRY_URL", "http://127.0.0.1:23270"),
                                 os.environ.get("ASSET_REGISTRY_INTERNAL_TOKEN", ""))
    write_result(execute(context, storage, assets))


if __name__ == "__main__":
    main()
