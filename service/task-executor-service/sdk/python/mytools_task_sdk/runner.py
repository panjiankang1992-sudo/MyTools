"""为已发布 Python 任务提供统一错误分类的启动包装器。"""

from __future__ import annotations

from pathlib import Path
import runpy
import socket
import sys
import urllib.error

from .errors import write_task_error


def classify(exception: BaseException) -> tuple[str, str] | None:
    """将常见基础设施和业务异常映射为稳定错误类别。"""
    if isinstance(exception, urllib.error.HTTPError):
        status = exception.code
        if status == 429:
            return "REMOTE_RATE_LIMITED", "RATE_LIMITED"
        if status >= 500:
            return "REMOTE_SERVICE_UNAVAILABLE", "TRANSIENT"
        return {
            400: ("REMOTE_REQUEST_INVALID", "VALIDATION"),
            401: ("REMOTE_AUTHENTICATION_FAILED", "AUTHENTICATION"),
            403: ("REMOTE_AUTHORIZATION_FAILED", "AUTHORIZATION"),
            404: ("REMOTE_RESOURCE_NOT_FOUND", "NOT_FOUND"),
            409: ("REMOTE_STATE_CONFLICT", "CONFLICT"),
        }.get(status, ("REMOTE_REQUEST_REJECTED", "PERMANENT"))
    if isinstance(exception, (urllib.error.URLError, TimeoutError, ConnectionError, socket.timeout)):
        return "REMOTE_NETWORK_FAILURE", "TRANSIENT"
    if isinstance(exception, FileNotFoundError):
        return "LOCAL_RESOURCE_NOT_FOUND", "NOT_FOUND"
    if isinstance(exception, PermissionError):
        return "LOCAL_RESOURCE_FORBIDDEN", "AUTHORIZATION"
    if isinstance(exception, ValueError):
        return "TASK_INPUT_INVALID", "VALIDATION"
    return None


def main() -> None:
    """执行目标脚本，并在可确定时写入标准错误文档。"""
    if len(sys.argv) < 2:
        raise SystemExit("task runner requires an entrypoint")
    entrypoint = sys.argv[1]
    sys.argv = [entrypoint, *sys.argv[2:]]
    original_path = sys.path.copy()
    # 与直接执行脚本保持一致，使已校验包内的辅助模块可导入。
    sys.path.insert(0, str(Path(entrypoint).resolve().parent))
    try:
        runpy.run_path(entrypoint, run_name="__main__")
    except SystemExit:
        raise
    except BaseException as exception:
        classified = classify(exception)
        if classified is not None:
            code, category = classified
            write_task_error(code, category, str(exception)[:2048])
        raise
    finally:
        sys.path[:] = original_path


if __name__ == "__main__":
    main()
