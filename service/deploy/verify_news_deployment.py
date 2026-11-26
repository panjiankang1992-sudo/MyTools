#!/usr/bin/env python3
"""只读核对新闻配置、完整发布及指定执行器运行依赖，不进行采集或发送。"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import zipfile

# 从不可变发布目录直接执行时，不生成会破坏文件清单的本地模块缓存。
sys.dont_write_bytecode = True

from news_deployment_config import (NewsConfigurationError, read_private, validate_configuration)
from install_release import validate_release, validate_relative_path

PACKAGES = {
    ("news_generate_edition", "1.0.0"), ("news_resolve_region", "1.2.0"),
    ("message_send_channel_message", "1.0.0"),
}
JAR_MEMBERS = {
    "news-digest-service": (
        "db/migration/V7__add_content_retention.sql",
        "db/migration/V8__add_collection_capacity.sql",
        "db/migration/V9__reserve_news_control_capacity.sql",
        "db/migration/V10__reserve_registry_capacity.sql",
        "com/yuyutian/mytools/news/mapper/RegistryCapacityRepository.class",
        "com/yuyutian/mytools/news/mapper/ControlCapacityRepository.class",
        "com/yuyutian/mytools/news/controller/NewsEvidenceController.class",
        "com/yuyutian/mytools/news/service/NewsDispatchService.class",
    ),
    "messaging-service": (
        "db/migration/V18__create_verified_outbound.sql", "db/migration/V19__create_outbound_channel_budget.sql",
        "db/migration/V20__add_outbound_retention.sql",
        "db/migration/V21__reserve_outbound_capacity.sql",
        "db/migration/V22__add_outbound_content_deadline.sql",
        "com/yuyutian/mytools/messaging/config/OutboundDeadlineTimer.class",
        "com/yuyutian/mytools/messaging/repository/OutboundCapacityRepository.class",
        "com/yuyutian/mytools/messaging/controller/OutboundRetentionController.class",
    ),
    "mytools-gateway": (
        "com/yuyutian/mytools/gateway/config/NewsGatewayConfiguration.class",
        "com/yuyutian/mytools/gateway/controller/NewsGatewayController.class",
    ),
    "task-executor-service": ("com/yuyutian/mytools/task/executor/runtime/ScriptReleaseVerifier.class",),
    "task-scheduler-service": (
        "db/migration/V173__seed_news_edition_task.sql", "db/migration/V174__seed_news_region_task.sql",
        "db/migration/V177__upgrade_news_region_provider.sql",
        "db/migration/V176__seed_verified_outbound_task.sql",
        "com/yuyutian/mytools/task/scheduler/controller/NewsExecutionAuthorizationController.class",
    ),
}
MIGRATION_RUNTIME_SERVICES = {"news-digest-service", "messaging-service", "task-scheduler-service"}
MIGRATION_RUNTIME_LIBRARIES = {"spring-boot": "3.5.16", "spring-boot-autoconfigure": "3.5.16",
                               "flyway-core": "11.20.3", "flyway-mysql": "11.20.3"}


def digest(path: Path) -> str:
    """流式计算文件身份，不读取或打印任何运行凭据。"""
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inspect_packages(directory: Path) -> int:
    """验证索引聚合摘要、每个版本文件摘要与必需的不可变包身份。"""
    try:
        index = json.loads((directory / "package-index.json").read_bytes())
        packages = index["packages"]
        if not isinstance(packages, list) or len(packages) != index["packageCount"]:
            raise ValueError()
        encoded = json.dumps(packages, sort_keys=True, separators=(",", ":")).encode()
        if hashlib.sha256(encoded).hexdigest() != index["contentSha256"]:
            raise ValueError()
        identities = set()
        indexed_files = {"package-index.json"}
        for package in packages:
            identity = (package["name"], package["version"])
            if identity in identities or any(not isinstance(value, str) or "/" in value or "\\" in value or value in {"", ".", ".."} for value in identity):
                raise ValueError()
            identities.add(identity)
            root = directory.joinpath(*identity)
            paths = set()
            for item in package["files"]:
                value = item["path"]
                relative = PurePosixPath(value)
                if not value or "\\" in value or relative.is_absolute() or value != relative.as_posix() or ".." in relative.parts or value in paths:
                    raise ValueError()
                paths.add(value)
                indexed_files.add("/".join((*identity, value)))
                file = root.joinpath(*relative.parts)
                if not file.is_file() or file.is_symlink() or file.stat().st_size != item["sizeBytes"] or digest(file) != item["sha256"]:
                    raise ValueError()
            if package["entrypoint"] not in paths:
                raise ValueError()
        actual_files = {path.relative_to(directory).as_posix() for path in directory.rglob("*") if path.is_file()}
        if actual_files != indexed_files or any(path.is_symlink() for path in directory.rglob("*")):
            raise ValueError()
        if not PACKAGES.issubset(identities):
            raise ValueError()
        requirements = directory / "news_resolve_region" / "1.2.0" / "requirements.txt"
        if requirements.read_text().strip() != "maxminddb==3.2.0":
            raise ValueError()
        return len(packages)
    except (OSError, TypeError, KeyError, ValueError) as error:
        raise NewsConfigurationError("NEWS_RELEASE_PACKAGE_INVALID") from error


def inspect_installed_payload(directory: Path) -> dict:
    """安装后核对原始发布载荷，venv 等安装产物另由运行依赖检查覆盖。"""
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError()
    manifest = json.loads((directory / "release-manifest.json").read_bytes())
    declared = set()
    root = directory.resolve(strict=True)
    for item in manifest["files"]:
        relative = validate_relative_path(item["path"])
        if relative in declared or relative != PurePosixPath(relative).as_posix():
            raise ValueError()
        declared.add(relative)
        file = root / relative
        if file.resolve() != file or not file.is_file() or file.stat().st_size != item["size"] or digest(file) != item["sha256"]:
            raise ValueError()
    # JAR 与任务包不能混入未发布文件；安装器产生的 venv/build 文件不冒充原始清单成员。
    for name in ("apps", "task-packages"):
        actual = {path.relative_to(root).as_posix() for path in (root / name).rglob("*") if path.is_file()}
        expected = {path for path in declared if path.startswith(name + "/")}
        if actual != expected or any(path.is_symlink() for path in (root / name).rglob("*")):
            raise ValueError()
    return manifest


def inspect_release(directory: Path, *, installed: bool = False) -> dict:
    """完整文件清单与静态能力检查不替代测试、数据库迁移或现场验收。"""
    try:
        manifest = inspect_installed_payload(directory) if installed else validate_release(directory)
        services = json.loads((directory / "services.json").read_bytes())
        entries = services["services"] + services["statelessServices"]
        names = {entry["name"] for entry in entries}
        if not set(JAR_MEMBERS).issubset(names):
            raise ValueError()
        news = next(entry for entry in entries if entry["name"] == "news-digest-service")
        if news.get("port") != 23350 or news.get("schema") != "mytools_news" or news.get("defaultEnabled") is not False:
            raise ValueError()
        for service, members in JAR_MEMBERS.items():
            with zipfile.ZipFile(directory / "apps" / f"{service}.jar") as archive:
                present = set(archive.namelist())
                if any("BOOT-INF/classes/" + member not in present for member in members):
                    raise ValueError()
                if service in MIGRATION_RUNTIME_SERVICES:
                    # 旧 JAR 也可能带齐业务类；必须核对实际打包依赖，避免只改 POM 却发布旧引擎。
                    for library, version in MIGRATION_RUNTIME_LIBRARIES.items():
                        pattern = re.compile(r"BOOT-INF/lib/" + re.escape(library) + r"-[0-9].*\.jar")
                        matching = {entry for entry in present if pattern.fullmatch(entry)}
                        if matching != {f"BOOT-INF/lib/{library}-{version}.jar"}:
                            raise NewsConfigurationError("NEWS_RELEASE_MIGRATION_RUNTIME_INVALID")
                        member = next(iter(matching))
                        if archive.getinfo(member).file_size > 20 * 1024 * 1024:
                            raise NewsConfigurationError("NEWS_RELEASE_MIGRATION_RUNTIME_INVALID")
                        with zipfile.ZipFile(io.BytesIO(archive.read(member))) as dependency:
                            metadata = (f"META-INF/maven/org.flywaydb/{library}/pom.properties"
                                        if library.startswith("flyway-") else "META-INF/MANIFEST.MF")
                            if dependency.getinfo(metadata).file_size > 16384:
                                raise NewsConfigurationError("NEWS_RELEASE_MIGRATION_RUNTIME_INVALID")
                            content = dependency.read(metadata).decode("utf-8")
                            separator = "=" if library.startswith("flyway-") else ":"
                            key = "version" if library.startswith("flyway-") else "Implementation-Version"
                            versions = [line.split(separator, 1)[1].strip() for line in content.splitlines()
                                        if separator in line and line.split(separator, 1)[0].strip() == key]
                            if versions != [version]:
                                raise NewsConfigurationError("NEWS_RELEASE_MIGRATION_RUNTIME_INVALID")
        packages = inspect_packages(directory / "task-packages")
        return {"releaseFilesValid": True, "inventoryMode": "installed_payload" if installed else "exact_source", "fileCount": len(manifest["files"]), "packageCount": packages,
                "requiredJarCount": len(JAR_MEMBERS), "pinnedMigrationRuntimeVerified": True,
                "runtimeAcceptance": False}
    except NewsConfigurationError:
        raise
    except (OSError, TypeError, KeyError, ValueError, StopIteration, zipfile.BadZipFile) as error:
        raise NewsConfigurationError("NEWS_RELEASE_INVALID") from error


def inspect_runtime(values: dict[str, str], directory: Path) -> dict:
    """执行器身份只读检查本地路径和 Python，不携带环境凭据启动子进程。"""
    if os.geteuid() == 0:
        raise NewsConfigurationError("NEWS_RUNTIME_NON_ROOT_REQUIRED")
    try:
        release = directory.resolve(strict=True)
        scripts = Path(values.get("TASK_EXECUTOR_SCRIPT_ROOT", "")).resolve(strict=True)
        python = Path(values.get("TASK_EXECUTOR_PYTHON_EXECUTABLE", ""))
        work = Path(values.get("TASK_EXECUTOR_WORK_ROOT", ""))
        # 子进程必须使用选定版本的 venv 解释器，不能把任意环境值当作可执行命令。
        if scripts != release / "task-packages" or python.absolute().parent.resolve(strict=True) != release / "venv" / "bin" \
                or python.name not in {"python", "python3", "python3.12"} or not python.is_file() or not os.access(python, os.X_OK):
            raise ValueError()
        if not work.is_dir() or not os.access(work, os.R_OK | os.W_OK | os.X_OK):
            raise ValueError()
        if values.get("NEWS_REGION_PROVIDER", "MAXMIND_MMDB") == "MAXMIND_MMDB":
            database = Path(values.get("NEWS_REGION_MMDB_PATH", ""))
            if not database.is_file() or not os.access(database, os.R_OK):
                raise ValueError()
        code = "import sys,importlib.metadata; sys.exit(0 if sys.version_info[:2]>=(3,12) and importlib.metadata.version('maxminddb')=='3.2.0' else 2)"
        result = subprocess.run([str(python), "-I", "-c", code], env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"},
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=10, check=False)
        if result.returncode != 0:
            raise ValueError()
        return {"runtimeDependenciesValid": True, "networkProbed": False, "liveAcceptance": False}
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise NewsConfigurationError("NEWS_RUNTIME_DEPENDENCY_INVALID") from error


def main(argv=None) -> int:
    """文件配置默认只读，实际依赖检查需要显式选择并使用执行器身份。"""
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--env-file", type=Path)
    source.add_argument("--from-environment", action="store_true")
    parser.add_argument("--stage", choices=("staged", "shadow", "delivery"), default="staged")
    parser.add_argument("--release", type=Path)
    parser.add_argument("--runtime", action="store_true")
    args = parser.parse_args(argv)
    try:
        values = dict(os.environ) if args.from_environment else read_private(args.env_file or Path("/opt/yuyutian/mytools/config/services.env")).values
        result = validate_configuration(values, args.stage)
        result.update({"releaseChecked": args.release is not None, "runtimeChecked": args.runtime})
        if args.release is not None:
            result["release"] = inspect_release(args.release, installed=args.runtime)
        if args.runtime:
            if args.release is None or args.stage == "staged":
                raise NewsConfigurationError("NEWS_RUNTIME_RELEASE_AND_ACTIVE_STAGE_REQUIRED")
            result["runtime"] = inspect_runtime(values, args.release)
        print(json.dumps(result, sort_keys=True))
        return 0
    except NewsConfigurationError as error:
        print(json.dumps({"configurationValid": False, **error.public()}, sort_keys=True))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
