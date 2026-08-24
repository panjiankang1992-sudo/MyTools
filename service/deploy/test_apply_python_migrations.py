"""Tests for Python service migration discovery and safety."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("apply_python_migrations.py")
SPEC = importlib.util.spec_from_file_location("apply_python_migrations", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
migrator = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = migrator
SPEC.loader.exec_module(migrator)


class ApplyPythonMigrationsTest(unittest.TestCase):
    """Verify complete discovery, checksums, and destructive SQL rejection."""

    def test_discovers_every_python_service_migration(self) -> None:
        directory = Path(__file__).resolve().parent
        initializer = migrator.load_initializer(directory)
        manifest = initializer.load_manifest(directory / "services.json")
        services = migrator.python_services(manifest, directory.parent)

        self.assertEqual(5, len(services))
        self.assertEqual(13, sum(len(migrations) for _, migrations in services))
        self.assertTrue(all(len(migration.checksum) == 64 for _, items in services for migration in items))

    def test_rejects_non_contiguous_versions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            migrations = Path(directory) / "db" / "migrations"
            migrations.mkdir(parents=True)
            (migrations / "V2__late.sql").write_text("CREATE TABLE late (id INT);", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "contiguous"):
                migrator.discover_migrations(Path(directory))

    def test_deployed_python_source_layout_is_supported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            migrations = root / "python-src" / "worker" / "db" / "migrations"
            migrations.mkdir(parents=True)
            (migrations / "V1__create_worker.sql").write_text(
                "CREATE TABLE worker(id INT);\n", encoding="utf-8")
            manifest = {"services": [{"name": "worker", "runtime": "python"}]}

            services = migrator.python_services(manifest, root)

            self.assertEqual(1, len(services[0][1]))

    def test_rejects_destructive_database_sql(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            migrations = Path(directory) / "db" / "migrations"
            migrations.mkdir(parents=True)
            (migrations / "V1__unsafe.sql").write_text("DROP DATABASE legacy;", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "forbidden"):
                migrator.discover_migrations(Path(directory))


if __name__ == "__main__":
    unittest.main()
