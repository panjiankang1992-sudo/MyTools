"""Tests for legacy Drive account migration."""
import importlib.util
from pathlib import Path
import unittest

SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py"; SPEC=importlib.util.spec_from_file_location("migration",SCRIPT)
MODULE=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)
class Client:
    def __init__(self): self.registered=[]
    def page(self,source,after):
        if after: return {"accounts":[],"nextAfterId":after,"complete":True}
        item={"legacyId":1,"ownerId":7,"externalAccountId":source.lower()+":1","displayName":"A",
            "providerType":"RCLONE","providerSecretRef":"secret://ref/1","remoteKey":source.lower()+"_1",
            "readOnly":True,"enabled":source=="DRIVE"}
        return {"accounts":[item],"nextAfterId":1,"complete":False}
    def register(self,item): self.registered.append(item); return {"id":"id"}
class MigrationTest(unittest.TestCase):
    def test_migrates_sanitized_metadata_from_both_sources(self):
        client=Client(); result=MODULE.execute(client)
        self.assertEqual(2,result["migrated"]); self.assertEqual(2,len(client.registered))
        self.assertTrue(all("password" not in item for item in client.registered))
if __name__=="__main__": unittest.main()
