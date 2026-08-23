#!/usr/bin/env python3
"""Validate Storage migration evidence without changing runtime state."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from uuid import UUID

MAXIMUM_REPORT_BYTES = 1024 * 1024
DIGEST = re.compile(r"^[a-f0-9]{64}$")
MIGRATION_KEY = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


def read_report(path: Path) -> dict:
    """Read one bounded JSON evidence document."""
    if not path.is_file() or path.stat().st_size > MAXIMUM_REPORT_BYTES:
        raise ValueError("EVIDENCE_FILE_INVALID")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("EVIDENCE_FILE_INVALID")
    return value


def evaluate(dry_run: dict, applied: dict, reconciliations: list[dict],
             expected_reconciliations: int) -> dict:
    """Evaluate migration and reconciliation invariants into safe error codes."""
    errors: set[str] = set()
    migration_key = safe_text(dry_run.get("migrationKey"))
    if not MIGRATION_KEY.fullmatch(migration_key) or migration_key != safe_text(applied.get("migrationKey")):
        errors.add("MIGRATION_KEY_MISMATCH")
    if dry_run.get("dryRun") is not True or applied.get("dryRun") is not False:
        errors.add("MIGRATION_MODE_INVALID")
    dry_digest = safe_text(dry_run.get("digestSha256"))
    apply_digest = safe_text(applied.get("digestSha256"))
    if not DIGEST.fullmatch(dry_digest) or dry_digest != apply_digest:
        errors.add("SOURCE_DIGEST_MISMATCH")
    dry_exported = integer(dry_run, "exported", errors)
    apply_exported = integer(applied, "exported", errors)
    dry_accepted = integer(dry_run, "accepted", errors)
    apply_accepted = integer(applied, "accepted", errors)
    dry_rejected = integer(dry_run, "rejected", errors)
    apply_rejected = integer(applied, "rejected", errors)
    applied_bound = integer(applied, "bound", errors)
    dry_bound = integer(dry_run, "bound", errors)
    if (dry_exported, dry_accepted) != (apply_exported, apply_accepted):
        errors.add("MIGRATION_COUNT_MISMATCH")
    if dry_rejected != 0 or apply_rejected != 0:
        errors.add("MIGRATION_HAS_REJECTIONS")
    if dry_accepted + dry_rejected != dry_exported or apply_accepted + apply_rejected != apply_exported:
        errors.add("MIGRATION_COUNT_INVALID")
    if dry_bound != 0:
        errors.add("DRY_RUN_HAS_BINDINGS")
    if applied_bound != apply_accepted:
        errors.add("MIGRATION_BINDING_INCOMPLETE")
    if expected_reconciliations < 0 or len(reconciliations) != expected_reconciliations:
        errors.add("RECONCILIATION_COUNT_MISMATCH")
    identities: set[tuple[str, str, str]] = set()
    accounts: set[str] = set()
    providers: set[str] = set()
    for report in reconciliations:
        if safe_text(report.get("migrationKey")) != migration_key:
            errors.add("RECONCILIATION_KEY_MISMATCH")
        if report.get("matched") is not True or report.get("mismatchReasons") != []:
            errors.add("RECONCILIATION_FAILED")
        identity = (safe_text(report.get("accountId")), safe_text(report.get("storageProviderId")),
                    safe_text(report.get("storageOperationId")))
        if not all(valid_uuid(value) for value in identity) or identity in identities \
                or identity[0] in accounts or identity[1] in providers:
            errors.add("RECONCILIATION_IDENTITY_INVALID")
        identities.add(identity)
        accounts.add(identity[0])
        providers.add(identity[1])
        if not valid_digest_pair(report):
            errors.add("RECONCILIATION_DIGEST_INVALID")
        elif (report["driveItemCount"] != report["storageItemCount"]
              or report["driveContentSha256"] != report["storageContentSha256"]):
            errors.add("RECONCILIATION_FAILED")
    return {"ready": not errors, "migrationKey": migration_key or None,
            "migratedCount": applied_bound, "reconciliationCount": len(reconciliations),
            "errors": sorted(errors)}


def integer(report: dict, name: str, errors: set[str]) -> int:
    """Read one non-negative integer without accepting booleans."""
    value = report.get(name)
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        errors.add("MIGRATION_REPORT_INVALID")
        return -1
    return value


def valid_digest_pair(report: dict) -> bool:
    """Validate both collection digests and non-negative counts."""
    counts = (report.get("driveItemCount"), report.get("storageItemCount"))
    return all(isinstance(value, int) and not isinstance(value, bool) and value >= 0 for value in counts) \
        and DIGEST.fullmatch(safe_text(report.get("driveContentSha256"))) is not None \
        and DIGEST.fullmatch(safe_text(report.get("storageContentSha256"))) is not None


def safe_text(value: object) -> str:
    """Convert scalar identifiers without serializing nested or secret-bearing values."""
    return value if isinstance(value, str) and len(value) <= 255 else ""


def valid_uuid(value: str) -> bool:
    """Require canonical UUID evidence identifiers."""
    try:
        return str(UUID(value)) == value.lower()
    except (ValueError, AttributeError):
        return False


def main() -> None:
    """Run the read-only Storage cutover evidence gate."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run-report", required=True, type=Path)
    parser.add_argument("--apply-report", required=True, type=Path)
    parser.add_argument("--reconciliation-report", action="append", default=[], type=Path)
    parser.add_argument("--expected-reconciliations", required=True, type=int)
    arguments = parser.parse_args()
    try:
        result = evaluate(read_report(arguments.dry_run_report), read_report(arguments.apply_report),
                          [read_report(path) for path in arguments.reconciliation_report],
                          arguments.expected_reconciliations)
    except (OSError, ValueError, json.JSONDecodeError):
        result = {"ready": False, "migrationKey": None, "migratedCount": -1,
                  "reconciliationCount": 0, "errors": ["EVIDENCE_FILE_INVALID"]}
    print(json.dumps(result, separators=(",", ":")))
    if not result["ready"]:
        sys.exit(2)


if __name__ == "__main__":
    main()
