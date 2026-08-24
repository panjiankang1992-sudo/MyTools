"""Tests for the service architecture verification runner."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_service_architecture as verifier


class VerifyServiceArchitectureTest(unittest.TestCase):
    """Verify command planning without launching service builds."""

    def test_build_checks_contains_all_groups(self) -> None:
        with patch.object(verifier, "python_command", return_value=("python",)):
            checks = verifier.build_checks(Path("/repo"), Path("/java"), "3.12")

        names = {check.name for check in checks}
        self.assertEqual(len(verifier.JAVA_SERVICES), len([name for name in names if name.startswith("java:")]))
        self.assertEqual(len(verifier.PYTHON_SERVICES) + 3, len([name for name in names if name.startswith("python:")]))
        self.assertIn("python:architecture-gates", names)
        self.assertIn("python:deployment-gates", names)
        media_check = next(check for check in checks if check.name == "python:media-intelligence")
        self.assertIn("--import-mode=importlib", media_check.command)

    def test_build_checks_can_select_python_only(self) -> None:
        with patch.object(verifier, "python_command", return_value=("python",)):
            checks = verifier.build_checks(
                Path("/repo"),
                Path("/java"),
                "3.12",
                include_java=False,
            )

        self.assertTrue(checks)
        self.assertTrue(all(check.name.startswith("python:") for check in checks))

    def test_main_rejects_missing_java(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            status = verifier.main(("--java-home", directory, "--skip-python"))

        self.assertEqual(2, status)


if __name__ == "__main__":
    unittest.main()
