#!/usr/bin/env python3
"""为部署验收产生确定性的成功、失败、超时和取消行为。"""
from __future__ import annotations
import argparse,json,os,sys,time
from pathlib import Path

SCENARIOS={"success","failure","timeout","cancel"}

def write_result(value:dict)->None:
    """写入 Executor 约定的结构化结果。"""
    Path(os.environ["TASK_RESULT_FILE"]).write_text(json.dumps(value,separators=(",",":")),encoding="utf-8")

def execute(context:dict,terminal:str|None)->int:
    """执行一个无业务副作用的验收场景。"""
    scenario=str(context.get("parameters",{}).get("scenario",""))
    if scenario not in SCENARIOS:raise ValueError("acceptance scenario is invalid")
    if terminal is not None:
        write_result({"scenario":scenario,"terminal":terminal,"accepted":True});return 0
    if scenario=="success":write_result({"scenario":scenario,"accepted":True});return 0
    if scenario=="failure":write_result({"scenario":scenario,"accepted":True});return 7
    time.sleep(60);return 0

def main()->None:
    """运行验收脚本。"""
    parser=argparse.ArgumentParser();parser.add_argument("--terminal",choices=("failure","timeout","cancel"));arguments=parser.parse_args()
    context=json.loads(Path(os.environ["TASK_CONTEXT_FILE"]).read_text(encoding="utf-8"))
    sys.exit(execute(context,arguments.terminal))

if __name__=="__main__":main()
