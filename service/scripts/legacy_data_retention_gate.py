#!/usr/bin/env python3
"""Validate that the legacy MyTools database has a recoverable full backup."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


REQUIRED_TABLES = {
    "drive_account",
    "drive_item_index",
    "ebook_metadata",
    "file_maintenance_log",
    "file_tag",
    "local_directory",
    "local_file",
    "media_package",
    "media_package_asset",
    "media_tag_artifact",
    "refresh_log",
    "t_api_log",
    "t_app_file",
    "t_app_market",
    "t_app_version",
    "t_book_source_search_cache",
    "t_dsh_session_binding",
    "t_email_verification_code",
    "t_feedback",
    "t_reader_marker",
    "t_reading_progress",
    "t_role",
    "t_shelf_book",
    "t_synced_book_source",
    "t_token",
    "t_user",
    "t_user_role",
    "webdav_account",
}


def evaluate(manifest: dict[str, object]) -> dict[str, object]:
    """检查备份清单是否覆盖全部已知旧表且已经验证可读取。"""
    errors: list[str] = []
    backup_file = manifest.get("backupFile")
    if not isinstance(backup_file, str) or not backup_file.strip():
        errors.append("backupFile is required")

    sha256 = manifest.get("sha256")
    if not isinstance(sha256, str) or re.fullmatch(r"[0-9a-f]{64}", sha256) is None:
        errors.append("sha256 must be a lowercase SHA-256 digest")

    if manifest.get("readVerified") is not True:
        errors.append("readVerified must be true")
    if manifest.get("inventoryComplete") is not True:
        errors.append("inventoryComplete must be true")

    unclassified = manifest.get("unclassifiedTables")
    if unclassified != []:
        errors.append("unclassifiedTables must be an empty list")

    raw_absent = manifest.get("absentTables", [])
    absent = set(raw_absent) if isinstance(raw_absent, list) and all(
        isinstance(name, str) for name in raw_absent
    ) else set()
    if not isinstance(raw_absent, list) or len(absent) != len(raw_absent):
        errors.append("absentTables must be an array of unique table names")
    unknown_absent = sorted(absent - REQUIRED_TABLES)
    if unknown_absent:
        errors.append("unknown absent tables: " + ", ".join(unknown_absent))

    raw_tables = manifest.get("tables")
    tables = raw_tables if isinstance(raw_tables, dict) else {}
    if not isinstance(raw_tables, dict):
        errors.append("tables must be an object of table row counts")
    overlap = sorted(absent & set(tables))
    if overlap:
        errors.append("tables cannot also be absent: " + ", ".join(overlap))
    missing = sorted(REQUIRED_TABLES - set(tables) - absent)
    if missing:
        errors.append("missing required tables: " + ", ".join(missing))

    invalid_counts = sorted(
        name for name, count in tables.items()
        if not isinstance(name, str) or isinstance(count, bool) or not isinstance(count, int) or count < 0
    )
    if invalid_counts:
        errors.append("invalid row counts: " + ", ".join(invalid_counts))

    return {
        "ready": not errors,
        "knownTableCount": len(REQUIRED_TABLES),
        "backedUpTableCount": len(tables),
        "absentTableCount": len(absent),
        "totalRows": sum(count for count in tables.values()
                         if isinstance(count, int) and not isinstance(count, bool) and count >= 0),
        "errors": errors,
    }


def verify_backup(manifest: dict[str, object], manifest_path: Path) -> dict[str, object]:
    """流式验证清单引用的备份文件存在、类型安全且摘要一致。"""
    backup_file = manifest.get("backupFile")
    expected_sha256 = manifest.get("sha256")
    errors: list[str] = []
    if not isinstance(backup_file, str) or not backup_file.strip():
        return {"verified": False, "sizeBytes": 0, "errors": ["backup file cannot be verified"]}
    candidate = Path(backup_file)
    if not candidate.is_absolute():
        candidate = manifest_path.resolve().parent / candidate
    if candidate.is_symlink():
        return {"verified": False, "sizeBytes": 0,
                "errors": ["backupFile must not be a symbolic link"]}
    try:
        resolved = candidate.resolve(strict=True)
    except FileNotFoundError:
        errors.append("backupFile does not exist")
        return {"verified": False, "sizeBytes": 0, "errors": errors}
    except OSError:
        errors.append("backupFile cannot be resolved")
        return {"verified": False, "sizeBytes": 0, "errors": errors}
    if not resolved.is_file():
        errors.append("backupFile must be a regular file")
        return {"verified": False, "sizeBytes": 0, "errors": errors}
    digest = hashlib.sha256()
    size = 0
    try:
        with resolved.open("rb") as handle:
            while chunk := handle.read(1024 * 1024):
                size += len(chunk)
                digest.update(chunk)
    except OSError:
        errors.append("backupFile cannot be read")
        return {"verified": False, "sizeBytes": size, "errors": errors}
    if not isinstance(expected_sha256, str) or digest.hexdigest() != expected_sha256:
        errors.append("backupFile SHA-256 does not match manifest")
    return {"verified": not errors, "sizeBytes": size, "errors": errors}


def main() -> int:
    """执行只读的旧库数据保全门禁。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    arguments = parser.parse_args()
    manifest = json.loads(arguments.manifest.read_text(encoding="utf-8"))
    report = evaluate(manifest)
    backup = verify_backup(manifest, arguments.manifest)
    report["backupVerified"] = backup["verified"]
    report["backupSizeBytes"] = backup["sizeBytes"]
    report["errors"].extend(backup["errors"])
    report["ready"] = not report["errors"]
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    sys.exit(main())
