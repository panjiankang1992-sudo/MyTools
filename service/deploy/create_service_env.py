#!/usr/bin/env python3
"""Create a private production environment file for a fresh remote MyTools deployment."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import tempfile
from collections.abc import Sequence
from pathlib import Path
from typing import Any

DEPLOYMENT_ROOT = Path("/opt/yuyutian/mytools")
LOG_ROOT = Path("/opt/yuyutian/logs/mytools")
OUTPUT = DEPLOYMENT_ROOT / "config" / "services.env"
TOKEN_KEYS = (
    "APP_CATALOG_INTERNAL_TOKEN", "ASSET_REGISTRY_INTERNAL_TOKEN", "DOWNLOAD_INGESTION_TOKEN",
    "DOWNLOAD_INTERNAL_TOKEN", "DOWNLOADBOT_PIKPAK_EXPORT_TOKEN", "DOWNLOADBOT_SNAPSHOT_EXPORT_TOKEN",
    "DRIVE_INTERNAL_TOKEN", "DRIVE_MIGRATION_INTERNAL_TOKEN", "DRIVE_STORAGE_MIGRATION_TOKEN",
    "DSH_CONNECTOR_INTERNAL_TOKEN", "GATEWAY_INTERNAL_TOKEN", "IDENTITY_INTERNAL_TOKEN",
    "IDENTITY_MIGRATION_INTERNAL_TOKEN", "LEGACY_ASSET_ADAPTER_TOKEN",
    "MEDIA_LIBRARY_INTERNAL_TOKEN", "MESSAGE_AUTOMATION_INTERNAL_TOKEN",
    "MESSAGE_PROVIDER_RESOLVER_TOKEN", "MESSAGING_INTERNAL_TOKEN", "MSGSERVICE_MIGRATION_TOKEN",
    "PIKPAK_CONNECTOR_TOKEN", "READER_INTERNAL_TOKEN", "READER_MIGRATION_INTERNAL_TOKEN",
    "STORAGE_INTERNAL_TOKEN",
)
DISABLED_FLAGS = (
    "GATEWAY_IDENTITY_ROUTE_ENABLED", "GATEWAY_READER_ROUTE_ENABLED",
    "GATEWAY_DRIVE_ROUTE_ENABLED", "GATEWAY_DOWNLOAD_ROUTE_ENABLED",
    "GATEWAY_MEDIA_ROUTE_ENABLED", "GATEWAY_MESSAGING_ROUTE_ENABLED",
    "GATEWAY_APP_CATALOG_ROUTE_ENABLED", "GATEWAY_DSH_ROUTE_ENABLED",
    "DOWNLOADBOT_LIVE_BRIDGE_ENABLED", "LEGACY_ASSET_ADAPTER_EXPORT_ENABLED",
    "MSGSERVICE_ADAPTER_IMPORT_ENABLED", "MSGSERVICE_ADAPTER_EXPORT_ENABLED",
    "ONEBOT_CONNECTOR_ENABLED", "PIKPAK_CONNECTOR_ENABLED",
    "MESSAGE_AUTOMATION_COMPLETION_RELAY_ENABLED", "MESSAGE_AUTOMATION_RELAY_ENABLED",
    "MESSAGING_EMAIL_INGRESS_ENABLED", "MESSAGING_ONEBOT_INGRESS_ENABLED",
    "DSH_CONNECTOR_RPC_ENABLED", "MESSAGING_MAIL_HEALTH_ENABLED",
)


def load_manifest(path: Path) -> dict[str, Any]:
    """Load the authoritative service manifest."""
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("deploymentRoot") != str(DEPLOYMENT_ROOT):
        raise ValueError("service manifest is invalid")
    return value


def private_value(bytes_count: int = 32) -> str:
    """Generate an environment-file-safe random secret."""
    return secrets.token_urlsafe(bytes_count)


def validate_business_path(value: str, name: str) -> str:
    """Require an absolute business path outside the deployment and log roots."""
    path = Path(value)
    if not path.is_absolute():
        raise ValueError(f"{name} must be absolute")
    resolved = path.resolve(strict=False)
    for forbidden in (DEPLOYMENT_ROOT, LOG_ROOT):
        if resolved == forbidden or forbidden in resolved.parents:
            raise ValueError(f"{name} must be independent from deployment paths")
    return str(resolved)


def values(manifest: dict[str, Any], download_root: str, storage_root: str,
           media_roots: list[str], reader_storage_root: str) -> dict[str, str]:
    """Generate complete database, token, path, and default-off settings."""
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9._-]{0,127}", reader_storage_root):
        raise ValueError("reader storage root name is invalid")
    rclone_user = "mytools"
    rclone_password = private_value()
    environment = {
        "MYTOOLS_SERVICE_ROOT": str(DEPLOYMENT_ROOT),
        "MYTOOLS_LOG_ROOT": str(LOG_ROOT),
        "TASK_EXECUTOR_WORK_ROOT": str(DEPLOYMENT_ROOT / "runtime" / "tasks"),
        "TASK_EXECUTOR_SCRIPT_ROOT": str(DEPLOYMENT_ROOT / "releases" / "current" / "task-packages"),
        "TASK_EXECUTOR_PYTHON_SDK_ROOT": str(DEPLOYMENT_ROOT / "releases" / "current"
                                                / "task-executor-sdk"),
        "TASK_EXECUTOR_PYTHON_EXECUTABLE": str(DEPLOYMENT_ROOT / "releases" / "current"
                                                / "venv" / "bin" / "python3"),
        "TASK_EXECUTOR_REQUIRE_PACKAGE_INDEX": "true",
        "TASK_EXECUTOR_NODE_NAME": "executor-remote-1",
        "TASK_SCHEDULER_HTTP_PORT": "23410",
        "TASK_SCHEDULER_URL": "http://127.0.0.1:23410",
        "MSGSERVICE_MIGRATION_URL": "http://127.0.0.1:23321",
        "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": "3",
        "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE": "0",
        "DOWNLOAD_DESTINATION_ROOT": validate_business_path(download_root, "download root"),
        "STORAGE_DEFAULT_ROOT_PATH": validate_business_path(storage_root, "storage root"),
        "MEDIA_SCAN_ALLOWED_ROOTS": json.dumps(
            [validate_business_path(item, "media root") for item in media_roots], separators=(",", ":")),
        "READER_EBOOK_STORAGE_ROOT": reader_storage_root,
        "IDENTITY_JWT_SECRET": base64.b64encode(secrets.token_bytes(48)).decode(),
        "IDENTITY_VALIDATION_MODE": "LEGACY",
        "DOWNLOADBOT_ADAPTER_MODE": "DISABLED",
        "RCLONE_RC_URL": "http://127.0.0.1:5572",
        "RCLONE_RC_USER": rclone_user,
        "RCLONE_RC_PASSWORD": rclone_password,
        "STORAGE_RCLONE_RC_URL": "http://127.0.0.1:5572",
        "STORAGE_RCLONE_RC_USER": rclone_user,
        "STORAGE_RCLONE_RC_PASSWORD": rclone_password,
    }
    for key in TOKEN_KEYS:
        environment[key] = private_value()
    environment["LEGACY_ASSET_ADAPTER_INTERNAL_TOKEN"] = environment["LEGACY_ASSET_ADAPTER_TOKEN"]
    environment["MSGSERVICE_ADAPTER_INTERNAL_TOKEN"] = environment["MSGSERVICE_MIGRATION_TOKEN"]
    for key in DISABLED_FLAGS:
        environment[key] = "false"
    for entry in manifest["services"]:
        if entry.get("schema"):
            prefix = entry["dbPrefix"]
            environment[f"{prefix}_DB_USER"] = f"mytools_{entry['name'].removesuffix('-service').replace('-', '_')}"
            environment[f"{prefix}_DB_PASSWORD"] = private_value()
    environment["TASK_DB_USERNAME"] = environment.pop("TASK_DB_USER")
    return environment


def encode(environment: dict[str, str]) -> bytes:
    """Encode sorted shell-compatible assignments without interpolation syntax."""
    for key, value in environment.items():
        if not key.replace("_", "").isalnum() or not key[0].isalpha() \
                or any(character in value for character in "\n\r\0"):
            raise ValueError("environment value is invalid")
    return "".join(f"{key}={value}\n" for key, value in sorted(environment.items())).encode()


def write_private(path: Path, content: bytes) -> None:
    """Create a non-overwriting private environment file atomically."""
    if path != OUTPUT:
        raise ValueError("environment output must be /opt/yuyutian/mytools/config/services.env")
    if path.exists():
        raise ValueError("environment output already exists")
    path.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix="services.env.", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def main(argv: Sequence[str] | None = None) -> int:
    """Generate a remote environment file without printing any secret."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEPLOYMENT_ROOT / "releases" / "current"
                                                   / "services.json"))
    parser.add_argument("--output", default=str(OUTPUT))
    parser.add_argument("--download-root", required=True)
    parser.add_argument("--storage-root", required=True)
    parser.add_argument("--media-root", action="append", default=[])
    parser.add_argument("--reader-storage-root", default="managed")
    parser.add_argument("--execute", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        environment = values(load_manifest(Path(arguments.manifest)), arguments.download_root,
                             arguments.storage_root, arguments.media_root,
                             arguments.reader_storage_root)
        content = encode(environment)
        if arguments.execute:
            write_private(Path(arguments.output), content)
        report = {"ready": True, "keyCount": len(environment),
                  "contentSha256": hashlib.sha256(content).hexdigest(),
                  "written": arguments.execute}
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(json.dumps({"ready": False, "error": str(error)}, separators=(",", ":")))
        return 2
    print(json.dumps(report, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
