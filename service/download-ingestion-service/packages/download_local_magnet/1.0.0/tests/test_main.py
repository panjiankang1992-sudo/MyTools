from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

SPEC = spec_from_file_location("download_local_magnet", Path(__file__).parents[1] / "scripts/main.py")
MODULE = module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)


class Storage:
    def publish(self, _path, _root, relative, _key, _size, _digest):
        return "storage://managed/" + relative


class Context:
    def __init__(self, request_id):
        self.parameters = {"downloadRequestId": request_id,
                           "magnetUri": "magnet:?xt=urn:btih:" + "a" * 40}
        self.children = []

    def create_child(self, name, parameters, key, **_kwargs):
        child = SimpleNamespace(id=uuid4(), name=name, parameters=parameters, key=key)
        self.children.append(child)
        return child

    def get_task(self, _task_id):
        return {"status": "SUCCEEDED"}


def test_execute_resumes_and_creates_one_child(tmp_path, monkeypatch):
    request_id = str(uuid4())
    monkeypatch.setenv("LOCAL_MAGNET_STAGING_ROOT", str(tmp_path))
    context = Context(request_id)

    def runner(_uri, root, _maximum, _binary):
        (root / "album").mkdir()
        (root / "album" / "video.mp4").write_bytes(b"video")

    monkeypatch.setattr(MODULE, "wait_all_or_cancel", lambda *_args: None)
    result = MODULE.execute(context, Storage(), runner)
    assert result["itemCount"] == 1
    assert context.children[0].name == "download_storage_object"
    assert context.children[0].parameters["sourceStorageUri"].startswith("storage://")


def test_validate_magnet_rejects_invalid_uri():
    try:
        MODULE.validate_magnet("magnet:?dn=missing")
    except ValueError:
        return
    raise AssertionError("invalid magnet must fail")
