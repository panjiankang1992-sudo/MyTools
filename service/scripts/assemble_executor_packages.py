#!/usr/bin/env python3
"""Validate and assemble domain task packages into one immutable Executor release directory."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile

IDENTIFIER = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,127}$")
VERSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
MANIFEST_FIELDS = ("name", "version", "entrypoint")
EXCLUDED_PARTS = {"tests", "__pycache__", ".pytest_cache", ".ruff_cache"}


def read_manifest(path: Path) -> dict[str, str]:
    """Read required scalar fields from the constrained task package manifest."""
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([A-Za-z][A-Za-z0-9]*):\s*(.*?)\s*", line)
        if match and match.group(1) in MANIFEST_FIELDS:
            value = match.group(2)
            if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
                value = value[1:-1]
            values[match.group(1)] = value
    missing = [name for name in MANIFEST_FIELDS if not values.get(name)]
    if missing:
        raise ValueError(f"Package manifest is missing fields: {', '.join(missing)}")
    return values


def file_digest(path: Path) -> str:
    """Compute one file SHA-256 without loading it into memory."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_package(manifest_path: Path) -> dict[str, object]:
    """Validate one package identity, entrypoint, tree shape, and immutable file inventory."""
    version_root = manifest_path.parent
    package_root = version_root.parent
    manifest = read_manifest(manifest_path)
    name = manifest["name"]
    version = manifest["version"]
    if name != package_root.name or IDENTIFIER.fullmatch(name) is None:
        raise ValueError(f"Package name does not match directory: {manifest_path}")
    if version != version_root.name or VERSION.fullmatch(version) is None:
        raise ValueError(f"Package version does not match directory: {manifest_path}")
    entrypoint_value = manifest["entrypoint"]
    entrypoint_relative = Path(entrypoint_value)
    if entrypoint_relative.is_absolute() or ".." in entrypoint_relative.parts:
        raise ValueError(f"Package entrypoint is unsafe: {manifest_path}")
    entrypoint = version_root / entrypoint_relative
    if entrypoint.is_symlink() or not entrypoint.is_file():
        raise ValueError(f"Package entrypoint is missing or symbolic: {manifest_path}")
    files: list[dict[str, object]] = []
    for path in sorted(version_root.rglob("*"), key=lambda item: item.relative_to(version_root).as_posix()):
        if path.is_symlink():
            raise ValueError(f"Package tree contains a symbolic link: {path}")
        if path.is_file():
            relative_path = path.relative_to(version_root)
            if any(part in EXCLUDED_PARTS for part in relative_path.parts) or path.suffix == ".pyc":
                continue
            relative = relative_path.as_posix()
            files.append({"path": relative, "sizeBytes": path.stat().st_size,
                          "sha256": file_digest(path)})
    if not files:
        raise ValueError(f"Package contains no files: {manifest_path}")
    return {"name": name, "version": version, "entrypoint": entrypoint_value,
            "source": version_root, "files": files}


def discover(service_root: Path) -> list[dict[str, object]]:
    """Discover every domain-owned task package and reject duplicate package versions."""
    manifests = sorted(service_root.glob("*/packages/*/*/manifest.yaml"))
    packages: list[dict[str, object]] = []
    identities: set[tuple[str, str]] = set()
    for manifest in manifests:
        package = inspect_package(manifest)
        identity = (str(package["name"]), str(package["version"]))
        if identity in identities:
            raise ValueError(f"Duplicate package version: {identity[0]}:{identity[1]}")
        identities.add(identity)
        packages.append(package)
    if not packages:
        raise ValueError("No task packages were found")
    return sorted(packages, key=lambda item: (str(item["name"]), str(item["version"])))


def public_index(packages: list[dict[str, object]]) -> dict[str, object]:
    """Build a path-independent package release index."""
    entries = [{key: package[key] for key in ("name", "version", "entrypoint", "files")}
               for package in packages]
    encoded = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()
    return {"packageCount": len(entries), "contentSha256": hashlib.sha256(encoded).hexdigest(),
            "packages": entries}


def assemble(service_root: Path, output: Path | None) -> dict[str, object]:
    """Validate all packages and optionally publish them into a new atomic release directory."""
    packages = discover(service_root)
    index = public_index(packages)
    if output is None:
        return index
    destination = output.absolute()
    if destination.exists() or destination.is_symlink():
        raise ValueError("Executor package release directory already exists")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{destination.name}-", dir=destination.parent))
    try:
        for package in packages:
            target = temporary / str(package["name"]) / str(package["version"])
            source = Path(package["source"])
            for item in package["files"]:
                relative = Path(str(item["path"]))
                destination_file = target / relative
                destination_file.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source / relative, destination_file)
        (temporary / "package-index.json").write_text(
            json.dumps(index, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        os.replace(temporary, destination)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return index


def main() -> int:
    """Run validation or assemble a fresh Executor package release."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--service-root", type=Path, default=Path(__file__).parents[1])
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    try:
        index = assemble(arguments.service_root, arguments.output)
    except (OSError, ValueError) as exception:
        print(json.dumps({"ready": False, "error": str(exception)}, sort_keys=True))
        return 1
    print(json.dumps({"ready": True, "packageCount": index["packageCount"],
                      "contentSha256": index["contentSha256"],
                      "output": None if arguments.output is None else str(arguments.output)},
                     indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
