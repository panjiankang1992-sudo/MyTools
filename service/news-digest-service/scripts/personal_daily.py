#!/usr/bin/env python3
"""自用日报入口：按日冻结材料、恢复分析、查询回执并显式发送。"""
import argparse
from datetime import datetime
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import uuid
from zoneinfo import ZoneInfo
from personal_report import CATEGORIES, digest, generate_report, write_json


def load_config(path):
    """个人配置不包含 SMTP 密码，收件人与运行目录必须明确提供。"""
    config = json.loads(path.read_text())
    if type(config.get("enabled")) is not bool or not re.fullmatch(r"[0-9]{1,20}", str(config.get("ownerId", ""))) \
            or not Path(config.get("stateDirectory", "")).is_absolute() \
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", config.get("nodeId", "")) \
            or config.get("addressFamily") not in ("IPV4", "IPV6"):
        raise ValueError("DAILY_CONFIGURATION_INVALID")
    recipients = config.get("recipients", [])
    mail_enabled = config.get("mailEnabled", True)
    if type(mail_enabled) is not bool or not isinstance(recipients, list) or (mail_enabled and not recipients) or len(set(recipients)) != len(recipients) \
            or any(not isinstance(value, str) or not re.fullmatch(r"[^\s<>@,;]+@[^\s<>@,;]+\.[^\s<>@,;]+", value) for value in recipients):
        raise ValueError("DAILY_RECIPIENTS_INVALID")
    timeout = config.get("analysisTimeoutSeconds", 300)
    if type(timeout) is not int or not 30 <= timeout <= 900:
        raise ValueError("DAILY_CONFIGURATION_INVALID")
    ZoneInfo(config["zone"])
    config.setdefault("recipients", [])
    return config


def publish_report(config, runtime, directory):
    """公开发布独立失败，使用正文环境的图像依赖，不触发邮件。"""
    if not config.get("rssConfig"):
        return {"status": "disabled"}
    try:
        command = [os.environ.get("NEWS_CONTENT_PYTHON", sys.executable),
                   str(runtime / "scripts" / "publish_rss.py"), "--config", config["rssConfig"],
                   "--report", str(directory / "report" / "daily-report.json"),
                   "--region-input", str(directory / "input" / "report-input.json"),
                   "--details-directory", str(directory / "rss-articles"), "--dsh", config.get("dsh", "dsh")]
        result = subprocess.run(command, capture_output=True, text=True, timeout=960, check=False)
        state = json.loads(result.stdout)
        if result.returncode or not isinstance(state, dict):
            state = {"status": "failed", "code": "RSS_PUBLISH_FAILED"}
    except (OSError, ValueError, subprocess.SubprocessError):
        state = {"status": "failed", "code": "RSS_PUBLISH_FAILED"}
    write_json(directory / "rss-summary.json", state)
    return state


def frozen_config(config):
    """发布开关独立于已有材料和邮件身份，添加 RSS 不会重置邮件回执。"""
    return {key: value for key, value in config.items() if key not in ("enabled", "rssConfig")}


def current_directory(config):
    """同一所有者同一天固定使用一个目录，重复触发不会生成另一份投递任务。"""
    day = datetime.now(ZoneInfo(config["zone"])).date().isoformat()
    return Path(config["stateDirectory"]) / str(config["ownerId"]) / day


def observe_region(runtime, config):
    """复用已发布地区探测代码，在本次执行主机定位，原始 IP 和临时 HMAC 密钥不落盘。"""
    spec = importlib.util.spec_from_file_location("personal_region_probe", runtime / "region_probe.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.observe(config["nodeId"], str(uuid.uuid4()),
                          {"provider": "IPWHOIS", "family": config["addressFamily"], "proxy": "",
                           "fingerprint_key": secrets.token_hex(32)})


