import importlib.util,json
from pathlib import Path

SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py";SPEC=importlib.util.spec_from_file_location("media_commit_analysis",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
class Response:
 def __enter__(self):return self
 def __exit__(self,*_):return False
 def read(self):return b""
def test_commits_normalized_tags_description_and_artifacts():
 captured={}
 def requester(request,timeout):captured["payload"]=json.loads(request.data);return Response()
 context={"taskInstanceId":"00000000-0000-4000-8000-000000000001","parameters":{"mediaItemId":"00000000-0000-4000-8000-000000000002","analysisVersion":"analysis-v2"},"stepOutputs":{"describe_video":{"summary":"summary","description":"description"},"generate_tags":{"tags":[{"name":"travel","confidence":.9,"type":"topic"}]},"register_thumbnail":{"artifactAssetId":"00000000-0000-4000-8000-000000000003"},"register_storyboard":{"artifacts":[{"index":1,"assetId":"00000000-0000-4000-8000-000000000004"}]}}}
 result=MODULE.execute(context,"http://media","token",requester)
 assert result=={"mediaItemId":"00000000-0000-4000-8000-000000000002","status":"SUCCEEDED","tagCount":1,"artifactCount":2}
 assert captured["payload"]["artifacts"][1]["kind"]=="STORYBOARD_FRAME_01";assert captured["payload"]["artifacts"][0]["generatorVersion"]=="analysis-v2"
