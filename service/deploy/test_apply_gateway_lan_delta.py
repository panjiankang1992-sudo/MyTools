"""Verify a LAN patch cannot mix unrelated dirty Gateway classes into a release."""

from __future__ import annotations

import hashlib
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


SPEC = importlib.util.spec_from_file_location(
    "apply_gateway_lan_delta", Path(__file__).with_name("apply_gateway_lan_delta.py"))
assert SPEC is not None and SPEC.loader is not None
patcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(patcher)


class ApplyGatewayLanDeltaTest(unittest.TestCase):
    """Cover exact allowlists, nested-library compatibility and immutable outputs."""

    def fixture(self, directory: str) -> tuple[Path, Path, str]:
        """Create an original archive and exactly the allowed class replacements."""
        original = Path(directory) / "original.jar"
        delta = Path(directory) / "delta.zip"
        with zipfile.ZipFile(original, "w") as archive:
            for name in sorted(patcher.ALLOWED_ENTRIES):
                archive.writestr(name, b"old-class", compress_type=zipfile.ZIP_DEFLATED)
            archive.writestr("BOOT-INF/lib/dependency.jar", b"nested-library", compress_type=zipfile.ZIP_STORED)
            archive.writestr("BOOT-INF/classes/application.yml", b"original-config")
        with zipfile.ZipFile(delta, "w") as archive:
            for name in sorted(patcher.ALLOWED_ENTRIES):
                archive.writestr(name, b"new-class")
        return original, delta, hashlib.sha256(delta.read_bytes()).hexdigest()

    def test_preserves_unrelated_bytes_and_nested_library_storage(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            original, delta, checksum = self.fixture(directory)
            output = Path(directory) / "patched.jar"
            receipt = patcher.patch(original, delta, output, checksum)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(b"nested-library", archive.read("BOOT-INF/lib/dependency.jar"))
                self.assertEqual(zipfile.ZIP_STORED, archive.getinfo("BOOT-INF/lib/dependency.jar").compress_type)
                self.assertEqual(b"original-config", archive.read("BOOT-INF/classes/application.yml"))
                for name in patcher.ALLOWED_ENTRIES:
                    self.assertEqual(b"new-class", archive.read(name))
            with zipfile.ZipFile(original) as archive:
                self.assertEqual(b"old-class", archive.read(sorted(patcher.ALLOWED_ENTRIES)[0]))
            self.assertEqual(2, receipt["unrelatedEntriesVerified"])

    def test_rejects_unreviewed_delta_checksum_before_writing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            original, delta, _ = self.fixture(directory)
            output = Path(directory) / "patched.jar"
            with self.assertRaises(ValueError):
                patcher.patch(original, delta, output, "0" * 64)
            self.assertFalse(output.exists())

    def test_variable_length_updates_preserve_offsets_with_interleaved_mixed_entries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory) / "original.jar"
            delta = Path(directory) / "delta.zip"
            output = Path(directory) / "patched.jar"
            expected: dict[str, bytes] = {}
            compression: dict[str, int] = {}
            names = sorted(patcher.ALLOWED_ENTRIES)
            with zipfile.ZipFile(original, "w") as archive:
                for index, name in enumerate(names):
                    resource = "BOOT-INF/lib/preserved-" + str(index) + ".jar"
                    content = (bytes(range(256)) * (index + 1)) + b"resource"
                    method = zipfile.ZIP_STORED if index % 2 == 0 else zipfile.ZIP_DEFLATED
                    archive.writestr(resource, content, compress_type=method)
                    archive.writestr(name, b"old" * (index + 1), compress_type=method)
                    expected[resource] = content
                    compression[resource] = method
                    compression[name] = method
                archive.writestr("last-resource", b"unchanged-tail", compress_type=zipfile.ZIP_DEFLATED)
                expected["last-resource"] = b"unchanged-tail"
                compression["last-resource"] = zipfile.ZIP_DEFLATED
            with zipfile.ZipFile(delta, "w") as archive:
                for index, name in enumerate(names):
                    # 新旧长度和压缩结果都不同，强制移动其后成员的文件头偏移。
                    content = bytes(range(256)) * (index + 8)
                    archive.writestr(name, content, compress_type=zipfile.ZIP_DEFLATED)
                    expected[name] = content
            checksum = hashlib.sha256(delta.read_bytes()).hexdigest()

            receipt = patcher.patch(original, delta, output, checksum)

            with zipfile.ZipFile(output) as archive, zipfile.ZipFile(original) as source:
                self.assertEqual(source.namelist(), archive.namelist())
                for name, content in expected.items():
                    self.assertEqual(content, archive.read(name))
                    self.assertEqual(compression[name], archive.getinfo(name).compress_type)
                    if name in patcher.ALLOWED_ENTRIES:
                        self.assertTrue(source.read(name).startswith(b"old"))
                    else:
                        self.assertEqual(content, source.read(name))
            self.assertEqual(7, receipt["unrelatedEntriesVerified"])

    def test_rejects_extra_unrelated_patch_member(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            original, delta, _ = self.fixture(directory)
            with zipfile.ZipFile(delta, "a") as archive:
                archive.writestr("BOOT-INF/classes/application.yml", b"dirty-config")
            output = Path(directory) / "patched.jar"
            with self.assertRaises(ValueError):
                patcher.patch(original, delta, output, hashlib.sha256(delta.read_bytes()).hexdigest())
            self.assertFalse(output.exists())

    def test_refuses_overwriting_original_or_existing_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            original, delta, checksum = self.fixture(directory)
            with self.assertRaises(ValueError):
                patcher.patch(original, delta, original, checksum)
            output = Path(directory) / "existing.jar"
            output.write_bytes(b"existing")
            with self.assertRaises(FileExistsError):
                patcher.patch(original, delta, output, checksum)
            self.assertEqual(b"existing", output.read_bytes())


if __name__ == "__main__":
    unittest.main()
