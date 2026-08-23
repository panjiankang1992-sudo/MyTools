"""Tests for Media Library item registration."""
import importlib.util,json
from pathlib import Path
import tempfile,unittest
SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py";SPEC=importlib.util.spec_from_file_location("media_register_item",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
class Response:
 def __enter__(self):return self
 def __exit__(self,*_):return False
 def read(self):return json.dumps({"id":"00000000-0000-4000-8000-000000000002","version":1}).encode()
class MediaRegisterTest(unittest.TestCase):
 def test_does_not_send_physical_path(self):
  with tempfile.TemporaryDirectory()as directory:
   source=Path(directory)/"video.mp4";source.write_bytes(b"media");captured={}
   def requester(request,timeout):captured["body"]=json.loads(request.data);return Response()
   result=MODULE.execute({"taskInstanceId":"00000000-0000-4000-8000-000000000001","parameters":{"assetId":"42","sourcePath":str(source),"contentSha256":"a"*64,"assetMimeType":"video/mp4","assetSourceType":"MEDIA_SCAN","assetSourceBusinessId":"scan:item","directoryKey":"movies","directoryName":"Movies","scanId":"00000000-0000-4000-8000-000000000004"},"stepOutputs":{"probe":{"durationMs":1},"register_asset":{"assetId":"00000000-0000-4000-8000-000000000003"}}},"http://media","token",requester)
   self.assertEqual(5,captured["body"]["sizeBytes"]);self.assertNotIn(str(source),json.dumps(captured["body"]));self.assertEqual("MEDIA_SCAN",captured["body"]["sourceType"]);self.assertEqual("movies",captured["body"]["directoryKey"]);self.assertEqual("00000000-0000-4000-8000-000000000004",captured["body"]["scanId"]);self.assertEqual("00000000-0000-4000-8000-000000000003",result["assetId"])
if __name__=="__main__":unittest.main()
