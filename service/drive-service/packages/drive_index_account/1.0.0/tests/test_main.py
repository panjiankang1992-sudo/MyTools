"""Tests for Drive account indexing."""
import importlib.util
from pathlib import Path
import unittest

SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py"
SPEC=importlib.util.spec_from_file_location("drive_index_account",SCRIPT)
MODULE=importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(MODULE)

class Client:
    def __init__(self): self.batches=[]
    def scan(self,_account,path):
        return {"":[{"remotePath":"folder","directory":True},{"remotePath":"root.txt","directory":False}],
                "folder":[{"remotePath":"folder/file.txt","directory":False}]}[path]
    def ingest(self,_account,payload): self.batches.append(payload); return {"status":"RUNNING"}

class DriveIndexTest(unittest.TestCase):
    def test_indexes_tree_and_completes_after_all_batches(self):
        client=Client(); result=MODULE.execute({"taskInstanceId":"00000000-0000-4000-8000-000000000099",
            "parameters":{"accountId":"00000000-0000-4000-8000-000000000001"}},client)
        self.assertEqual(2,result["directoryCount"]); self.assertEqual(3,result["itemCount"])
        self.assertTrue(client.batches[-1]["complete"]); self.assertEqual([],client.batches[-1]["items"])

if __name__=="__main__": unittest.main()
