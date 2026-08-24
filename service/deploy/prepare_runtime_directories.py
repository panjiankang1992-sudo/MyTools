#!/usr/bin/env python3
"""Create exact remote MyTools runtime and per-service log directories safely."""
from __future__ import annotations

import argparse
import json
import os
import pwd
from collections.abc import Sequence
from pathlib import Path
from typing import Any

DEPLOYMENT_ROOT = Path("/opt/yuyutian/mytools")
LOG_ROOT = Path("/opt/yuyutian/logs/mytools")


def load_manifest(path: Path) -> dict[str, Any]:
    """Load the authoritative roots and service names."""
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("deploymentRoot") != str(DEPLOYMENT_ROOT) \
            or value.get("logRoot") != str(LOG_ROOT):
        raise ValueError("service manifest roots are invalid")
    entries = value.get("services", []) + value.get("statelessServices", [])
    names = [item.get("name") for item in entries if isinstance(item, dict)]
    if len(names) != len(entries) or len(set(names)) != len(names) \
            or any(not isinstance(name, str) or not name.replace("-", "").isalnum()
                   for name in names):
        raise ValueError("service manifest names are invalid")
    return value


def paths(manifest: dict[str, Any]) -> list[Path]:
    """Return only exact managed directories, excluding business data roots."""
    entries = manifest["services"] + manifest["statelessServices"]
    return [DEPLOYMENT_ROOT, DEPLOYMENT_ROOT / "config", DEPLOYMENT_ROOT / "releases",
            DEPLOYMENT_ROOT / "runtime", DEPLOYMENT_ROOT / "runtime" / "tasks",
            DEPLOYMENT_ROOT / "migration", LOG_ROOT,
            *(LOG_ROOT / entry["name"] for entry in entries)]


def identity(name: str) -> tuple[int, int]:
    """Resolve the non-root service account."""
    if not name or name == "root":
        raise ValueError("service user is invalid")
    try:
        value = pwd.getpwnam(name)
    except KeyError as error:
        raise ValueError("service user does not exist") from error
    return value.pw_uid, value.pw_gid


def prepare(manifest: dict[str, Any], uid: int, gid: int) -> list[str]:
    """Create or normalize only exact directories without following symbolic links."""
    prepared = []
    for path in paths(manifest):
        if path.is_symlink():
            raise ValueError(f"managed directory is a symbolic link: {path}")
        path.mkdir(mode=0o750, parents=True, exist_ok=True)
        if not path.is_dir():
            raise ValueError(f"managed path is not a directory: {path}")
        os.chown(path, uid, gid, follow_symlinks=False)
        os.chmod(path, 0o750, follow_symlinks=False)
        prepared.append(str(path))
    return prepared


def main(argv: Sequence[str] | None = None) -> int:
    """Validate or create the exact remote runtime directory set."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(DEPLOYMENT_ROOT / "releases" / "current"
                                                   / "services.json"))
    parser.add_argument("--service-user", default="mytools")
    parser.add_argument("--execute", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        manifest = load_manifest(Path(arguments.manifest))
        managed = paths(manifest)
        if arguments.execute:
            uid, gid = identity(arguments.service_user)
            managed = [Path(item) for item in prepare(manifest, uid, gid)]
        report = {"ready": True, "directoryCount": len(managed), "prepared": arguments.execute}
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(json.dumps({"ready": False, "error": str(error)}, separators=(",", ":")))
        return 2
    print(json.dumps(report, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
