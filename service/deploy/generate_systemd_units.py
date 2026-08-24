#!/usr/bin/env python3
"""Generate deterministic systemd units for the MyTools service manifest."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any, Sequence


SAFE_NAME = re.compile(r"^[a-z][a-z0-9-]+$")


def load_manifest(path: Path) -> dict[str, Any]:
    """Load the deployment manifest and validate runtime dependencies."""

    manifest = json.loads(path.read_text(encoding="utf-8"))
    root = manifest.get("deploymentRoot")
    if root != "/opt/yuyutian/mytools":
        raise ValueError("deploymentRoot must be /opt/yuyutian/mytools")
    entries = manifest.get("services", []) + manifest.get("statelessServices", [])
    names = {entry.get("name") for entry in entries}
    if len(names) != len(entries) or None in names:
        raise ValueError("service names must be present and unique")
    for entry in entries:
        name = entry["name"]
        runtime = entry.get("runtime")
        if not SAFE_NAME.fullmatch(name):
            raise ValueError(f"unsafe service name: {name}")
        if runtime not in {"java", "python"}:
            raise ValueError(f"unsupported runtime for {name}: {runtime}")
        if runtime == "python" and not SAFE_NAME.fullmatch(entry.get("executable", "")):
            raise ValueError(f"missing Python executable for {name}")
        unknown = set(entry.get("after", [])) - names
        if unknown:
            raise ValueError(f"unknown dependencies for {name}: {sorted(unknown)}")
    return manifest


def service_unit(entry: dict[str, Any], deployment_root: str) -> str:
    """Render a hardened service unit for one manifest entry."""

    name = entry["name"]
    dependencies = entry.get("after", [])
    after = ["network-online.target", "mysql.service", *(f"mytools-{item}.service" for item in dependencies)]
    if entry["runtime"] == "java":
        executable = f"/usr/bin/java -jar {deployment_root}/releases/current/apps/{name}.jar"
    else:
        executable = f"{deployment_root}/releases/current/venv/bin/{entry['executable']}"
    lines = [
        "[Unit]",
        f"Description=MyTools {name}",
        f"After={' '.join(after)}",
        "Wants=network-online.target",
        "",
        "[Service]",
        "Type=simple",
        "User=mytools",
        "Group=mytools",
        f"EnvironmentFile={deployment_root}/config/services.env",
        f"WorkingDirectory={deployment_root}/releases/current",
        f"ExecStart={executable}",
        "Restart=on-failure",
        "RestartSec=5s",
        "TimeoutStopSec=90s",
        "NoNewPrivileges=true",
        "PrivateTmp=true",
        "ProtectSystem=full",
        "ProtectHome=true",
        "StandardOutput=journal",
        "StandardError=journal",
        "",
        "[Install]",
        "WantedBy=mytools-services.target",
        "",
    ]
    return "\n".join(lines)


def target_unit(entries: list[dict[str, Any]]) -> str:
    """Render a target containing only services enabled by default."""

    enabled = [f"mytools-{entry['name']}.service" for entry in entries if entry.get("defaultEnabled", True)]
    return "\n".join(
        (
            "[Unit]",
            "Description=MyTools service architecture",
            f"Wants={' '.join(enabled)}",
            "After=network-online.target mysql.service",
            "",
            "[Install]",
            "WantedBy=multi-user.target",
            "",
        )
    )


def tmpfiles_config(deployment_root: str) -> str:
    """Render persistent directory ownership without deleting existing data."""

    paths = ("config", "releases", "runtime/tasks", "migration", "logs")
    return "\n".join(f"d {deployment_root}/{path} 0750 mytools mytools -" for path in paths) + "\n"


def generate(manifest: dict[str, Any], output: Path) -> list[Path]:
    """Generate units into an empty or previously generated output directory."""

    output.mkdir(parents=True, exist_ok=True)
    entries = manifest["services"] + manifest["statelessServices"]
    generated: list[Path] = []
    for entry in entries:
        path = output / f"mytools-{entry['name']}.service"
        path.write_text(service_unit(entry, manifest["deploymentRoot"]), encoding="utf-8")
        generated.append(path)
    target = output / "mytools-services.target"
    target.write_text(target_unit(entries), encoding="utf-8")
    generated.append(target)
    tmpfiles = output / "mytools.conf"
    tmpfiles.write_text(tmpfiles_config(manifest["deploymentRoot"]), encoding="utf-8")
    generated.append(tmpfiles)
    return generated


def main(argv: Sequence[str] | None = None) -> int:
    """Generate deployment units without installing or enabling them."""

    directory = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=directory / "services.json")
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args(argv)
    try:
        manifest = load_manifest(arguments.manifest)
        generated = generate(manifest, arguments.output)
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2
    print(f"Generated systemd artifacts: {len(generated)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
