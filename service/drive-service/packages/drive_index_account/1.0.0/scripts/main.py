#!/usr/bin/env python3
"""Recursively index one server-configured Drive account through bounded APIs."""

from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.parse
import urllib.request

MAX_DIRECTORIES = 100000
MAX_ITEMS = 1000000
BATCH_SIZE = 1000


class DriveClient:
    """Authenticated Drive internal API client."""
    def __init__(self, base_url: str, token: str):
        if not token: raise ValueError("Drive internal token is missing")
        self.base_url=base_url.rstrip("/"); self.token=token
    def scan(self, account_id: str, path: str) -> list[dict]:
        """List one directory without receiving provider credentials."""
        query=urllib.parse.urlencode({"path":path})
        result=self._request("GET",f"/internal/v1/drive/accounts/{account_id}/scan?{query}",None)
        if not isinstance(result,list): raise RuntimeError("Drive scan response is invalid")
        return result
    def ingest(self, account_id: str, payload: dict) -> dict:
        """Persist one idempotent index batch."""
        return self._request("POST",f"/internal/v1/drive/accounts/{account_id}/index-batches",payload)
    def _request(self, method: str, path: str, payload):
        data=None if payload is None else json.dumps(payload,separators=(",",":")).encode()
        request=urllib.request.Request(self.base_url+path,data=data,method=method,headers={
            "Authorization":f"Bearer {self.token}","Accept":"application/json","Content-Type":"application/json"})
        with urllib.request.urlopen(request,timeout=180) as response:
            return json.loads(response.read().decode())


def execute(context: dict, client: DriveClient) -> dict:
    """Breadth-first scan an account and checkpoint every bounded item batch."""
    account_id=str(context["parameters"]["accountId"]); run_id=str(context["taskInstanceId"])
    queue=[""]; directory_count=0; item_count=0
    while queue:
        parent=queue.pop(0); directory_count+=1
        if directory_count>MAX_DIRECTORIES: raise ValueError("Drive directory limit exceeded")
        items=client.scan(account_id,parent)
        item_count+=len(items)
        if item_count>MAX_ITEMS: raise ValueError("Drive item limit exceeded")
        queue.extend(str(item["remotePath"]) for item in items if item.get("directory"))
        for offset in range(0,len(items),BATCH_SIZE):
            chunk=items[offset:offset+BATCH_SIZE]
            identity=f"{parent}\0{offset}".encode()
            client.ingest(account_id,{"runId":run_id,"batchKey":hashlib.sha256(identity).hexdigest(),
                "nextCursor":queue[0] if queue else None,"complete":False,"items":chunk})
    client.ingest(account_id,{"runId":run_id,"batchKey":"complete","nextCursor":None,"complete":True,"items":[]})
    return {"accountId":account_id,"directoryCount":directory_count,"itemCount":item_count,"status":"SUCCEEDED"}


def write_result(result: dict) -> None:
    """Atomically write the task result."""
    target=Path(os.environ["TASK_RESULT_FILE"]); target.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False) as handle:
        json.dump(result,handle,separators=(",",":")); temporary=Path(handle.name)
    temporary.replace(target)


def main() -> None:
    """Run one Drive account index task."""
    context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    client=DriveClient(os.getenv("DRIVE_SERVICE_URL","http://127.0.0.1:23280"),os.getenv("DRIVE_INTERNAL_TOKEN",""))
    write_result(execute(context,client))


if __name__=="__main__": main()
