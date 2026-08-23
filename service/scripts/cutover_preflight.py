#!/usr/bin/env python3
"""Validate service migration configuration without changing runtime state."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys

DEFAULT_SCHEMAS = {
    "DOWNLOAD_DB_NAME": "mytools_download",
    "STORAGE_DB_NAME": "mytools_storage",
    "ASSET_DB_NAME": "mytools_asset",
    "DRIVE_DB_NAME": "mytools_drive",
    "IDENTITY_DB_NAME": "mytools_identity",
    "MEDIA_LIBRARY_DB_NAME": "mytools_media",
    "READER_DB_NAME": "mytools_reader",
    "MESSAGING_DB_NAME": "mytools_messaging",
    "MESSAGE_AUTOMATION_DB_NAME": "mytools_message_automation",
}

SAFE_DISABLED_FLAGS = (
    "MEDIA_TAG_SIDECAR_ENABLED",
    "MEDIA_PROCESSING_SIDECAR_ENABLED",
    "READER_SEARCH_SIDECAR_ENABLED",
    "MESSAGING_REGISTRATION_MAIL_SIDECAR_ENABLED",
    "MESSAGING_ONEBOT_INGRESS_ENABLED",
    "MESSAGE_AUTOMATION_RELAY_ENABLED",
)

FALSE_VALUES = {"", "0", "false", "no", "off"}


def parse_env_file(path: Path) -> dict[str, str]:
    """Read a simple KEY=VALUE file without evaluating shell expressions."""
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)", line)
        if match is None:
            raise ValueError(f"Invalid environment line for key-only preflight: {raw_line}")
        value = match.group(2).strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[match.group(1)] = value
    return values


def inspect(values: dict[str, str], allow_enabled: bool = False) -> dict[str, object]:
    """Build a secret-free readiness report from environment values."""
    schemas = {key: values.get(key, default) for key, default in DEFAULT_SCHEMAS.items()}
    errors: list[str] = []
    warnings: list[str] = []
    task_url = values.get("TASK_DB_URL", "jdbc:mysql://127.0.0.1:3306/mytools_task")
    task_match = re.match(r"jdbc:mysql://[^/]+/([A-Za-z0-9_]+)(?:\?|$)", task_url)
    if task_match is None:
        errors.append("TASK_DB_URL does not contain a valid MySQL schema")
    else:
        schemas["TASK_DB_URL_SCHEMA"] = task_match.group(1)
    by_name: dict[str, list[str]] = {}
    for key, schema in schemas.items():
        if not re.fullmatch(r"[A-Za-z0-9_]+", schema):
            errors.append(f"{key} has an invalid schema name")
        by_name.setdefault(schema, []).append(key)
    for schema, keys in by_name.items():
        if len(keys) > 1:
            errors.append(f"Schema {schema} is shared by: {', '.join(sorted(keys))}")

    flags: dict[str, bool] = {}
    for key in SAFE_DISABLED_FLAGS:
        enabled = values.get(key, "false").strip().lower() not in FALSE_VALUES
        flags[key] = enabled
        if enabled and not allow_enabled:
            errors.append(f"{key} must remain disabled before an approved grey release")
        elif enabled:
            warnings.append(f"{key} is enabled for an approved rehearsal")
    return {"ready": not errors, "schemas": schemas, "flags": flags,
            "errors": errors, "warnings": warnings}


def main() -> int:
    """Run the read-only cutover preflight command."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--allow-enabled", action="store_true")
    arguments = parser.parse_args()
    values = dict(os.environ)
    if arguments.env_file is not None:
        values.update(parse_env_file(arguments.env_file))
    report = inspect(values, arguments.allow_enabled)
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    sys.exit(main())
