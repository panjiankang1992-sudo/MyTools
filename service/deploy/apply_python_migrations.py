#!/usr/bin/env python3
"""Validate and apply versioned migrations for Python MyTools services."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import re
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

MIGRATION_NAME = re.compile(r"^V([1-9][0-9]*)__([a-z0-9_]+)\.sql$")
FORBIDDEN_SQL = re.compile(r"\b(?:DROP\s+DATABASE|CREATE\s+DATABASE|TRUNCATE\s+TABLE|DELETE\s+FROM|USE)\b", re.IGNORECASE)


@dataclass(frozen=True)
class Migration:
    """Describe one immutable SQL migration file."""

    version: int
    description: str
    path: Path
    checksum: str
    sql: str


def load_initializer(directory: Path) -> Any:
    """Load shared deployment manifest and environment parsing helpers."""

    path = directory / "initialize_schemas.py"
    spec = importlib.util.spec_from_file_location("initialize_schemas_shared", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load deployment helper: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def discover_migrations(service_directory: Path) -> list[Migration]:
    """Discover ordered, contiguous, and non-destructive migration files."""

    migrations: list[Migration] = []
    migration_directory = service_directory / "db" / "migrations"
    for path in sorted(migration_directory.glob("V*__*.sql")):
        match = MIGRATION_NAME.fullmatch(path.name)
        if match is None:
            raise ValueError(f"invalid migration name: {path}")
        sql = path.read_text(encoding="utf-8")
        if FORBIDDEN_SQL.search(sql):
            raise ValueError(f"forbidden destructive or cross-schema SQL: {path}")
        migrations.append(
            Migration(
                version=int(match.group(1)),
                description=match.group(2),
                path=path,
                checksum=hashlib.sha256(sql.encode("utf-8")).hexdigest(),
                sql=sql,
            )
        )
    versions = [migration.version for migration in migrations]
    if not migrations or versions != list(range(1, len(migrations) + 1)):
        raise ValueError(f"migration versions must be contiguous from V1: {service_directory.name}")
    return migrations


def python_services(manifest: dict[str, Any], service_root: Path) -> list[tuple[dict[str, Any], list[Migration]]]:
    """Return stateful Python services and their validated migrations."""

    result: list[tuple[dict[str, Any], list[Migration]]] = []
    for service in manifest["services"]:
        if service["runtime"] == "python":
            directory = service_root / service["name"]
            if not directory.is_dir():
                directory = service_root / "python-src" / service["name"]
            result.append((service, discover_migrations(directory)))
    return result


def ensure_history(cursor: Any) -> None:
    """Create the Python migration history table in the current new Schema."""

    cursor.execute(
        """
        CREATE TABLE IF NOT EXISTS mytools_schema_history (
            installed_rank INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            version INT NOT NULL UNIQUE,
            description VARCHAR(200) NOT NULL,
            script VARCHAR(255) NOT NULL,
            checksum CHAR(64) NOT NULL,
            installed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
            execution_time_ms BIGINT NOT NULL,
            success BOOLEAN NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """
    )


def applied_migrations(cursor: Any) -> dict[int, str]:
    """Read successful applied versions and immutable checksums."""

    cursor.execute("SELECT version, checksum FROM mytools_schema_history WHERE success = TRUE ORDER BY version")
    return {int(version): str(checksum) for version, checksum in cursor.fetchall()}


def apply_service(service: dict[str, Any], migrations: list[Migration], environment: dict[str, str]) -> int:
    """Apply pending migrations to exactly one manifest-declared Schema."""

    try:
        import pymysql
        from pymysql.constants import CLIENT
    except ImportError as error:
        raise RuntimeError("PyMySQL is required; run with uv --with pymysql") from error
    prefix = service["dbPrefix"]
    username = environment.get(f"{prefix}_DB_USER") or environment.get(f"{prefix}_DB_USERNAME", "")
    password = environment.get(f"{prefix}_DB_PASSWORD", "")
    if not username or not password:
        raise ValueError(f"missing database credentials for {service['name']}")
    connection = pymysql.connect(
        host=environment.get(f"{prefix}_DB_HOST", "127.0.0.1"),
        port=int(environment.get(f"{prefix}_DB_PORT", "3306")),
        user=username,
        password=password,
        database=service["schema"],
        autocommit=False,
        client_flag=CLIENT.MULTI_STATEMENTS,
    )
    applied_count = 0
    try:
        with connection.cursor() as cursor:
            ensure_history(cursor)
            existing = applied_migrations(cursor)
            for migration in migrations:
                checksum = existing.get(migration.version)
                if checksum is not None:
                    if checksum != migration.checksum:
                        raise ValueError(
                            f"checksum mismatch: {service['name']} {migration.path.name}"
                        )
                    continue
                import time

                started = time.monotonic()
                cursor.execute(migration.sql)
                while cursor.nextset():
                    pass
                elapsed = int((time.monotonic() - started) * 1000)
                cursor.execute(
                    """
                    INSERT INTO mytools_schema_history
                        (version, description, script, checksum, execution_time_ms, success)
                    VALUES (%s, %s, %s, %s, %s, TRUE)
                    """,
                    (migration.version, migration.description, migration.path.name, migration.checksum, elapsed),
                )
                connection.commit()
                applied_count += 1
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()
    return applied_count


def main(argv: Sequence[str] | None = None) -> int:
    """Validate all Python migrations and optionally apply them in manifest order."""

    directory = Path(__file__).resolve().parent
    service_root = directory.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=directory / "services.json")
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--apply", action="store_true")
    arguments = parser.parse_args(argv)
    try:
        initializer = load_initializer(directory)
        manifest = initializer.load_manifest(arguments.manifest)
        services = python_services(manifest, service_root)
        if not arguments.apply:
            for service, migrations in services:
                print(f"{service['name']}: {len(migrations)} migrations")
            print(f"Python migration plan validated: {sum(len(item[1]) for item in services)}")
            return 0
        if arguments.env_file is None:
            raise ValueError("--env-file is required with --apply")
        environment = initializer.parse_env_file(arguments.env_file)
        applied = sum(apply_service(service, migrations, environment) for service, migrations in services)
    except (OSError, ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 2
    print(f"Python migrations applied: {applied}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
