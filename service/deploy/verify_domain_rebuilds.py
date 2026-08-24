#!/usr/bin/env python3
"""Verify domain rebuild operations and their Scheduler execution evidence."""
from __future__ import annotations

import argparse
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Sequence
from pathlib import Path
from typing import Any

DIGEST=re.compile(r"^[a-f0-9]{64}$")
DOMAINS=("storage","drive","media","reader")

class Client:
    """Authenticated JSON client for one deployed service."""
    def __init__(self,base_url:str,token:str="",timeout:float=5):self.base_url=base_url.rstrip("/");self.token=token;self.timeout=timeout
    def get(self,path:str)->Any:
        """Read one JSON resource."""
        headers={"Accept":"application/json"}
        if self.token:headers["Authorization"]=f"Bearer {self.token}"
        request=urllib.request.Request(self.base_url+path,headers=headers)
        try:response=urllib.request.urlopen(request,timeout=self.timeout)
        except urllib.error.HTTPError as error:raise RuntimeError(f"GET {path} returned HTTP {error.code}")from error
        with response:body=response.read()
        return json.loads(body.decode())if body else None

def required_uuid(value:Any,field:str)->str:
    """Require a canonical UUID-like identifier without importing service models."""
    import uuid
    try:return str(uuid.UUID(str(value)))
    except (ValueError,TypeError,AttributeError)as error:raise ValueError(f"{field} is invalid")from error

def scheduler_evidence(client:Any,task_id:str)->dict[str,Any]:
    """Require a successful task and successful reported steps."""
    task=client.get(f"/api/v1/task-instances/{task_id}");results=client.get(f"/api/v1/task-instances/{task_id}/results")
    if not isinstance(task,dict)or task.get("status")!="SUCCEEDED":raise RuntimeError(f"task {task_id} is not successful")
    if not isinstance(results,dict)or results.get("status")!="SUCCEEDED" or not isinstance(results.get("steps"),list)or not results["steps"]:raise RuntimeError(f"task {task_id} results are incomplete")
    if any(not isinstance(step,dict)or step.get("status")!="SUCCEEDED" for step in results["steps"]):raise RuntimeError(f"task {task_id} contains an unsuccessful step")
    return {"taskId":task_id,"stepCount":len(results["steps"])}

def verify_storage(client:Any,scheduler:Any,entry:dict[str,Any])->dict[str,Any]:
    """Verify one completed Storage scan and stable object digest."""
    operation_id=required_uuid(entry.get("operationId"),"storage.operationId");operation=client.get(f"/api/internal/v1/storage/operations/{operation_id}");digest=client.get(f"/api/internal/v1/storage/operations/{operation_id}/digest")
    if not isinstance(operation,dict)or operation.get("status")!="SUCCEEDED" or operation.get("operationType")!="SCAN_ROOT":raise RuntimeError(f"storage operation {operation_id} is not a successful scan")
    if not isinstance(digest,dict)or digest.get("itemCount")!=operation.get("itemCount")or DIGEST.fullmatch(str(digest.get("contentSha256")or""))is None:raise RuntimeError(f"storage operation {operation_id} digest is invalid")
    task=scheduler_evidence(scheduler,required_uuid(operation.get("taskInstanceId"),"storage.taskInstanceId"))
    return {"operationId":operation_id,"itemCount":digest["itemCount"],"contentSha256":digest["contentSha256"],**task}

def verify_operation(client:Any,scheduler:Any,entry:dict[str,Any],domain:str,path:str)->dict[str,Any]:
    """Verify one Drive or Media operation and its task."""
    operation_id=required_uuid(entry.get("operationId"),f"{domain}.operationId");owner=int(entry.get("ownerId",0))
    if owner<=0:raise ValueError(f"{domain}.ownerId is invalid")
    operation=client.get(path.format(operation_id=operation_id,owner_id=owner))
    if not isinstance(operation,dict)or operation.get("status")!="SUCCEEDED":raise RuntimeError(f"{domain} operation {operation_id} is not successful")
    task=scheduler_evidence(scheduler,required_uuid(operation.get("taskInstanceId"),f"{domain}.taskInstanceId"))
    return {"operationId":operation_id,"ownerId":owner,**task}

