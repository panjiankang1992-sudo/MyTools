#!/usr/bin/env python3
"""Validate and install an assembled MyTools release on the remote deployment host."""
from __future__ import annotations

import argparse
import hashlib
import grp
import json
import os
import pwd
import shutil
import stat
import subprocess
import sys
from collections.abc import Sequence
from pathlib import Path, PurePosixPath
from typing import Any

DEPLOYMENT_ROOT = Path("/opt/yuyutian/mytools")


def sha256(path: Path) -> str:
    """Return the SHA-256 of one release file."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def validate_relative_path(value: Any) -> str:
    """Reject absolute, parent-relative, ambiguous, and hidden inventory paths."""
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError("release inventory path is invalid")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError("release inventory path is invalid")
    return value


def validate_release(source: Path) -> dict[str, Any]:
    """Require an exact regular-file inventory and validate every digest."""
    manifest_path = source / "release-manifest.json"
    if not source.is_dir() or source.is_symlink() or not manifest_path.is_file():
        raise ValueError("release source is invalid")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or not isinstance(manifest.get("files"), list):
        raise TypeError("release manifest is invalid")
    declared: dict[str, dict[str, Any]] = {}
    for item in manifest["files"]:
        if not isinstance(item, dict):
            raise TypeError("release inventory item is invalid")
        relative = validate_relative_path(item.get("path"))
        if relative in declared or not isinstance(item.get("size"), int) \
                or not isinstance(item.get("sha256"), str):
            raise ValueError("release inventory item is invalid")
        declared[relative] = item
    actual = set()
    for path in source.rglob("*"):
        if path.is_symlink():
            raise ValueError("release source contains a symbolic link")
        if path.is_file():
            relative = path.relative_to(source).as_posix()
            if relative == "release-manifest.json":
                continue
            actual.add(relative)
            item = declared.get(relative)
            if item is None or path.stat().st_size != item["size"] or sha256(path) != item["sha256"]:
                raise ValueError(f"release file validation failed: {relative}")
    if actual != set(declared):
        raise ValueError("release inventory does not match source files")
    return manifest


def validate_destination(root: Path, release_id: str) -> Path:
    """Resolve a release destination beneath the single approved remote root."""
    if root != DEPLOYMENT_ROOT:
        raise ValueError("deployment root must be /opt/yuyutian/mytools")
    if not release_id or any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"
                             for character in release_id):
        raise ValueError("release id is invalid")
    destination = root / "releases" / release_id
    if os.path.lexists(destination):
        raise ValueError("release destination already exists")
    return destination


def install_python(destination: Path, python: str) -> None:
    """Create the release-local venv and install all bundled Python services."""
    subprocess.run([python, "-c", "import sys; assert sys.version_info >= (3, 12)"], check=True)
    subprocess.run([python, "-m", "venv", str(destination / "venv")], check=True)
    pip = destination / "venv" / "bin" / "python"
    projects = sorted(path for path in (destination / "python-src").iterdir()
                      if (path / "pyproject.toml").is_file())
    if not projects:
        raise ValueError("release contains no Python projects")
    subprocess.run([str(pip), "-m", "pip", "install", "--disable-pip-version-check",
                    *map(str, projects)], check=True)


def switch_current(root: Path, destination: Path) -> None:
    """Atomically switch the current release symlink after installation succeeds."""
    current = root / "releases" / "current"
    temporary = root / "releases" / ".current.new"
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(destination.name)
    os.replace(temporary, current)


def service_identity(name: str) -> tuple[int, int]:
    """Resolve the pre-created unprivileged service account."""
    if name != "mytools":
        raise ValueError("service user is invalid")
    try:
        value = pwd.getpwnam(name)
        group = grp.getgrnam(name)
    except KeyError as error:
        raise ValueError("service identity does not exist") from error
    if value.pw_gid != group.gr_gid:
        raise ValueError("service user primary group is invalid")
    return value.pw_uid, group.gr_gid


def protect_release_tree(path: Path, gid: int) -> None:
    """Make installed executables immutable to the unprivileged service account."""

    for item in (path, *path.rglob("*")):
        metadata = item.lstat()
        if stat.S_ISLNK(metadata.st_mode):
            os.chown(item, 0, gid, follow_symlinks=False)
            continue
        if stat.S_ISDIR(metadata.st_mode):
            mode = 0o750
        elif stat.S_ISREG(metadata.st_mode):
            mode = 0o750 if metadata.st_mode & 0o111 else 0o640
        else:
            raise ValueError(f"release contains an unsupported file: {item}")
        os.chown(item, 0, gid, follow_symlinks=False)
        os.chmod(item, mode, follow_symlinks=False)


def prepare_deployment_directories(root: Path, uid: int, gid: int) -> None:
    """Prepare root-owned trust boundaries and service-owned writable roots."""

    protected = (root, root / "config", root / "releases")
    writable = (root / "runtime", root / "runtime" / "tasks", root / "runtime" / "qq",
                root / "runtime" / "onebot", root / "migration")
    for path in (*protected, *writable):
        if path.is_symlink():
            raise ValueError(f"deployment directory is a symbolic link: {path}")
        path.mkdir(mode=0o750, parents=True, exist_ok=True)
        if not path.is_dir():
            raise ValueError(f"deployment path is not a directory: {path}")
        owner = 0 if path in protected else uid
        os.chown(path, owner, gid, follow_symlinks=False)
        os.chmod(path, 0o750, follow_symlinks=False)


def install(source: Path, root: Path, python: str, service_user: str) -> dict[str, Any]:
    """Install a validated release without changing any legacy service directory."""
    manifest = validate_release(source)
    uid, gid = service_identity(service_user)
    release_id = str(manifest.get("releaseId", ""))
    destination = validate_destination(root, release_id)
    prepare_deployment_directories(root, uid, gid)
    shutil.copytree(source, destination, copy_function=shutil.copy2)
    try:
        install_python(destination, python)
        protect_release_tree(destination, gid)
        switch_current(root, destination)
    except BaseException:
        shutil.rmtree(destination, ignore_errors=True)
        raise
    return {"ready": True, "releaseId": release_id, "destination": str(destination),
            "current": str((root / "releases" / "current").resolve())}


def main(argv: Sequence[str] | None = None) -> int:
    """Install one release on the remote host."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--deployment-root", default=str(DEPLOYMENT_ROOT))
    parser.add_argument("--python", default=sys.executable)
    parser.add_argument("--service-user", default="mytools")
    parser.add_argument("--execute", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        source = Path(arguments.source).resolve()
        manifest = validate_release(source)
        destination = validate_destination(Path(arguments.deployment_root),
                                           str(manifest.get("releaseId", "")))
        if not arguments.execute:
            report = {"ready": True, "validated": True,
                      "releaseId": manifest["releaseId"], "destination": str(destination)}
        else:
            report = install(source, Path(arguments.deployment_root), arguments.python,
                             arguments.service_user)
    except (OSError, RuntimeError, TypeError, ValueError, subprocess.CalledProcessError,
            json.JSONDecodeError) as error:
        print(json.dumps({"ready": False, "error": str(error)}, separators=(",", ":")))
        return 2
    print(json.dumps(report, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
