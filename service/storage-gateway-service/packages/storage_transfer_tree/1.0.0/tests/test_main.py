import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "main.py"
SPEC = importlib.util.spec_from_file_location("storage_transfer_tree", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_execute_uses_only_opaque_operation_identity_and_polls():
    class Client:
        def __init__(self):
            self.polls = 0

        def start(self, operation_id):
            assert operation_id == "00000000-0000-4000-8000-000000000001"
            return {"remoteJobId": 77}

        def status(self, operation_id):
            self.polls += 1
            return {"jobId": 77, "finished": self.polls == 2,
                    "success": self.polls == 2, "errorCode": None}

    sleeps = []
    result = MODULE.execute(
        {"operationId": "00000000-0000-4000-8000-000000000001"},
        Client(), poll_seconds=0.1, sleeper=sleeps.append,
    )

    assert result == {"operationId": "00000000-0000-4000-8000-000000000001",
                      "remoteJobId": 77, "status": "SUCCEEDED"}
    assert sleeps == [0.1]


def test_execute_fails_closed_when_remote_job_fails():
    class Client:
        def start(self, _operation_id):
            return {"remoteJobId": 78}

        def status(self, _operation_id):
            return {"jobId": 78, "finished": True, "success": False,
                    "errorCode": "STORAGE_014"}

    try:
        MODULE.execute({"operationId": "id"}, Client(), sleeper=lambda _: None)
        assert False, "expected transfer failure"
    except RuntimeError as exception:
        assert str(exception) == "Storage remote transfer failed"
