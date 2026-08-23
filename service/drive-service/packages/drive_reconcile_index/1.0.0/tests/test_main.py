"""Tests for deterministic Drive index reconciliation."""

import importlib.util
import io
import json
from pathlib import Path
import unittest

SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py"
SPEC=importlib.util.spec_from_file_location("drive_reconcile_index",SCRIPT)
MODULE=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)


class Response:
    """Minimal digest response."""
    def __init__(self,payload): self.stream=io.BytesIO(json.dumps(payload).encode())
    def __enter__(self): return self
    def __exit__(self,*_args): self.stream.close()
    def read(self):
        """Read response bytes."""
        return self.stream.read()


class DriveReconcileIndexTest(unittest.TestCase):
    """Validate explicit match and mismatch results."""
    def test_reports_digest_match(self):
        """Equal count and digest are required for a match."""
        def opener(_request,timeout): return Response({"itemCount":2,"contentSha256":"a"*64})
        result=MODULE.execute({"accountId":"a","storageOperationId":"o"},"http://drive","d",
                              "http://storage","s",opener)
        self.assertTrue(result["matched"])


if __name__=="__main__": unittest.main()
