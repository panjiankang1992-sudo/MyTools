import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_migrate_drive_providers_1_1", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Client:
    def __init__(self, items):
        self.items = items
        self.registered = []
        self.bound = []

    def page(self, after_id):
        return {"items": self.items, "nextAfterId": None}

    def register(self, payload):
        self.registered.append(payload)
        return "00000000-0000-4000-8000-000000000099"

    def bind(self, account_id, provider_id):
        self.bound.append((account_id, provider_id))


def account(**changes):
    value = {"id": "00000000-0000-4000-8000-000000000001", "providerType": "RCLONE",
             "remoteKey": "primary", "providerSecretRef": "secret://drive/primary", "enabled": True}
    value.update(changes)
    return value


class StorageProviderMigrationTest(unittest.TestCase):
    def test_dry_run_and_live_run_have_same_source_digest(self):
        dry_client = Client([account()])
        dry_result = MODULE.execute(dry_client, "drive-provider-v1", True)
        live_client = Client([account()])
        live_result = MODULE.execute(live_client, "drive-provider-v1", False)

        self.assertEqual(dry_result["digestSha256"], live_result["digestSha256"])
        self.assertEqual(1, dry_result["accepted"])
        self.assertEqual(0, dry_result["bound"])
        self.assertEqual([], dry_client.registered)
        self.assertEqual(1, live_result["bound"])
        self.assertNotIn("endpointUri", live_client.registered[0])

    def test_maps_native_s3_routing_without_secret_material(self):
        client = Client([account(providerType="S3", remoteKey="reader-bucket",
                                 endpointUri="https://s3.example.com", regionName="test-region-1",
                                 providerSecretRef="env://S3_SECRET")])

        result = MODULE.execute(client, "drive-provider-v1", False)

        self.assertEqual(1, result["bound"])
        self.assertEqual("S3", client.registered[0]["providerType"])
        self.assertEqual("env://S3_SECRET", client.registered[0]["secretRef"])
        self.assertFalse(any(key in client.registered[0] for key in ("username", "password", "token")))

    def test_rejects_export_rows_containing_raw_credentials(self):
        client = Client([account(username="unsafe", password="unsafe")])

        result = MODULE.execute(client, "drive-provider-v1", False)

        self.assertEqual(1, result["exported"])
        self.assertEqual(1, result["rejected"])
        self.assertEqual([], client.registered)

    def test_reports_resume_cursor_and_rejects_string_boolean(self):
        client = Client([account(enabled="false")])

        result = MODULE.execute(client, "drive-provider-v1", True, "previous-id")

        self.assertEqual(1, result["rejected"])
        self.assertEqual("00000000-0000-4000-8000-000000000001", result["lastAfterId"])


if __name__ == "__main__":
    unittest.main()
