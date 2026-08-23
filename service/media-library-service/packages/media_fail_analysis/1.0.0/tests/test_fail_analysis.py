import importlib.util,json
from pathlib import Path

SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py";SPEC=importlib.util.spec_from_file_location("media_fail_analysis",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
class Response:
 def __enter__(self):return self
 def __exit__(self,*_):return False
 def read(self):return b""
def test_maps_timeout_scenario_to_domain_terminal_state():
 captured={}
 def requester(request,timeout):captured["payload"]=json.loads(request.data);return Response()
 result=MODULE.execute({"stepName":"analysis_timeout","taskInstanceId":"00000000-0000-4000-8000-000000000001","parameters":{"mediaItemId":"00000000-0000-4000-8000-000000000002"}},"http://media","token",requester)
 assert result["status"]=="TIMED_OUT";assert captured["payload"]["errorCode"]=="MEDIA_ANALYSIS_TIMEOUT"
