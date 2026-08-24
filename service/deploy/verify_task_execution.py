#!/usr/bin/env python3
"""Verify deployed Scheduler and Executor terminal paths without touching business data."""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
import uuid
from collections.abc import Sequence
from typing import Any

TERMINAL={"SUCCEEDED","FAILED","TIMED_OUT","CANCELLED"}
EXPECTATIONS={"success":("SUCCEEDED",None),"failure":("FAILED","on_failure"),"timeout":("TIMED_OUT","on_timeout"),"cancel":("CANCELLED","on_cancel")}

class HttpClient:
    """Minimal Scheduler JSON client."""
    def __init__(self,base_url:str,timeout:float=3):
        self.base_url=base_url;self.timeout=timeout
    def request(self,path:str,method:str="GET",payload:dict[str,Any]|None=None)->Any:
        """Send one JSON request and require a successful response."""
        data=None if payload is None else json.dumps(payload,separators=(",",":")).encode()
        request=urllib.request.Request(self.base_url.rstrip("/")+path,data=data,method=method,headers={"Accept":"application/json","Content-Type":"application/json"})
        try:response=urllib.request.urlopen(request,timeout=self.timeout)
        except urllib.error.HTTPError as error:raise RuntimeError(f"Scheduler returned HTTP {error.code}")from error
        with response:
            body=response.read()
            if response.status not in range(200,300):raise RuntimeError(f"Scheduler returned HTTP {response.status}")
        return json.loads(body.decode())if body else None

def create(client:Any,scenario:str,run_key:str)->dict[str,Any]:
    """Create one isolated acceptance task."""
    value=client.request("/api/v1/task-instances","POST",{"taskName":"system_executor_acceptance","idempotencyKey":f"acceptance:{run_key}:{scenario}","businessType":"SYSTEM_ACCEPTANCE","businessId":run_key,"parentTaskInstanceId":None,"priority":100,"parameters":{"scenario":scenario},"requiredNodeLabels":{}})
    if not isinstance(value,dict)or not value.get("id"):raise RuntimeError("Scheduler create response is invalid")
    return value

def wait_status(client:Any,task_id:str,deadline:float,running:bool=False)->dict[str,Any]:
    """Wait for a running or terminal task state."""
    while time.monotonic()<deadline:
        value=client.request(f"/api/v1/task-instances/{task_id}")
        if isinstance(value,dict)and ((running and value.get("status")=="RUNNING")or(not running and value.get("status")in TERMINAL)):return value
        time.sleep(.25)
    raise RuntimeError(f"Task {task_id} did not reach the expected state")

def validate_results(client:Any,scenario:str,task:dict[str,Any])->dict[str,Any]:
    """Validate final task status and scenario terminal step evidence."""
    expected_status,terminal_step=EXPECTATIONS[scenario]
    if task.get("status")!=expected_status:raise RuntimeError(f"{scenario} finished as {task.get('status')}")
    results=client.request(f"/api/v1/task-instances/{task['id']}/results")
    if not isinstance(results,dict)or results.get("status")!=expected_status or not isinstance(results.get("steps"),list):raise RuntimeError(f"{scenario} result payload is invalid")
    steps=results["steps"]
    if terminal_step is not None and not any(step.get("stepName")==terminal_step and step.get("status")=="SUCCEEDED" for step in steps if isinstance(step,dict)):raise RuntimeError(f"{scenario} terminal step evidence is missing")
    return {"taskId":task["id"],"status":expected_status,"stepCount":len(steps),"terminalStep":terminal_step}

def run(client:Any,run_key:str,deadline_seconds:float)->dict[str,Any]:
    """Run all terminal scenarios and verify idempotent creation."""
    evidence=[]
    for scenario in EXPECTATIONS:
        created=create(client,scenario,run_key);deadline=time.monotonic()+deadline_seconds
        if scenario=="cancel":
            running=wait_status(client,created["id"],deadline,running=True)
            client.request(f"/api/v1/task-instances/{running['id']}/cancel","POST",{})
        final=wait_status(client,created["id"],deadline);evidence.append(validate_results(client,scenario,final))
        replay=create(client,scenario,run_key)
        if replay.get("id")!=created.get("id"):raise RuntimeError(f"{scenario} idempotency replay created a duplicate")
    return {"ready":True,"runKey":run_key,"scenarios":evidence}

def main(argv:Sequence[str]|None=None)->int:
    """Run deployed task execution acceptance."""
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--scheduler-url",default="http://127.0.0.1:23410");parser.add_argument("--run-key",default=f"run-{uuid.uuid4()}");parser.add_argument("--deadline-seconds",type=float,default=90);parser.add_argument("--request-timeout",type=float,default=3);arguments=parser.parse_args(argv)
    try:report=run(HttpClient(arguments.scheduler_url,arguments.request_timeout),arguments.run_key,arguments.deadline_seconds)
    except (OSError,RuntimeError,ValueError,json.JSONDecodeError)as error:print(json.dumps({"ready":False,"error":str(error)},separators=(",",":")));return 2
    print(json.dumps(report,separators=(",",":")));return 0

if __name__=="__main__":raise SystemExit(main())