def collect(runtime, config, directory, weekly_cutoff=None, weekly_categories=None):
    """先完成临时采集再发布材料目录；不依赖新的生产数据库或 Maven。"""
    with tempfile.TemporaryDirectory(prefix=".collect-", dir=directory) as temporary:
        staging = Path(temporary)
        region = staging / "region.json"
        try:
            write_json(region, observe_region(runtime, config))
        except (OSError, ValueError, RuntimeError):
            # 手动七天报告允许仅本地分类降级，普通日报保留原有失败行为。
            if weekly_cutoff is None:
                raise
            region = None
        command = [config.get("java", "java"), "-Dnews.illustrated=true",
                   "-Dnews.contentExtractor=" + str(runtime / "scripts" / "extract_article.py"),
                   "-Dnews.contentPython=" + os.environ.get("NEWS_CONTENT_PYTHON", sys.executable),
                   "-Dloader.main=com.yuyutian.mytools.news.PersonalCollectorLauncher",
                   "-cp", str(runtime / "news-digest-service.jar"),
                   "org.springframework.boot.loader.launch.PropertiesLauncher",
                   str(runtime / "sources.json"), str(staging / "input")]
        if region is not None:
            command.append(str(region))
        if weekly_cutoff is not None:
            command.insert(1, "-Dnews.weeklyCutoff=" + weekly_cutoff)
        if weekly_categories is not None:
            if weekly_cutoff is None or not weekly_categories or not set(weekly_categories).issubset(CATEGORIES):
                raise ValueError('WEEKLY_CATEGORIES_INVALID')
            command.insert(1, '-Dnews.weeklyCategories=' + ','.join(sorted(weekly_categories)))
        with (directory / "collection.log").open("a") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=2400, check=False)
        if result.returncode:
            raise ValueError("DAILY_COLLECTION_FAILED")
        if region is not None:
            (staging / "input" / "region-observation.json").write_bytes(region.read_bytes())
        (staging / "input").rename(directory / "input")


def mail_action(runtime, job, action):
    """仅邮件子进程读取已有服务配置；查询状态不发信，已接受或未知任务遵循原回执。"""
    command = [sys.executable, str(runtime / "scripts" / "send_personal_mail.py"), str(job), action]
    if os.geteuid() != 0:
        command = ["sudo", "-n", *command]
    result = subprocess.run(command, capture_output=True, text=True, timeout=600, check=False)
    records = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    if result.returncode:
        # 发送中断后不自动重试，后续运行只能依据持久回执恢复。
        raise ValueError("DAILY_MAIL_ACTION_FAILED")
    return records[-1] if records else {"status": "unknown"}


def submit_category(runtime, job):
    """先查询回执，再提交未接受的邮件；状态不明确时保持原任务并继续其他分类。"""
    identity = json.loads(job.read_text())["job_id"]
    try:
        current = mail_action(runtime, job, "--status")
    except (ValueError, subprocess.SubprocessError, OSError):
        return {"job_id": identity, "status": "unknown", "statusUnavailable": True}
    # 已接受任务和可能已投递的任务均不能再次提交。
    if current.get("status") not in ("prepared", "failed"):
        return current
    try:
        mail_action(runtime, job, "--send")
    except (ValueError, subprocess.SubprocessError, OSError):
        # 发送异常后以持久回执为准，不能把异常一律当成未发送。
        pass
    try:
        return mail_action(runtime, job, "--status")
    except (ValueError, subprocess.SubprocessError, OSError):
        return {"job_id": identity, "status": "unknown", "statusUnavailable": True}


def delivery_retryable(receipt):
    """只自动恢复未提交任务或明确的临时失败，不重发接受、永久拒绝或未知任务。"""
    if receipt is None or receipt.get("status") == "prepared":
        return True
    code = receipt.get("smtp_code")
    return receipt.get("status") == "failed" and (code is None or isinstance(code, int) and 400 <= code < 500)


