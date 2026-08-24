#!/usr/bin/env python3
"""Assemble one immutable MyTools release from verified service artifacts."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Sequence
from pathlib import Path
from typing import Any


def canonical(value: Any) -> bytes:
    """Serialize release metadata deterministically."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=True).encode()


def sha256(path: Path) -> str:
    """Return the SHA-256 of one release file."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path) -> dict[str, Any]:
    """Read and minimally validate the deployment service manifest."""
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("deploymentRoot") != "/opt/yuyutian/mytools":
        raise ValueError("service manifest deployment root is invalid")
    services = value.get("services", []) + value.get("statelessServices", [])
    if not services or any(not isinstance(item, dict) or not item.get("name") for item in services):
        raise ValueError("service manifest entries are invalid")
    return value


def java_services(manifest: dict[str, Any]) -> list[str]:
    """Return all Java service directory names in deterministic order."""
    entries = manifest["services"] + manifest["statelessServices"]
    return sorted(item["name"] for item in entries if item.get("runtime") == "java")


def python_services(manifest: dict[str, Any]) -> list[str]:
    """Return all Python service directory names in deterministic order."""
    return sorted(item["name"] for item in manifest["services"]
                  if item.get("runtime") == "python")


def build_java(service_root: Path, names: list[str]) -> None:
    """Package every Java service after the shared verification gate has passed."""
    for name in names:
        subprocess.run(["mvn", "-q", "package", "-DskipTests"],
                       cwd=service_root / name, check=True)


def resolve_jar(service_root: Path, name: str) -> Path:
    """Resolve exactly one executable Spring Boot jar for a service."""
    candidates = sorted(path for path in (service_root / name / "target").glob("*.jar")
                        if not path.name.endswith(".jar.original")
                        and not path.name.startswith("original-"))
    if len(candidates) != 1:
        raise ValueError(f"expected exactly one executable jar for {name}")
    return candidates[0]


def copy_python_project(service_root: Path, name: str, target: Path) -> None:
    """Copy only the Python package inputs required for a remote venv install."""
    source = service_root / name
    if not (source / "pyproject.toml").is_file() or not (source / "src").is_dir():
        raise ValueError(f"Python project {name} is incomplete")
    destination = target / name
    destination.mkdir(parents=True)
    shutil.copy2(source / "pyproject.toml", destination / "pyproject.toml")
    shutil.copytree(source / "src", destination / "src", ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))


def copy_deploy_tools(repository: Path, target: Path) -> None:
    """Copy deployment tools while excluding tests and operator evidence."""
    source = repository / "service" / "deploy"
    target.mkdir(parents=True)
    for path in sorted(source.iterdir()):
        if path.name.startswith("test_") or path.name.endswith(".example.json") \
                or path.name == "env.example" or path.name == "__pycache__":
            continue
        if path.is_file() and (path.suffix in {".py", ".json", ".md"}):
            shutil.copy2(path, target / path.name)


def assemble_task_packages(repository: Path, target: Path) -> None:
    """Use the existing package gate to publish the Executor script tree."""
    subprocess.run([sys.executable, str(repository / "service" / "scripts"
                                        / "assemble_executor_packages.py"),
                    "--service-root", str(repository / "service"), "--output", str(target)],
                   check=True)


def inventory(root: Path) -> list[dict[str, Any]]:
    """Build a path-independent complete release inventory."""
    values = []
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix()
        if relative == "release-manifest.json":
            continue
        values.append({"path": relative, "size": path.stat().st_size, "sha256": sha256(path)})
    return values


def assemble(repository: Path, output: Path, release_id: str,
             skip_java_build: bool = False) -> dict[str, Any]:
    """Assemble and atomically publish one complete release directory."""
    if not release_id or any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"
                             for character in release_id):
        raise ValueError("release id is invalid")
    if output.exists():
        raise ValueError("release output already exists")
    manifest_path = repository / "service" / "deploy" / "services.json"
    manifest = load_manifest(manifest_path)
    java = java_services(manifest)
    python = python_services(manifest)
    if not skip_java_build:
        build_java(repository / "service", java)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=output.name + ".", dir=output.parent))
    try:
        apps = temporary / "apps"
        apps.mkdir()
        for name in java:
            shutil.copy2(resolve_jar(repository / "service", name), apps / f"{name}.jar")
        python_root = temporary / "python-src"
        python_root.mkdir()
        for name in python:
            copy_python_project(repository / "service", name, python_root)
        copy_deploy_tools(repository, temporary / "deploy")
        shutil.copy2(manifest_path, temporary / "services.json")
        shutil.copytree(repository / "service" / "task-executor-service" / "sdk" / "python",
                        temporary / "task-executor-sdk",
                        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "target"))
        assemble_task_packages(repository, temporary / "task-packages")
        files = inventory(temporary)
        report = {"releaseId": release_id, "javaServiceCount": len(java),
                  "pythonServiceCount": len(python), "fileCount": len(files), "files": files}
        (temporary / "release-manifest.json").write_bytes(canonical(report) + b"\n")
        temporary.rename(output)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return report


def main(argv: Sequence[str] | None = None) -> int:
    """Build one local release directory ready for remote transfer."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--output", required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--skip-java-build", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        report = assemble(Path(arguments.repository).resolve(), Path(arguments.output).resolve(),
                          arguments.release_id, arguments.skip_java_build)
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError,
            json.JSONDecodeError) as error:
        print(json.dumps({"ready": False, "error": str(error)}, separators=(",", ":")))
        return 2
    print(json.dumps({key: report[key] for key in ("releaseId", "javaServiceCount",
                                                    "pythonServiceCount", "fileCount")},
                     separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
