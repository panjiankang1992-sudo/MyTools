import importlib.util,json
from pathlib import Path

SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py";SPEC=importlib.util.spec_from_file_location("media_begin_analysis",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
class Response:
 def __enter__(self):return self
 def __exit__(self,*_):return False
 def read(self):return json.dumps({"id":"00000000-0000-4000-8000-000000000001","mediaItemId":"00000000-0000-4000-8000-000000000002","status":"RUNNING"}).encode()
def test_binds_task_version_and_asset():
 captured={}
 def requester(request,timeout):captured["payload"]=json.loads(request.data);return Response()
 result=MODULE.execute({"taskInstanceId":"00000000-0000-4000-8000-000000000003","parameters":{"mediaItemId":"00000000-0000-4000-8000-000000000002","assetRegistryId":"00000000-0000-4000-8000-000000000004","analysisVersion":"v2"}},"http://media","token",requester)
 assert result["status"]=="RUNNING";assert captured["payload"]["assetId"].endswith("0004");assert captured["payload"]["analysisVersion"]=="v2"
