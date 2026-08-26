from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4
import shutil
import zipfile

SPEC = spec_from_file_location("download_local_import", Path(__file__).parents[1] / "scripts/main.py")
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Storage:
    def __init__(self, source):
        self.source = source

    def download(self, _uri, target, maximum):
        assert self.source.stat().st_size <= maximum
        shutil.copy2(self.source, target)
        return target.stat().st_size

    def publish(self, _path, _root, relative, _key, _size, _digest):
        return "storage://managed/" + relative


class Context:
    def __init__(self, request_id, file_name):
        self.parameters = {"downloadRequestId": request_id,
                           "sourceStorageUri": "storage://legacy/import",
                           "fileName": file_name, "ownerId": 7}
        self.children = []

    def create_child(self, name, parameters, key, **_kwargs):
        child = SimpleNamespace(id=uuid4(), name=name, parameters=parameters, key=key)
        self.children.append(child)
        return child

    def get_task(self, _task_id):
        return {"status": "SUCCEEDED"}


def test_archive_creates_classified_children_and_album(tmp_path, monkeypatch):
    archive = tmp_path / "photos.zip"
    with zipfile.ZipFile(archive, "w") as output:
        output.writestr("Beach/a.jpg", b"a")
        output.writestr("Beach/b.jpg", b"b")
    context = Context(str(uuid4()), archive.name)

    def extract(source, destination, _binary):
        with zipfile.ZipFile(source) as value:
            value.extractall(destination)

    monkeypatch.setenv("TASK_WORK_DIR", str(tmp_path / "work"))
    (tmp_path / "work").mkdir()
    monkeypatch.setattr(MODULE, "extract_archive", extract)
    monkeypatch.setattr(MODULE, "wait_all_or_cancel", lambda *_args: None)
    result = MODULE.execute(context, Storage(archive))

    assert result["archiveExtracted"] is True
    assert result["itemCount"] == 2
    assert {child.name for child in context.children} == {"download_storage_object"}
    assert len({child.parameters["albumFolder"] for child in context.children}) == 1
    assert context.children[0].parameters["albumFolder"].startswith("Beach--")
    assert [child.parameters["sourceIndex"] for child in context.children] == [0, 1]


def test_regular_image_uses_single_child_without_album(tmp_path, monkeypatch):
    source = tmp_path / "photo.jpg"
    source.write_bytes(b"image")
    context = Context(str(uuid4()), source.name)
    monkeypatch.setenv("TASK_WORK_DIR", str(tmp_path / "work"))
    (tmp_path / "work").mkdir()
    monkeypatch.setattr(MODULE, "wait_all_or_cancel", lambda *_args: None)
    result = MODULE.execute(context, Storage(source))

    assert result["archiveExtracted"] is False
    assert context.children[0].parameters["albumFolder"] == ""
    assert context.children[0].parameters["assetMimeType"] == "image/jpeg"
