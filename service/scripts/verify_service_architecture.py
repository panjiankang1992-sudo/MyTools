#!/usr/bin/env python3
"""Run the repeatable local verification suite for the service architecture."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence


JAVA_SERVICES = (
    "task-scheduler-service",
    "task-executor-service",
    "storage-gateway-service",
    "asset-registry-service",
    "media-library-service",
    "reader-service",
    "drive-service",
    "identity-service",
    "messaging-service",
    "message-automation-service",
    "mytools-gateway",
    "app-catalog-service",
    "dsh-connector-service",
    "pikpak-connector-service",
)

PYTHON_SERVICES = (
    "download-ingestion-service",
    "downloadbot-adapter-service",
    "legacy-asset-adapter-service",
    "msgservice-adapter-service",
    "onebot-connector-service",
)


@dataclass(frozen=True)
class Check:
    """Describe one verification command and its execution environment."""

    name: str
    command: tuple[str, ...]
    working_directory: Path
    environment: Mapping[str, str] | None = None


def repository_root() -> Path:
    """Return the repository root resolved from this script location."""

    return Path(__file__).resolve().parents[2]


def python_command(python_version: str) -> tuple[str, ...]:
    """Build an isolated Python command without modifying service projects."""

    uv = shutil.which("uv")
    if uv is None:
        raise RuntimeError("uv is required for Python verification")
    return (
        uv,
        "run",
        "--no-project",
        "--python",
        python_version,
        "--with",
        "pytest",
        "--with",
        "pymysql",
        "--with",
        "pyyaml",
        "python",
    )


def build_checks(
    root: Path,
    java_home: Path,
    python_version: str,
    include_java: bool = True,
    include_python: bool = True,
) -> list[Check]:
    """Build verification checks in stable dependency order."""

    checks: list[Check] = []
    if include_java:
        java_environment = {"JAVA_HOME": str(java_home)}
        checks.extend(
            Check(
                name=f"java:{service}",
                command=("mvn", "-q", "test"),
                working_directory=root / "service" / service,
                environment=java_environment,
            )
            for service in JAVA_SERVICES
        )

    if include_python:
        python = python_command(python_version)
        sdk = root / "service" / "task-executor-service" / "sdk" / "python"
        for service in PYTHON_SERVICES:
            directory = root / "service" / service
            checks.append(
                Check(
                    name=f"python:{service}",
                    command=python + ("-m", "pytest", "-q"),
                    working_directory=directory,
                    environment={"PYTHONPATH": os.pathsep.join((str(directory / "src"), str(sdk)))},
                )
            )

        media_tests = tuple(
            str(path.parent)
            for path in sorted(
                (root / "service" / "media-intelligence" / "packages").glob(
                    "*/1.0.0/tests/test_*.py"
                )
            )
        )
        checks.append(
            Check(
                name="python:media-intelligence",
                command=python
                + ("-m", "pytest", "-q", "--import-mode=importlib", *media_tests),
                working_directory=root,
            )
        )
        checks.append(
            Check(
                name="python:architecture-gates",
                command=python
                + (
                    "-m",
                    "unittest",
                    "discover",
                    "-s",
                    "service/scripts",
                    "-p",
                    "test_*.py",
                ),
                working_directory=root,
            )
        )
        checks.append(
            Check(
                name="python:deployment-gates",
                command=python
                + (
                    "-m",
                    "unittest",
                    "discover",
                    "-s",
                    "service/deploy",
                    "-p",
                    "test_*.py",
                ),
                working_directory=root,
            )
        )
    return checks


def run_checks(checks: Sequence[Check], dry_run: bool = False) -> int:
    """Run checks until completion and return a process-style status code."""

    failures: list[str] = []
    for index, check in enumerate(checks, start=1):
        command = " ".join(check.command)
        print(f"[{index}/{len(checks)}] {check.name}: {command}", flush=True)
        if dry_run:
            continue
        environment = os.environ.copy()
        if check.environment:
            environment.update(check.environment)
        completed = subprocess.run(
            check.command,
            cwd=check.working_directory,
            env=environment,
            check=False,
        )
        if completed.returncode != 0:
            failures.append(check.name)

    if failures:
        print(f"Verification failed: {', '.join(failures)}", file=sys.stderr)
        return 1
    print(f"Verification passed: {len(checks)} checks", flush=True)
    return 0


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    """Parse command-line arguments for the verification runner."""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--java-home",
        type=Path,
        default=Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")),
        help="Java 21 home used by Maven checks",
    )
    parser.add_argument("--python-version", default="3.12")
    parser.add_argument("--skip-java", action="store_true")
    parser.add_argument("--skip-python", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    """Validate prerequisites and execute the selected verification groups."""

    args = parse_args(argv)
    if not args.skip_java:
        java = args.java_home / "bin" / "java"
        if not java.is_file():
            print(f"Java executable not found: {java}", file=sys.stderr)
            return 2
    try:
        checks = build_checks(
            repository_root(),
            args.java_home,
            args.python_version,
            include_java=not args.skip_java,
            include_python=not args.skip_python,
        )
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 2
    if not checks:
        print("No verification group selected", file=sys.stderr)
        return 2
    return run_checks(checks, dry_run=args.dry_run)


if __name__ == "__main__":
    raise SystemExit(main())
