#!/usr/bin/env python3
"""Trigger one bounded IMAP poll through Messaging Service."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import tempfile
from urllib.request import Request, urlopen

ACCOUNT_PATTERN = re.compile(r"^[A-Za-z0-9_]{1,64}$")


def execute(parameters: dict, base_url: str, token: str, opener=urlopen) -> dict:
    """Call the account-bound email poll API without handling credentials."""
    account_key = str(parameters["accountKey"])
    if not ACCOUNT_PATTERN.fullmatch(account_key):
        raise ValueError("accountKey is invalid")
    if not token:
        raise ValueError("Messaging Service internal token is missing")
    body = json.dumps({"accountKey": account_key}, separators=(",", ":")).encode()
    request = Request(base_url.rstrip("/") + "/internal/v1/adapters/email/poll", data=body,
                      method="POST", headers={"Authorization": f"Bearer {token}",
                                              "Content-Type": "application/json"})
    with opener(request, timeout=300) as response:
        return json.loads(response.read().decode())


def write_result(result: dict) -> None:
    """Atomically write the structured poll result."""
    target = Path(os.environ["TASK_RESULT_FILE"])
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=target.parent, delete=False) as handle:
        json.dump(result, handle, separators=(",", ":"))
        temporary = Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Execute one email polling task."""
    context = json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    result = execute(context["parameters"], os.getenv("MESSAGING_SERVICE_URL", "http://127.0.0.1:23250"),
                     os.getenv("MESSAGING_INTERNAL_TOKEN", ""))
    write_result(result)


if __name__ == "__main__":
    main()
