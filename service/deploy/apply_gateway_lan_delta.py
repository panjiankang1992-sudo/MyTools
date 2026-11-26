"""Create a new Gateway JAR with exactly the reviewed LAN class delta."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import zipfile
from pathlib import Path


CLASS_ROOT = "BOOT-INF/classes/com/yuyutian/mytools/gateway/"
ALLOWED_ENTRIES = frozenset(CLASS_ROOT + name for name in (
    "model/ConnectivityModels$Bootstrap.class",
    "service/ConnectivityService.class",
    "service/ConnectivityService$Probe.class",
    "controller/DshWebController.class",
    "service/DshWebSessionService.class",
    "service/DshWebSessionService$Grant.class",
))


def patch(base_jar: Path, delta: Path, output: Path, expected_delta_sha256: str) -> dict[str, object]:
    """Validate the reviewed delta and preserve every unrelated JAR entry byte-for-byte."""
    if hashlib.sha256(delta.read_bytes()).hexdigest() != expected_delta_sha256:
        raise ValueError("Gateway LAN delta checksum mismatch")
    if base_jar.resolve() == output.resolve():
        raise ValueError("Output must be a new JAR path")
    with zipfile.ZipFile(base_jar) as original, zipfile.ZipFile(delta) as updates:
        names = original.namelist()
        if len(names) != len(set(names)) or len(updates.namelist()) != len(ALLOWED_ENTRIES):
            raise ValueError("Duplicate or unexpected archive entries")
        if set(updates.namelist()) != ALLOWED_ENTRIES or not ALLOWED_ENTRIES.issubset(names):
            raise ValueError("Gateway LAN delta must replace exactly the reviewed existing classes")
        # 按原 ZipInfo 写入，保持 Spring Boot 嵌套依赖的存储压缩方式。
        with zipfile.ZipFile(output, "x") as changed:
            changed.comment = original.comment
            for entry in original.infolist():
                changed.writestr(copy.copy(entry), updates.read(entry.filename)
                                 if entry.filename in ALLOWED_ENTRIES else original.read(entry.filename))
        with zipfile.ZipFile(output) as changed:
            if changed.namelist() != names:
                raise ValueError("Patched Gateway JAR member list changed")
            for name in names:
                expected = updates.read(name) if name in ALLOWED_ENTRIES else original.read(name)
                if changed.read(name) != expected:
                    raise ValueError("Patched Gateway JAR verification failed")
    return {
        "baseSha256": hashlib.sha256(base_jar.read_bytes()).hexdigest(),
        "deltaSha256": expected_delta_sha256,
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "replacedEntries": sorted(ALLOWED_ENTRIES),
        "unrelatedEntriesVerified": len(names) - len(ALLOWED_ENTRIES),
    }


def main() -> None:
    """Parse immutable input/output paths and emit a non-sensitive checksum receipt."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-jar", type=Path, required=True)
    parser.add_argument("--delta", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-delta-sha256", required=True)
    arguments = parser.parse_args()
    try:
        report = patch(arguments.base_jar, arguments.delta, arguments.output,
                       arguments.expected_delta_sha256)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print(json.dumps(report))


if __name__ == "__main__":
    main()
