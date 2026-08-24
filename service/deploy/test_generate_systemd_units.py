"""Tests for deterministic MyTools systemd unit generation."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("generate_systemd_units.py")
SPEC = importlib.util.spec_from_file_location("generate_systemd_units", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


class GenerateSystemdUnitsTest(unittest.TestCase):
    """Verify complete, safe, and default-off startup orchestration."""

    def setUp(self) -> None:
        self.manifest = generator.load_manifest(Path(__file__).with_name("services.json"))

    def test_generates_one_unit_per_service(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            generated = generator.generate(self.manifest, Path(directory))
            service_units = [path for path in generated if path.suffix == ".service"]

        expected = len(self.manifest["services"]) + len(self.manifest["statelessServices"])
        self.assertEqual(expected, len(service_units))

    def test_units_only_reference_unified_deployment_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            generated = generator.generate(self.manifest, Path(directory))
            contents = "\n".join(path.read_text(encoding="utf-8") for path in generated)

        self.assertIn("/opt/yuyutian/mytools/releases/current", contents)
        self.assertNotIn("/opt/yuyutian/app", contents)
        self.assertNotIn("/opt/yuyutian/MyTools", contents)

    def test_default_target_excludes_sidecars_and_external_connectors(self) -> None:
        entries = self.manifest["services"] + self.manifest["statelessServices"]
        target = generator.target_unit(entries)

        for entry in entries:
            unit = f"mytools-{entry['name']}.service"
            if entry.get("defaultEnabled", True):
                self.assertIn(unit, target)
            else:
                self.assertNotIn(unit, target)

    def test_tmpfiles_does_not_manage_business_data_directories(self) -> None:
        config = generator.tmpfiles_config(self.manifest["deploymentRoot"])

        self.assertNotIn("r ", config)
        self.assertNotIn("R ", config)
        self.assertNotIn("data/downloads", config)
        self.assertNotIn("data/storage", config)

    def test_units_allow_external_business_data_mounts(self) -> None:
        unit = generator.service_unit(self.manifest["services"][0], self.manifest["deploymentRoot"])

        self.assertIn("ProtectSystem=full", unit)
        self.assertNotIn("ReadWritePaths=", unit)


if __name__ == "__main__":
    unittest.main()
