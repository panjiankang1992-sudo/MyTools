#!/usr/bin/env python3
"""Migrate legacy account metadata without exposing stored provider credentials."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.parse
import urllib.request

PAGE_SIZE=100

class MigrationClient:
    """Client for the legacy export and new Drive account APIs."""
    def __init__(self,legacy_url:str,legacy_token:str,drive_url:str,drive_token:str):
        if not legacy_token or not drive_token: raise ValueError("Drive migration tokens are missing")
        self.legacy_url=legacy_url.rstrip("/"); self.legacy_token=legacy_token
        self.drive_url=drive_url.rstrip("/"); self.drive_token=drive_token
    def page(self,source:str,after_id:int)->dict:
        """Read one sanitized legacy metadata page."""
        query=urllib.parse.urlencode({"source":source,"afterId":after_id,"limit":PAGE_SIZE})
        return self._request(self.legacy_url+"/internal/v1/migration/drive-accounts?"+query,"GET",None,self.legacy_token)
    def register(self,account:dict)->dict:
        """Idempotently register one migrated account."""
        payload={key:account[key] for key in ("ownerId","externalAccountId","displayName","providerType",
            "providerSecretRef","remoteKey","readOnly","enabled")}
        return self._request(self.drive_url+"/internal/v1/drive/accounts","POST",payload,self.drive_token)
    def _request(self,url:str,method:str,payload,token:str):
        data=None if payload is None else json.dumps(payload,separators=(",",":")).encode()
        request=urllib.request.Request(url,data=data,method=method,headers={"Authorization":f"Bearer {token}",
            "Accept":"application/json","Content-Type":"application/json"})
        with urllib.request.urlopen(request,timeout=30) as response: return json.loads(response.read().decode())

def execute(client:MigrationClient)->dict:
    """Migrate both legacy account sources and return a deterministic reconciliation digest."""
    counts={"DRIVE":0,"WEBDAV":0}; identities=[]
    for source in counts:
        after_id=0
        while True:
            page=client.page(source,after_id); accounts=page.get("accounts")
            if not isinstance(accounts,list): raise RuntimeError("Legacy account page is invalid")
            for account in accounts:
                client.register(account); counts[source]+=1
                identities.append(f"{source}:{account['legacyId']}:{account['ownerId']}:{account['externalAccountId']}")
            next_after=int(page.get("nextAfterId",after_id))
            if accounts and next_after<=after_id: raise RuntimeError("Legacy migration cursor did not advance")
            after_id=next_after
            if page.get("complete") is True: break
    digest=hashlib.sha256("\n".join(identities).encode()).hexdigest()
    return {"migrated":sum(counts.values()),"sources":counts,"digestSha256":digest}

def main()->None:
    """Run account metadata migration."""
    client=MigrationClient(os.getenv("MYTOOLS_INTERNAL_URL","http://127.0.0.1:23110"),
        os.getenv("DRIVE_MIGRATION_INTERNAL_TOKEN",""),os.getenv("DRIVE_SERVICE_URL","http://127.0.0.1:23280"),
        os.getenv("DRIVE_INTERNAL_TOKEN",""))
    result=execute(client); target=Path(os.environ["TASK_RESULT_FILE"]); target.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False) as handle:
        json.dump(result,handle,separators=(",",":")); temporary=Path(handle.name)
    temporary.replace(target)

if __name__=="__main__": main()
