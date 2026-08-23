"""Tests for Drive to Storage Provider migration orchestration."""

import importlib.util
import io
import json
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_migrate_drive_providers", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    """Minimal context-managed HTTP response."""
    def __init__(self, payload):
        self.stream = io.BytesIO(json.dumps(payload).encode())
    def __enter__(self):
        return self
    def __exit__(self, *_args):
        self.stream.close()
    def read(self):
        """Read response bytes."""
        return self.stream.read()


class StorageProviderMigrationTest(unittest.TestCase):
    """Validate sanitized and idempotent cross-service requests."""

    def test_registers_and_binds_sanitized_account(self):
        """No URL, username, or password is accepted from the migration view."""
        requests = []
        account_id = "00000000-0000-4000-8000-000000000001"
        def opener(request, timeout):
            requests.append((request.full_url, request.data))
            if "storage-accounts" in request.full_url:
                return Response({"items": [{"id": account_id, "remoteKey": "primary",
                    "providerSecretRef": "secret://drive/primary", "enabled": True}], "nextAfterId": None})
            if request.full_url.endswith("/providers"):
                return Response({"id": "00000000-0000-4000-8000-000000000002"})
            return Response({})
        result = MODULE.execute("http://drive", "migration", "drive", "http://storage", "storage", opener)
        provider_payload = json.loads(requests[1][1])
        self.assertEqual({"processed": 1, "bound": 1}, result)
        self.assertEqual("secret://drive/primary", provider_payload["secretRef"])
        self.assertFalse(any(key in provider_payload for key in ("url", "username", "password")))


if __name__ == "__main__":
    unittest.main()
