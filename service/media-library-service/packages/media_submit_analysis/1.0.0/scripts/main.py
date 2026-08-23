#!/usr/bin/env python3
"""Optionally submit versioned media analysis after successful ingestion."""

from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile

from mytools_task_sdk.context import TaskContext


def execute(task: TaskContext, node_affinity: str) -> dict:
    """Create one same-node analysis child when the caller explicitly opts in."""
    parameters = task.parameters
    outputs = task.context.get("stepOutputs") or {}
    media = outputs.get("register_media_item") or {}
    asset = outputs.get("register_asset") or {}
    media_item_id = str(media.get("mediaItemId") or "")
    asset_registry_id = str(asset.get("assetId") or "")
    if not media_item_id or not asset_registry_id:
        raise ValueError("Media ingestion identities are missing")
    if not parameters.get("analyze", False):
        return {"status": "SKIPPED", "mediaItemId": media_item_id,
                "analysisTaskId": None, "analysisVersion": None}
    if not node_affinity:
        raise ValueError("Task executor node affinity is missing")
    analysis_version = str(parameters.get("analysisVersion") or "media-analysis-v1")
    source = Path(str(parameters["sourcePath"]))
    child_parameters = {
        "mediaItemId": media_item_id,
        "assetRegistryId": asset_registry_id,
        "assetId": str(parameters["assetId"]),
        "analysisVersion": analysis_version,
        "sourcePath": str(source),
        "contentSha256": str(parameters["contentSha256"]).lower(),
        "ownerId": int(parameters["ownerId"]),
        "filename": source.name,
        "mimeType": str(parameters.get("assetMimeType") or "application/octet-stream"),
        "assetMimeType": str(parameters.get("assetMimeType") or "application/octet-stream"),
    }
    for name in ("storageRoot", "frameCount", "seekSeconds"):
        if parameters.get(name) is not None:
            child_parameters[name] = parameters[name]
    child = task.create_child(
        "media_analyze_video", child_parameters,
        f"media-analysis:{media_item_id}:{analysis_version}",
        business_type="MEDIA_ANALYSIS", business_id=media_item_id,
        required_node_labels={"executor.node": node_affinity})
    return {"status": "SUBMITTED", "mediaItemId": media_item_id,
            "analysisTaskId": child.id, "analysisVersion": analysis_version}


def write_result(result: dict) -> None:
    """Atomically write the submission result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Submit analysis for the current ingested media when enabled."""
    task = TaskContext.load()
    write_result(execute(task, os.environ.get("TASK_EXECUTOR_NODE_AFFINITY", "")))


if __name__ == "__main__":
    main()
