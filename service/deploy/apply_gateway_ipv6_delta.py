"""仅替换本次经过验证的 IPv6 协商类，并逐项校验其余工件。"""

from __future__ import annotations

import copy
import hashlib
from pathlib import Path
import zipfile


CLASS_ROOT = "BOOT-INF/classes/com/yuyutian/mytools/gateway/"
ALLOWED_ENTRIES = frozenset(CLASS_ROOT + name for name in (
    "model/ConnectivityModels$Bootstrap.class",
    "service/ConnectivityService.class",
    "service/ConnectivityService$Probe.class",
))


def patch(base: Path, delta: Path, output: Path, expected_sha256: str) -> dict[str, object]:
    """基于当前运行包生成新包，不覆盖其他会话修改过的类。"""
    if hashlib.sha256(delta.read_bytes()).hexdigest() != expected_sha256:
        raise ValueError("IPv6 delta checksum mismatch")
    if base.resolve() == output.resolve():
        raise ValueError("Output must not replace the running artifact")
    with zipfile.ZipFile(base) as original, zipfile.ZipFile(delta) as updates:
        if set(updates.namelist()) != ALLOWED_ENTRIES or len(updates.namelist()) != len(ALLOWED_ENTRIES):
            raise ValueError("Unexpected IPv6 delta entries")
        if len(original.namelist()) != len(set(original.namelist())) or not ALLOWED_ENTRIES.issubset(original.namelist()):
            raise ValueError("Invalid base artifact")
        with zipfile.ZipFile(output, "x") as changed:
            changed.comment = original.comment
            for item in original.infolist():
                changed.writestr(copy.copy(item), updates.read(item.filename)
                                 if item.filename in ALLOWED_ENTRIES else original.read(item.filename))
        with zipfile.ZipFile(output) as changed:
            if changed.namelist() != original.namelist():
                raise ValueError("Artifact member order changed")
            for name in original.namelist():
                expected = updates.read(name) if name in ALLOWED_ENTRIES else original.read(name)
                if changed.read(name) != expected:
                    raise ValueError("Artifact content verification failed")
    return {"baseSha256": hashlib.sha256(base.read_bytes()).hexdigest(),
            "deltaSha256": expected_sha256, "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
            "replacedEntries": sorted(ALLOWED_ENTRIES)}
