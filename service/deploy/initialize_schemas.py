#!/usr/bin/env python3
"""Create isolated MyTools service schemas and least-privilege accounts."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Any, Sequence


SAFE_IDENTIFIER = re.compile(r"^[a-z][a-z0-9_]{0,63}$")


def load_manifest(path: Path) -> dict[str, Any]:
    """Load and structurally validate the deployment service manifest."""

    manifest = json.loads(path.read_text(encoding="utf-8"))
    services = manifest.get("services")
    if not isinstance(services, list) or not services:
        raise ValueError("manifest services must be a non-empty list")
    names: set[str] = set()
    schemas: set[str] = set()
    ports: set[int] = set()
    for service in services:
        name = service["name"]
        schema = service["schema"]
        port = service["port"]
        prefix = service["dbPrefix"]
        if name in names or schema in schemas or port in ports:
            raise ValueError(f"duplicate service deployment value: {name}")
        if not SAFE_IDENTIFIER.fullmatch(schema):
            raise ValueError(f"unsafe schema identifier: {schema}")
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", prefix):
            raise ValueError(f"unsafe database prefix: {prefix}")
        names.add(name)
        schemas.add(schema)
        ports.add(port)
    return manifest


def sql_statements(manifest: dict[str, Any], environment: dict[str, str]) -> list[tuple[str, tuple[str, ...]]]:
    """Build parameterized schema, account, and grant statements."""

    statements: list[tuple[str, tuple[str, ...]]] = []
    for service in manifest["services"]:
        schema = service["schema"]
        prefix = service["dbPrefix"]
        user = environment.get(f"{prefix}_DB_USER") or environment.get(f"{prefix}_DB_USERNAME", "")
        password = environment.get(f"{prefix}_DB_PASSWORD", "")
        if not SAFE_IDENTIFIER.fullmatch(user):
            raise ValueError(f"missing or unsafe {prefix}_DB_USER/USERNAME")
        if not password:
            raise ValueError(f"missing {prefix}_DB_PASSWORD")
        statements.extend(
            (
                (f"CREATE DATABASE IF NOT EXISTS `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci", ()),
                ("CREATE USER IF NOT EXISTS %s@'%%' IDENTIFIED BY %s", (user, password)),
                (f"ALTER USER %s@'%%' IDENTIFIED BY %s", (user, password)),
                (f"GRANT ALL PRIVILEGES ON `{schema}`.* TO %s@'%%'", (user,)),
            )
        )
    statements.append(("FLUSH PRIVILEGES", ()))
    return statements


def apply_statements(statements: list[tuple[str, tuple[str, ...]]], environment: dict[str, str]) -> None:
    """Apply initialization statements using administrator credentials."""

    try:
        import pymysql
    except ImportError as error:
        raise RuntimeError("PyMySQL is required; run with uv --with pymysql") from error
    connection = pymysql.connect(
        host=environment.get("MYTOOLS_NEW_DB_ADMIN_HOST", "127.0.0.1"),
        port=int(environment.get("MYTOOLS_NEW_DB_ADMIN_PORT", "3306")),
        user=environment.get("MYTOOLS_NEW_DB_ADMIN_USER", "root"),
        password=environment.get("MYTOOLS_NEW_DB_ADMIN_PASSWORD", ""),
        autocommit=False,
    )
    try:
        with connection.cursor() as cursor:
            for statement, parameters in statements:
                cursor.execute(statement, parameters)
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def parse_env_file(path: Path) -> dict[str, str]:
    """Read a simple KEY=VALUE environment file without shell evaluation."""

    values = dict(os.environ)
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        key, separator, value = stripped.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError(f"invalid environment line: {line}")
        values.setdefault(key, value)
    return values


def main(argv: Sequence[str] | None = None) -> int:
    """Validate configuration and optionally initialize the new database server."""

    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=directory / "services.json")
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        manifest = load_manifest(arguments.manifest)
        environment = parse_env_file(arguments.env_file)
        statements = sql_statements(manifest, environment)
        if arguments.apply:
            apply_statements(statements, environment)
    except (OSError, ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 2
    action = "initialized" if arguments.apply else "validated"
    print(f"Service schemas {action}: {len(manifest['services'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
