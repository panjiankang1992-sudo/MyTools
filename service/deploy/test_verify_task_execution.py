import importlib.util
from pathlib import Path
from unittest import mock
import unittest
SCRIPT=Path(__file__).with_name("verify_task_execution.py");SPEC=importlib.util.spec_from_file_location("task_acceptance",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
class FakeClient:
    def __init__(self):self.tasks={};self.cancelled=set()
    def request(self,path,method="GET",payload=None):
        if path=="/api/v1/task-instances":
            key=payload["idempotencyKey"];scenario=payload["parameters"]["scenario"]
            if key not in self.tasks:self.tasks[key]={"id":str(len(self.tasks)+1),"scenario":scenario,"status":"RUNNING"}
            return self.tasks[key]
        task_id=path.split("/")[4];task=next(value for value in self.tasks.values()if value["id"]==task_id)
        if path.endswith("/cancel"):task["status"]="CANCELLED";self.cancelled.add(task_id);return task
        if path.endswith("/results"):
            terminal=MODULE.EXPECTATIONS[task["scenario"]][1];steps=[{"stepName":"exercise_executor","status":task["status"]}]
            if terminal:steps.append({"stepName":terminal,"status":"SUCCEEDED"})
            return {"status":task["status"],"steps":steps}
        if task["scenario"]!="cancel":task["status"]=MODULE.EXPECTATIONS[task["scenario"]][0]
        return task
class TaskExecutionAcceptanceTest(unittest.TestCase):
    def test_accepts_all_terminal_paths_and_replay(self):
        report=MODULE.run(FakeClient(),"fixture",1)
        self.assertTrue(report["ready"]);self.assertEqual(4,len(report["scenarios"]))
    def test_rejects_missing_terminal_step(self):
        client=FakeClient();task={"id":"1","status":"FAILED"}
        client.tasks["x"]={"id":"1","scenario":"success","status":"FAILED"}
        with self.assertRaises(RuntimeError):MODULE.validate_results(client,"failure",task)
    def test_http_client_sends_scoped_business_credentials(self):
        response=mock.MagicMock();response.status=200;response.read.return_value=b'{}'
        response.__enter__.return_value=response
        with mock.patch.object(MODULE.urllib.request,"urlopen",return_value=response)as open_url:
            MODULE.HttpClient("http://scheduler",service_id="acceptance-client",token="secret").request("/api/v1/task-instances")
        request=open_url.call_args.args[0]
        self.assertEqual("acceptance-client",request.get_header("X-task-service-id"))
        self.assertEqual("secret",request.get_header("X-task-business-token"))
if __name__=="__main__":unittest.main()
