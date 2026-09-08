import importlib.util
import base64
import os
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("verify_task_runtime_config.py")
SPEC = importlib.util.spec_from_file_location("task_runtime_config", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def valid_environment(root: Path) -> dict[str, str]:
    environment = {key: f"secret-{index}" for index, key in enumerate(MODULE.TOKEN_KEYS)}
    environment.update({
        "TASK_EXECUTOR_WORK_ROOT": str(root / "work"),
        "TASK_EXECUTOR_SCRIPT_ROOT": str(root / "scripts"),
        "TASK_EXECUTOR_REQUIRE_PACKAGE_INDEX": "true",
        "TASK_EXECUTOR_REQUIRE_NON_ROOT": "true",
        "MESSAGING_REGISTRATION_MAIL_MODE": "LEGACY",
        "MESSAGING_REGISTRATION_MAIL_CANARY_PERCENT": "0",
        "MESSAGING_REGISTRATION_MAIL_ROUTING_KEY": "routing-secret",
        "MESSAGING_REGISTRATION_MAIL_DELIVERY_ENCRYPTION_KEY": base64.b64encode(bytes(32)).decode(),
        "MESSAGING_REGISTRATION_MAIL_SHADOW_HASH_KEY": "shadow-secret-with-at-least-32-characters",
    })
    return environment


class TaskRuntimeConfigTest(unittest.TestCase):
    def test_accepts_private_file_with_independent_tokens(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "services.env"
            environment = valid_environment(Path(directory))
            path.write_text("".join(f"{key}={value}\n" for key, value in environment.items()),
                            encoding="utf-8")
            os.chmod(path, 0o600)
            loaded = MODULE.verify_environment_file(path)
            MODULE.verify_values(loaded)

    def test_rejects_reused_service_token(self):
        environment = valid_environment(Path("/tmp/runtime"))
        environment[MODULE.TOKEN_KEYS[1]] = environment[MODULE.TOKEN_KEYS[0]]
        with self.assertRaisesRegex(ValueError, "independent"):
            MODULE.verify_values(environment)

    def test_rejects_open_file_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "services.env"
            path.write_text("A=B\n", encoding="utf-8")
            os.chmod(path, 0o640)
            with self.assertRaisesRegex(ValueError, "0600"):
                MODULE.verify_environment_file(path)

    def test_rejects_canary_percentage_outside_canary_mode(self):
        environment = valid_environment(Path("/tmp/runtime"))
        environment["MESSAGING_REGISTRATION_MAIL_CANARY_PERCENT"] = "10"
        with self.assertRaisesRegex(ValueError, "outside CANARY"):
            MODULE.verify_values(environment)

    def test_rejects_invalid_encryption_key_outside_legacy_mode(self):
        environment = valid_environment(Path("/tmp/runtime"))
        environment["MESSAGING_REGISTRATION_MAIL_MODE"] = "PRIMARY"
        environment["MESSAGING_REGISTRATION_MAIL_DELIVERY_ENCRYPTION_KEY"] = "invalid"
        with self.assertRaisesRegex(ValueError, "Base64"):
            MODULE.verify_values(environment)


if __name__ == "__main__":
    unittest.main()
