#!/usr/bin/env python3
"""Validate legacy media migration evidence without connecting to any service."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

MAXIMUM_REPORT_BYTES = 1024 * 1024
DIGEST = re.compile(r"^[a-f0-9]{64}$")
SNAPSHOT_ID = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


def read_report(path: Path) -> dict:
    """Read one bounded JSON evidence document."""
    if not path.is_file() or path.stat().st_size > MAXIMUM_REPORT_BYTES:
        raise ValueError("EVIDENCE_FILE_INVALID")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("EVIDENCE_FILE_INVALID")
    return value


def evaluate(snapshot: dict, asset_dry: dict, asset_apply: dict,
             media_dry: dict, media_apply: dict, media_replay: dict,
             asset_reconciliation: dict, media_target: dict,
             media_reconciliation: dict) -> dict:
    """Evaluate source, migration and target reconciliation invariants."""
    errors: set[str] = set()
    snapshot_id = text(snapshot.get("snapshotId"))
    if not SNAPSHOT_ID.fullmatch(snapshot_id):
        errors.add("SNAPSHOT_ID_INVALID")
    owner_id = integer(snapshot, "ownerId", errors)
    captured = integer(snapshot, "captured", errors)
    rejected = integer(snapshot, "rejected", errors)
    if owner_id <= 0:
        errors.add("SNAPSHOT_OWNER_INVALID")
    if rejected != 0:
        errors.add("SNAPSHOT_HAS_REJECTIONS")
    if not DIGEST.fullmatch(text(snapshot.get("digestSha256"))):
        errors.add("SNAPSHOT_DIGEST_INVALID")

    validate_modes(asset_dry, asset_apply, errors)
    if any(text(report.get("sourceSnapshotId")) != snapshot_id
           for report in (asset_dry, asset_apply, media_dry, media_apply, media_replay)):
        errors.add("SNAPSHOT_REFERENCE_MISMATCH")
    asset_exported = compare_pair(asset_dry, asset_apply, "exported", errors,
                                  "ASSET_COUNT_MISMATCH")
    if asset_exported != captured:
        errors.add("ASSET_SOURCE_COUNT_MISMATCH")
    for report in (asset_dry, asset_apply):
        accepted = integer(report, "accepted", errors)
        skipped = integer(report, "skipped", errors)
        migration_rejected = integer(report, "rejected", errors)
        if accepted + skipped + migration_rejected != integer(report, "exported", errors):
            errors.add("ASSET_COUNT_INVALID")
        if migration_rejected != 0:
            errors.add("ASSET_HAS_REJECTIONS")
    compare_digest(asset_dry, asset_apply, errors, "ASSET_DIGEST_MISMATCH")

    validate_modes(media_dry, media_apply, errors)
    if media_replay.get("dryRun") is not False:
        errors.add("MIGRATION_MODE_INVALID")
    migration_key = text(media_apply.get("migrationKey"))
    if not SNAPSHOT_ID.fullmatch(migration_key) or any(
            text(report.get("migrationKey")) != migration_key
            for report in (media_dry, media_replay)):
        errors.add("MEDIA_MIGRATION_KEY_MISMATCH")
    exported = compare_pair(media_dry, media_apply, "exported", errors,
                            "MEDIA_COUNT_MISMATCH")
    media_items = compare_pair(media_dry, media_apply, "mediaItems", errors,
                               "MEDIA_COUNT_MISMATCH")
    legacy_tags = compare_pair(media_dry, media_apply, "legacyTags", errors,
                               "MEDIA_TAG_COUNT_MISMATCH")
    skipped = compare_pair(media_dry, media_apply, "skippedNonMedia", errors,
                           "MEDIA_COUNT_MISMATCH")
    if exported != captured or media_items + skipped != exported:
        errors.add("MEDIA_SOURCE_COUNT_MISMATCH")
    if integer(media_dry, "imported", errors) != 0:
        errors.add("MEDIA_DRY_RUN_WROTE_DATA")
    if integer(media_apply, "imported", errors) != media_items:
        errors.add("MEDIA_IMPORT_INCOMPLETE")
    if integer(media_replay, "imported", errors) != media_items:
        errors.add("MEDIA_REPLAY_INCOMPLETE")
    for field, code in (("exported", "MEDIA_REPLAY_MISMATCH"),
                        ("mediaItems", "MEDIA_REPLAY_MISMATCH"),
                        ("legacyTags", "MEDIA_REPLAY_MISMATCH"),
                        ("skippedNonMedia", "MEDIA_REPLAY_MISMATCH")):
        if integer(media_replay, field, errors) != integer(media_apply, field, errors):
            errors.add(code)
    if media_apply.get("targetVerified") is not True \
            or media_replay.get("targetVerified") is not True \
            or media_dry.get("targetVerified") is not False:
        errors.add("MEDIA_TARGET_NOT_VERIFIED")
    compare_digest(media_dry, media_apply, errors, "MEDIA_DIGEST_MISMATCH")
    if text(media_replay.get("digestSha256")) != text(media_apply.get("digestSha256")):
        errors.add("MEDIA_REPLAY_MISMATCH")

    legacy_mappings = integer(asset_reconciliation, "legacyMappingCount", errors)
    integer(asset_reconciliation, "assetCount", errors)
    if legacy_mappings < captured:
        errors.add("ASSET_RECONCILIATION_INCOMPLETE")
    if not DIGEST.fullmatch(text(asset_reconciliation.get("digestSha256"))):
        errors.add("ASSET_RECONCILIATION_INVALID")
    if text(media_target.get("migrationKey")) != migration_key \
            or text(media_target.get("sourceSnapshotId")) != snapshot_id \
            or integer(media_target, "itemCount", errors) != media_items \
            or integer(media_target, "tagCount", errors) != legacy_tags \
            or text(media_target.get("collectionSha256")) != text(media_apply.get("digestSha256")):
        errors.add("MEDIA_TARGET_MISMATCH")
    for field in ("stagingScanCount", "analyzingCount", "runningAnalysisCount"):
        if integer(media_reconciliation, field, errors) != 0:
            errors.add("MEDIA_RECONCILIATION_NOT_QUIESCENT")
    if not DIGEST.fullmatch(text(media_reconciliation.get("digestSha256"))):
        errors.add("MEDIA_RECONCILIATION_INVALID")
    return {"ready": not errors, "snapshotId": snapshot_id or None,
            "captured": captured, "mediaItems": media_items, "legacyTags": legacy_tags,
            "errors": sorted(errors)}


def validate_modes(dry_run: dict, applied: dict, errors: set[str]) -> None:
    """Require an explicit dry-run followed by an explicit apply report."""
    if dry_run.get("dryRun") is not True or applied.get("dryRun") is not False:
        errors.add("MIGRATION_MODE_INVALID")


def compare_pair(dry_run: dict, applied: dict, field: str, errors: set[str], code: str) -> int:
    """Read and compare one non-negative count from both reports."""
    left = integer(dry_run, field, errors)
    right = integer(applied, field, errors)
    if left != right:
        errors.add(code)
    return left


def compare_digest(dry_run: dict, applied: dict, errors: set[str], code: str) -> None:
    """Require matching canonical source digests without returning them."""
    left = text(dry_run.get("digestSha256"))
    right = text(applied.get("digestSha256"))
    if not DIGEST.fullmatch(left) or left != right:
        errors.add(code)


def integer(report: dict, field: str, errors: set[str]) -> int:
    """Read one non-negative integer without accepting booleans."""
    value = report.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        errors.add("EVIDENCE_REPORT_INVALID")
        return -1
    return value


def text(value: object) -> str:
    """Read one bounded text value without serializing nested data."""
    return value if isinstance(value, str) and len(value) <= 255 else ""


def main() -> None:
    """Run the read-only legacy media migration evidence gate."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot-report", required=True, type=Path)
    parser.add_argument("--asset-dry-run-report", required=True, type=Path)
    parser.add_argument("--asset-apply-report", required=True, type=Path)
    parser.add_argument("--media-dry-run-report", required=True, type=Path)
    parser.add_argument("--media-apply-report", required=True, type=Path)
    parser.add_argument("--media-replay-report", required=True, type=Path)
    parser.add_argument("--asset-reconciliation-report", required=True, type=Path)
    parser.add_argument("--media-target-report", required=True, type=Path)
    parser.add_argument("--media-reconciliation-report", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        result = evaluate(read_report(arguments.snapshot_report),
                          read_report(arguments.asset_dry_run_report),
                          read_report(arguments.asset_apply_report),
                          read_report(arguments.media_dry_run_report),
                          read_report(arguments.media_apply_report),
                          read_report(arguments.media_replay_report),
                          read_report(arguments.asset_reconciliation_report),
                          read_report(arguments.media_target_report),
                          read_report(arguments.media_reconciliation_report))
    except (OSError, ValueError, json.JSONDecodeError):
        result = {"ready": False, "snapshotId": None, "captured": -1,
                  "mediaItems": -1, "legacyTags": -1, "errors": ["EVIDENCE_FILE_INVALID"]}
    print(json.dumps(result, separators=(",", ":")))
    if not result["ready"]:
        sys.exit(2)


if __name__ == "__main__":
    main()
