"""任务脚本命令行客户端。"""

from __future__ import annotations

import argparse
import json

from .context import TaskContext
from .errors import PERMANENT_CATEGORIES, RETRYABLE_CATEGORIES, write_task_error


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
    checkpoint = subparsers.add_parser("checkpoint")
    checkpoint_commands = checkpoint.add_subparsers(dest="checkpoint_command", required=True)
    checkpoint_get = checkpoint_commands.add_parser("get")
    checkpoint_get.add_argument("--key", required=True)
    checkpoint_put = checkpoint_commands.add_parser("put")
    checkpoint_put.add_argument("--key", required=True)
    checkpoint_put.add_argument("--value", required=True)
    checkpoint_put.add_argument("--expected-version", required=True, type=int)
    checkpoint_put.add_argument("--request-id")
    checkpoint_commands.add_parser("list")
    failure = subparsers.add_parser("fail")
    failure.add_argument("--code", required=True)
    failure.add_argument("--category", required=True,
                         choices=sorted(RETRYABLE_CATEGORIES | PERMANENT_CATEGORIES))
    failure.add_argument("--message")
    args = parser.parse_args()
    if args.command == "fail":
        write_task_error(args.code, args.category, args.message)
        raise SystemExit(1)
    context = TaskContext.load()
    if args.command == "status":
        result = context.get_task(args.id)
    elif args.command == "cancel":
        result = context.cancel_child(args.id)
    elif args.command == "create-child":
        with open(args.params, encoding="utf-8") as stream:
            parameters = json.load(stream)
        result = context.create_child(args.task, parameters, args.idempotency_key)
    elif args.checkpoint_command == "get":
        result = context.get_checkpoint(args.key)
    elif args.checkpoint_command == "put":
        with open(args.value, encoding="utf-8") as stream:
            value = json.load(stream)
        result = context.put_checkpoint(
            args.key, value, args.expected_version, request_id=args.request_id
        )
    else:
        result = context.list_checkpoints()
    if isinstance(result, list):
        output = [item.__dict__ for item in result]
    else:
        output = result.__dict__
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()
