import importlib.util
from pathlib import Path

SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py";SPEC=importlib.util.spec_from_file_location("asset_register_media_storyboard",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
class Storage:
 def publish(self,path,root,relative,key,size,sha):return "storage://managed/"+relative
class Assets:
 def __init__(self):self.version=4;self.links=[]
 def register(self,payload):return {"id":f"00000000-0000-4000-8000-00000000000{len(self.links)+2}","version":1}
 def register_artifact(self,parent,payload):self.links.append(payload);self.version+=1;return {"id":parent,"version":self.version}
def test_publishes_and_links_ordered_storyboard_frames(tmp_path):
 first=tmp_path/"1.jpg";second=tmp_path/"2.jpg";first.write_bytes(b"a");second.write_bytes(b"b");assets=Assets()
 result=MODULE.execute({"parameters":{"assetRegistryId":"00000000-0000-4000-8000-000000000001","ownerId":7,"analysisVersion":"v2"},"stepOutputs":{"register_thumbnail":{"parentVersion":4},"generate_storyboard":{"frames":[{"index":2,"artifactPath":str(second),"artifactSha256":"b"*64,"size":1},{"index":1,"artifactPath":str(first),"artifactSha256":"a"*64,"size":1}]}}},Storage(),assets)
 assert [item["index"]for item in result["artifacts"]]==[1,2];assert assets.links[0]["expectedAssetVersion"]==4;assert assets.links[1]["expectedAssetVersion"]==5;assert result["parentVersion"]==6
