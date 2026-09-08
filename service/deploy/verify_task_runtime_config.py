#!/usr/bin/env python3
"""Verify production task runtime credentials, permissions, and local paths."""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import stat
from collections.abc import Sequence
from pathlib import Path

TOKEN_KEYS = (
    "MESSAGING_INTERNAL_TOKEN",
    "TASK_EXECUTOR_INTERNAL_TOKEN",
    "TASK_OPERATOR_INTERNAL_TOKEN",
    "TASK_BUSINESS_MYTOOLS_TOKEN",
    "TASK_BUSINESS_MESSAGING_TOKEN",
    "TASK_BUSINESS_DRIVE_TOKEN",
    "TASK_BUSINESS_MEDIA_LIBRARY_TOKEN",
    "TASK_BUSINESS_READER_TOKEN",
    "TASK_BUSINESS_STORAGE_GATEWAY_TOKEN",
)
REQUIRED_KEYS = TOKEN_KEYS + (
    "TASK_EXECUTOR_WORK_ROOT",
    "TASK_EXECUTOR_SCRIPT_ROOT",
    "TASK_EXECUTOR_REQUIRE_PACKAGE_INDEX",
    "TASK_EXECUTOR_REQUIRE_NON_ROOT",
    "MESSAGING_REGISTRATION_MAIL_MODE",
    "MESSAGING_REGISTRATION_MAIL_CANARY_PERCENT",
    "MESSAGING_REGISTRATION_MAIL_ROUTING_KEY",
    "MESSAGING_REGISTRATION_MAIL_DELIVERY_ENCRYPTION_KEY",
    "MESSAGING_REGISTRATION_MAIL_SHADOW_HASH_KEY",
)


def load_environment(path: Path) -> dict[str, str]:
    """Load a simple deployment environment file without shell evaluation."""

    environment: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"invalid environment assignment at line {line_number}")
        key, value = line.split("=", 1)
        if not key or key in environment:
            raise ValueError(f"invalid or duplicate environment key at line {line_number}")
        environment[key] = value
    return environment


def verify_environment_file(path: Path) -> dict[str, str]:
    """Require a regular environment file with exact owner-only permissions."""

    mode = path.stat().st_mode
    if not stat.S_ISREG(mode):
        raise ValueError("environment path is not a regular file")
    if stat.S_IMODE(mode) != 0o600:
        raise ValueError("environment file mode must be 0600")
    return load_environment(path)


def verify_values(environment: dict[str, str]) -> None:
    """Verify task credentials and migration safety defaults."""

    missing = [key for key in REQUIRED_KEYS if not environment.get(key)]
    if missing:
        raise ValueError("required task settings are missing: " + ", ".join(missing))
    token_values = [environment[key] for key in TOKEN_KEYS]
    if len(token_values) != len(set(token_values)):
        raise ValueError("task service tokens must be independent")
    if environment["TASK_EXECUTOR_REQUIRE_PACKAGE_INDEX"].lower() != "true":
        raise ValueError("Executor package index must be required")
    if environment["TASK_EXECUTOR_REQUIRE_NON_ROOT"].lower() != "true":
        raise ValueError("Executor non-root guard must be enabled")
    mode = environment["MESSAGING_REGISTRATION_MAIL_MODE"].upper()
    if mode not in {"LEGACY", "SHADOW", "CANARY", "PRIMARY"}:
        raise ValueError("registration mail mode is invalid")
    try:
        canary_percent = int(environment["MESSAGING_REGISTRATION_MAIL_CANARY_PERCENT"])
    except ValueError as error:
        raise ValueError("registration mail canary percent is invalid") from error
    if canary_percent < 0 or canary_percent > 100:
        raise ValueError("registration mail canary percent must be between 0 and 100")
    if mode != "CANARY" and canary_percent != 0:
        raise ValueError("registration mail canary percent must be zero outside CANARY mode")
    if mode == "SHADOW" and len(environment["MESSAGING_REGISTRATION_MAIL_SHADOW_HASH_KEY"]) < 32:
        raise ValueError("registration mail shadow hash key must contain at least 32 characters")
    if mode == "CANARY" and len(environment["MESSAGING_REGISTRATION_MAIL_ROUTING_KEY"]) < 32:
        raise ValueError("registration mail routing key must contain at least 32 characters")
    if mode != "LEGACY":
        try:
            encryption_key = base64.b64decode(
                environment["MESSAGING_REGISTRATION_MAIL_DELIVERY_ENCRYPTION_KEY"], validate=True)
        except (binascii.Error, ValueError) as error:
            raise ValueError("registration mail delivery encryption key must be Base64") from error
        if len(encryption_key) != 32:
            raise ValueError("registration mail delivery encryption key must contain 32 bytes")


def verify_runtime_paths(environment: dict[str, str]) -> None:
    """Verify writable work storage and an indexed immutable script release."""

    if os.geteuid() == 0:
        raise ValueError("runtime acceptance must run as the Executor non-root user")
    work_root = Path(environment["TASK_EXECUTOR_WORK_ROOT"])
    script_root = Path(environment["TASK_EXECUTOR_SCRIPT_ROOT"])
    if not work_root.is_dir() or not os.access(work_root, os.R_OK | os.W_OK | os.X_OK):
        raise ValueError("Executor work root is not accessible for read and write")
    if not script_root.is_dir() or not os.access(script_root, os.R_OK | os.X_OK):
        raise ValueError("Executor script root is not readable")
    package_index = script_root / "package-index.json"
    try:
        index = json.loads(package_index.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("Executor package index is missing or invalid") from error
    if not isinstance(index, dict) or not index:
        raise ValueError("Executor package index is empty")


def main(argv: Sequence[str] | None = None) -> int:
    """Run task runtime configuration acceptance."""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path,
                        default=Path("/opt/yuyutian/mytools/config/services.env"))
    parser.add_argument("--require-runtime-paths", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        environment = verify_environment_file(arguments.env_file)
        verify_values(environment)
        if arguments.require_runtime_paths:
            verify_runtime_paths(environment)
    except (OSError, ValueError) as error:
        print(json.dumps({"ready": False, "error": str(error)}, separators=(",", ":")))
        return 2
    print(json.dumps({"ready": True, "runtimePaths": arguments.require_runtime_paths},
                     separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
