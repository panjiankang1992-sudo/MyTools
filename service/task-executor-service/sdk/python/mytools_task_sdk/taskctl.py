"""任务脚本命令行客户端。"""

from __future__ import annotations

import argparse
import json

from .context import TaskContext


def main() -> None:
    """执行 taskctl 命令。"""
    parser = argparse.ArgumentParser(prog="taskctl")
    subparsers = parser.add_subparsers(dest="command", required=True)
    status = subparsers.add_parser("status")
    status.add_argument("--id", required=True)
    cancel = subparsers.add_parser("cancel")
    cancel.add_argument("--id", required=True)
    create = subparsers.add_parser("create-child")
    create.add_argument("--task", required=True)
    create.add_argument("--params", required=True)
    create.add_argument("--idempotency-key", required=True)
    args = parser.parse_args()
    context = TaskContext.load()
    if args.command == "status":
        result = context.get_task(args.id)
    elif args.command == "cancel":
        result = context.cancel_child(args.id)
    else:
        with open(args.params, encoding="utf-8") as stream:
            parameters = json.load(stream)
        result = context.create_child(args.task, parameters, args.idempotency_key)
    print(json.dumps(result.__dict__, ensure_ascii=False))


if __name__ == "__main__":
    main()