def verify_media_quiescent(client:Any)->dict[str,int]:
    """Require no staged scans or unfinished analysis across every media page."""
    after=None;pages=0;running=analyzing=failed=staging=0
    while True:
        query="?limit=200"+("&afterId="+urllib.parse.quote(after)if after else"");page=client.get("/internal/v1/media/reconciliation"+query)
        if not isinstance(page,dict):raise TypeError("media reconciliation is invalid")
        pages+=1;staging=max(staging,int(page.get("stagingScanCount",0)));running+=int(page.get("runningAnalysisCount",0));analyzing+=int(page.get("analyzingCount",0));failed+=int(page.get("failedAnalysisCount",0));after=page.get("nextAfterId")
        if not after:break
        if pages>100000:raise RuntimeError("media reconciliation pagination exceeded limit")
    if staging or running or analyzing or failed:raise RuntimeError("media rebuild state is not quiescent")
    return {"pageCount":pages,"stagingScanCount":staging,"runningAnalysisCount":running,"analyzingCount":analyzing,"failedAnalysisCount":failed}

def verify_reader(client:Any,scheduler:Any,entry:dict[str,Any])->dict[str,Any]:
    """Verify one atomically published Reader library generation."""
    rebuild_id=required_uuid(entry.get("rebuildId"),"reader.rebuildId");value=client.get(f"/api/internal/v1/library-rebuilds/{rebuild_id}")
    if not isinstance(value,dict)or value.get("status")!="SUCCEEDED" or int(value.get("indexedCount",-1))<0:raise RuntimeError(f"reader rebuild {rebuild_id} is not published")
    task=scheduler_evidence(scheduler,required_uuid(value.get("taskId"),"reader.taskId"))
    return {"rebuildId":rebuild_id,"ownerId":value.get("ownerId"),"indexedCount":value["indexedCount"],**task}

def verify(config:dict[str,Any],clients:dict[str,Any])->dict[str,Any]:
    """Verify the complete configured domain rebuild evidence set."""
    unknown=set(config)-set(DOMAINS)
    if unknown:raise ValueError("unknown rebuild evidence domain")
    if any(not isinstance(config.get(domain,[]),list)for domain in DOMAINS):raise ValueError("rebuild evidence entries must be arrays")
    result={"storage":[verify_storage(clients["storage"],clients["scheduler"],entry)for entry in config.get("storage",[])],"drive":[verify_operation(clients["drive"],clients["scheduler"],entry,"drive","/internal/v1/drive/operations/{operation_id}?ownerId={owner_id}")for entry in config.get("drive",[])],"media":[verify_operation(clients["media"],clients["scheduler"],entry,"media","/internal/v1/media/operations/{operation_id}?ownerId={owner_id}")for entry in config.get("media",[])],"reader":[verify_reader(clients["reader"],clients["scheduler"],entry)for entry in config.get("reader",[])]}
    if result["media"]:result["mediaQuiescence"]=verify_media_quiescent(clients["media"])
    if not any(result[domain]for domain in DOMAINS):raise ValueError("rebuild evidence is empty")
    return {"ready":True,"domains":result}

def main(argv:Sequence[str]|None=None)->int:
    """Run remote domain rebuild verification."""
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--evidence",type=Path,required=True);parser.add_argument("--scheduler-url",default="http://127.0.0.1:23410");parser.add_argument("--storage-url",default="http://127.0.0.1:23240");parser.add_argument("--drive-url",default="http://127.0.0.1:23280");parser.add_argument("--media-url",default="http://127.0.0.1:23300");parser.add_argument("--reader-url",default="http://127.0.0.1:23230");parser.add_argument("--request-timeout",type=float,default=5);arguments=parser.parse_args(argv)
    try:
        config=json.loads(arguments.evidence.read_text(encoding="utf-8"));clients={"scheduler":Client(arguments.scheduler_url,timeout=arguments.request_timeout),"storage":Client(arguments.storage_url,os.getenv("STORAGE_INTERNAL_TOKEN",""),arguments.request_timeout),"drive":Client(arguments.drive_url,os.getenv("DRIVE_INTERNAL_TOKEN",""),arguments.request_timeout),"media":Client(arguments.media_url,os.getenv("MEDIA_LIBRARY_INTERNAL_TOKEN",""),arguments.request_timeout),"reader":Client(arguments.reader_url,os.getenv("READER_INTERNAL_TOKEN",""),arguments.request_timeout)};report=verify(config,clients)
    except (OSError,TypeError,ValueError,RuntimeError,json.JSONDecodeError)as error:print(json.dumps({"ready":False,"error":str(error)},separators=(",",":")));return 2
    print(json.dumps(report,separators=(",",":")));return 0

if __name__=="__main__":raise SystemExit(main())