def run(config_path, runtime, send=False, publish=False):
    """执行一次日任务；已完成阶段可恢复，启停配置在每封邮件发送前再次检查。"""
    config = load_config(config_path)
    if (send or publish) and not config["enabled"]:
        raise ValueError("DAILY_SENDING_DISABLED")
    directory = current_directory(config)
    directory.mkdir(parents=True, mode=0o700, exist_ok=True)
    with (directory / ".daily.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        identity = frozen_config(config)
        identity.update(sourcesSha256=digest((runtime / "sources.json").read_bytes()),
                        collectorSha256=digest((runtime / "news-digest-service.jar").read_bytes()))
        manifest = directory / "daily.json"
        if manifest.exists() and json.loads(manifest.read_text()) != identity:
            raise ValueError("DAILY_CONFIGURATION_CHANGED")
        if not manifest.exists():
            write_json(manifest, identity)
        if not (directory / "input").exists():
            collect(runtime, config, directory)
        source = directory / "input" / "report-input.json"
        material = json.loads(source.read_text())
        if not any(section["items"] for section in material["sections"]):
            raise ValueError("DAILY_NO_NEWS")
        receipts = {}
        # 第一轮先投递已通过校验的分类，第二轮只恢复缺少的分析或临时发送失败。
        for attempt in range(1, 3):
            summary = generate_report(source, directory / "report", config["recipients"] if config.get("mailEnabled", True) else [],
                                      config.get("dsh", "dsh"), config.get("analysisTimeoutSeconds", 300),
                                      resume=True, allow_partial=True)
            failures = summary.get("failedSections", [])
            rss = {"status": "pending" if publish else "not_requested"}
            if not send or not config.get("mailEnabled", True):
                rss = publish_report(config, runtime, directory) if publish else rss
                if publish and attempt < 2 and any(f.get("retryable", False) for f in failures):
                    continue
                return {"status": "prepared_partial" if failures else "prepared", "directory": str(directory),
                        "categories": len(summary["sections"]), "failedSections": failures, "rss": rss}
            state = {"attempt": attempt, "receipts": list(receipts.values()), "analysisFailures": failures,
                     "complete": False, "mailboxDeliveryConfirmed": False, "rss": rss}
            write_json(directory / "delivery-summary.json", state)
            for section in summary["sections"]:
                current = load_config(config_path)
                if not current["enabled"]:
                    raise ValueError("DAILY_SENDING_DISABLED")
                if frozen_config(current) != frozen_config(config):
                    raise ValueError("DAILY_CONFIGURATION_CHANGED")
                job = Path(section["job"])
                if attempt == 2 and not delivery_retryable(receipts.get(str(job))):
                    continue
                receipt = submit_category(runtime, job)
                receipts[str(job)] = dict(receipt, category=section.get("category", job.parent.name.upper()))
                state["receipts"] = list(receipts.values())
                write_json(directory / "delivery-summary.json", state)
            # 已准备好的邮件先投递，RSS 补充详情的模型耗时不延迟这些邮件。
            state["rss"] = publish_report(config, runtime, directory) if publish else rss
            complete = not failures and all(record.get("status") == "accepted" for record in receipts.values())
            state["complete"] = complete
            write_json(directory / "delivery-summary.json", state)
            if complete:
                return {"status": "submitted", **state}
            retryable = any(failure.get("retryable", False) for failure in failures) or any(delivery_retryable(record) for record in receipts.values())
            if attempt == 2 or not retryable:
                raise ValueError("DAILY_INCOMPLETE")
            print(json.dumps({"stage": "daily_recovery", "nextAttempt": attempt + 1,
                              "analysisFailures": failures, "accepted": sum(r.get("status") == "accepted" for r in receipts.values())}), flush=True)


def status(config_path, runtime):
    """读取当前日进度与原始发送回执，不采集、不分析、不发送。"""
    config = load_config(config_path); directory = current_directory(config)
    report = {"enabled": config["enabled"], "directory": str(directory), "collected": (directory / "input").exists(), "receipts": []}
    for job in sorted((directory / "report").glob("*/job.json")):
        report["receipts"].append(mail_action(runtime, job, "--status"))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("action", choices=("run", "status", "disable", "publish-only"))
    parser.add_argument("--send", action="store_true")
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--date")
    args = parser.parse_args()
    runtime = Path(__file__).resolve().parent.parent
    if args.action == "disable":
        config = load_config(args.config); config["enabled"] = False; write_json(args.config, config)
        result = {"enabled": False}
    elif args.action == "status":
        result = status(args.config, runtime)
    elif args.action == "publish-only":
        config = load_config(args.config)
        if not config["enabled"]:
            raise ValueError("DAILY_SENDING_DISABLED")
        directory = current_directory(config)
        if args.date:
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", args.date):
                raise ValueError("DAILY_DATE_INVALID")
            datetime.fromisoformat(args.date)
            directory = directory.parent / args.date
        with (directory / ".daily.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = publish_report(config, runtime, directory)
    else:
        result = run(args.config, runtime, args.send, args.publish)
    print(json.dumps(result), flush=True)
    if result.get("status") == "failed" or result.get("rss", {}).get("status") == "failed":
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"status": "failed", "code": str(error) if isinstance(error, ValueError) and str(error).isupper() else type(error).__name__}), file=sys.stderr)
        sys.exit(1)
