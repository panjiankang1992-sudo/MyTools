import importlib.util
from pathlib import Path
import unittest
SCRIPT=Path(__file__).with_name("verify_domain_rebuilds.py");SPEC=importlib.util.spec_from_file_location("domain_rebuilds",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
IDS={"storage":"00000000-0000-4000-8000-000000000001","drive":"00000000-0000-4000-8000-000000000002","media":"00000000-0000-4000-8000-000000000003","reader":"00000000-0000-4000-8000-000000000004","task":"00000000-0000-4000-8000-000000000010"}
class Fake:
 def __init__(self,values):self.values=values
 def get(self,path):
  value=self.values[path]
  return value()if callable(value)else value
def scheduler():return Fake({f"/api/v1/task-instances/{IDS['task']}":{"status":"SUCCEEDED"},f"/api/v1/task-instances/{IDS['task']}/results":{"status":"SUCCEEDED","steps":[{"stepName":"run","status":"SUCCEEDED"}]}})
def clients():
 return {"scheduler":scheduler(),"storage":Fake({f"/api/internal/v1/storage/operations/{IDS['storage']}":{"status":"SUCCEEDED","operationType":"SCAN_ROOT","itemCount":2,"taskInstanceId":IDS["task"]},f"/api/internal/v1/storage/operations/{IDS['storage']}/digest":{"itemCount":2,"contentSha256":"a"*64}}),"drive":Fake({f"/internal/v1/drive/operations/{IDS['drive']}?ownerId=1":{"status":"SUCCEEDED","taskInstanceId":IDS["task"]}}),"media":Fake({f"/internal/v1/media/operations/{IDS['media']}?ownerId=1":{"status":"SUCCEEDED","taskInstanceId":IDS["task"]},"/internal/v1/media/reconciliation?limit=200":{"stagingScanCount":0,"runningAnalysisCount":0,"analyzingCount":0,"failedAnalysisCount":0,"nextAfterId":None}}),"reader":Fake({f"/api/internal/v1/library-rebuilds/{IDS['reader']}":{"status":"SUCCEEDED","taskId":IDS["task"],"ownerId":1,"indexedCount":3}})}
class DomainRebuildTest(unittest.TestCase):
 def test_accepts_domain_and_scheduler_evidence(self):
  config={"storage":[{"operationId":IDS["storage"]}],"drive":[{"operationId":IDS["drive"],"ownerId":1}],"media":[{"operationId":IDS["media"],"ownerId":1}],"reader":[{"rebuildId":IDS["reader"]}]}
  report=MODULE.verify(config,clients());self.assertTrue(report["ready"]);self.assertEqual(2,report["domains"]["storage"][0]["itemCount"]);self.assertEqual(3,report["domains"]["reader"][0]["indexedCount"])
 def test_rejects_unsuccessful_scheduler_step(self):
  current=clients();current["scheduler"].values[f"/api/v1/task-instances/{IDS['task']}/results"]["steps"][0]["status"]="FAILED"
  with self.assertRaises(RuntimeError):MODULE.verify({"storage":[{"operationId":IDS["storage"]}]},current)
 def test_rejects_media_non_quiescence(self):
  current=clients();current["media"].values["/internal/v1/media/reconciliation?limit=200"]["runningAnalysisCount"]=1
  with self.assertRaises(RuntimeError):MODULE.verify({"media":[{"operationId":IDS["media"],"ownerId":1}]},current)
 def test_rejects_empty_or_unknown_evidence(self):
  with self.assertRaises(ValueError):MODULE.verify({},clients())
  with self.assertRaises(ValueError):MODULE.verify({"unknown":[]},clients())
if __name__=="__main__":unittest.main()
