import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("message_send_email", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return json.dumps({"deliveryId": "00000000-0000-4000-8000-000000000001",
                           "status": "DELIVERED", "providerMessageId": "message-1"}).encode()


class MessageSendEmailTest(unittest.TestCase):

    @patch("urllib.request.urlopen", return_value=Response())
    def test_only_sends_delivery_identifier_to_internal_api(self, urlopen):
        result = MODULE.execute("00000000-0000-4000-8000-000000000001",
                                "http://messaging", "secret")

        request = urlopen.call_args.args[0]
        self.assertEqual(b"", request.data)
        self.assertNotIn("secret", request.full_url)
        self.assertEqual("DELIVERED", result["status"])


if __name__ == "__main__":
    unittest.main()
