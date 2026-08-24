#!/usr/bin/env python3
"""Validate that task scripts receive every explicitly referenced node environment variable."""

from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path
import sys

EXECUTOR_VARIABLES = {
    "LANG",
    "PATH",
    "PYTHONPATH",
    "TASK_API_URL",
    "TASK_CONTEXT_FILE",
    "TASK_EXECUTION_ID",
    "TASK_EXECUTOR_NODE_AFFINITY",
    "TASK_LEASE_TOKEN_FILE",
    "TASK_RESULT_FILE",
    "TASK_WORK_DIR",
}


def configured_environments(path: Path) -> dict[str, set[str]]:
    """Parse the constrained script-environments section without evaluating YAML values."""
    result: dict[str, set[str]] = {}
    active = False
    package: str | None = None
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if raw_line == "  script-environments:":
            active = True
            continue
        if active and raw_line and not raw_line.startswith("    "):
            break
        if not active or not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        stripped = raw_line.strip()
        if raw_line.startswith("    ") and not raw_line.startswith("      ") and stripped.endswith(":"):
            package = stripped[:-1]
            result.setdefault(package, set())
        elif raw_line.startswith("      ") and package is not None and ":" in stripped:
            result[package].add(stripped.split(":", 1)[0])
    return result


def environment_references(path: Path) -> set[str]:
    """Extract literal os.getenv and os.environ references from one Python script."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    references: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and node.args and isinstance(node.args[0], ast.Constant):
            function = node.func
            direct_getenv = (isinstance(function, ast.Attribute) and function.attr == "getenv"
                             and isinstance(function.value, ast.Name) and function.value.id == "os")
            environ_get = (isinstance(function, ast.Attribute) and function.attr == "get"
                           and isinstance(function.value, ast.Attribute)
                           and function.value.attr == "environ"
                           and isinstance(function.value.value, ast.Name)
                           and function.value.value.id == "os")
            if (direct_getenv or environ_get) and isinstance(node.args[0].value, str):
                references.add(node.args[0].value)
        if isinstance(node, ast.Subscript) and isinstance(node.value, ast.Attribute):
            environ = node.value
            if (environ.attr == "environ" and isinstance(environ.value, ast.Name)
                    and environ.value.id == "os" and isinstance(node.slice, ast.Constant)
                    and isinstance(node.slice.value, str)):
                references.add(node.slice.value)
    return references


def evaluate(service_root: Path, application_yml: Path) -> dict[str, object]:
    """Compare all package script references with the Executor package environment map."""
    configured = configured_environments(application_yml)
    missing: dict[str, list[str]] = {}
    scripts = sorted(service_root.glob("**/packages/*/*/scripts/*.py"))
    checked_packages: set[str] = set()
    for script in scripts:
        package = script.parts[-4]
        references = environment_references(script) - EXECUTOR_VARIABLES
        checked_packages.add(package)
        absent = sorted(references - configured.get(package, set()))
        if absent:
            missing.setdefault(package, []).extend(absent)
    normalized = {package: sorted(set(names)) for package, names in sorted(missing.items())}
    return {"ready": not normalized, "checkedPackageCount": len(checked_packages),
            "configuredPackageCount": len(configured), "missing": normalized}


def main() -> int:
    """Run the read-only Executor environment contract gate."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--service-root", type=Path, default=Path(__file__).parents[1])
    parser.add_argument("--application-yml", type=Path)
    arguments = parser.parse_args()
    application_yml = arguments.application_yml or (
        arguments.service_root / "task-executor-service/src/main/resources/application.yml")
    report = evaluate(arguments.service_root, application_yml)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["ready"] else 1


if __name__ == "__main__":
    sys.exit(main())
