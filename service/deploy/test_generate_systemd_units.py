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
            service_units = [
                path
                for path in generated
            if path.suffix == ".service" and path.name not in {
                "mytools-logrotate.service", "mytools-onebot-relogin.service"}
            ]

        expected = len(self.manifest["services"]) + len(self.manifest["statelessServices"])
        self.assertEqual(expected, len(service_units))

    def test_each_service_uses_an_independent_log_file(self) -> None:
        entries = self.manifest["services"] + self.manifest["statelessServices"]
        for entry in entries:
            unit = generator.service_unit(
                entry,
                self.manifest["deploymentRoot"],
                self.manifest["logRoot"],
            )
            expected = f"/opt/yuyutian/logs/mytools/{entry['name']}/service.log"
            self.assertIn(expected, unit)
            self.assertIn("StandardError=inherit", unit)

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
        entries = self.manifest["services"] + self.manifest["statelessServices"]
        config = generator.tmpfiles_config(
            self.manifest["deploymentRoot"],
            self.manifest["logRoot"],
            entries,
        )

        self.assertNotIn("r ", config)
        self.assertNotIn("R ", config)
        self.assertNotIn("data/downloads", config)
        self.assertNotIn("data/storage", config)
        self.assertIn("/opt/yuyutian/logs/mytools/messaging-service", config)

    def test_logrotate_bounds_each_service_by_size_and_age(self) -> None:
        entries = self.manifest["services"] + self.manifest["statelessServices"]
        config = generator.logrotate_config(entries, self.manifest["logRoot"])

        self.assertEqual(len(entries), config.count("maxsize 10M"))
        self.assertEqual(len(entries), config.count("rotate 9"))
        self.assertEqual(len(entries), config.count("maxage 10"))
        self.assertEqual(len(entries), config.count("copytruncate"))

    def test_logrotate_is_checked_every_minute(self) -> None:
        timer = generator.logrotate_timer()

        self.assertIn("OnCalendar=*-*-* *:*:00", timer)
        self.assertIn("Persistent=true", timer)

    def test_units_allow_external_business_data_mounts(self) -> None:
        unit = generator.service_unit(
            self.manifest["services"][0],
            self.manifest["deploymentRoot"],
            self.manifest["logRoot"],
        )

        self.assertIn("ProtectSystem=full", unit)
        self.assertNotIn("ReadWritePaths=", unit)

    def test_onebot_relogin_units_use_only_fixed_paths_and_action(self) -> None:
        service = generator.onebot_relogin_service("/opt/yuyutian/mytools")
        path = generator.onebot_relogin_path("/opt/yuyutian/mytools")

        self.assertIn("docker restart --time 30 downloadbot-napcat", service)
        self.assertIn("rm -f /opt/napcat/cache/qrcode.png", service)
        self.assertIn("PathExists=/opt/yuyutian/mytools/runtime/onebot/relogin.request", path)
        self.assertNotIn("%", service + path)


if __name__ == "__main__":
    unittest.main()
