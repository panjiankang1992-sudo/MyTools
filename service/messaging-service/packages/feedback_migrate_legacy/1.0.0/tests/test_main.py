import importlib.util
from pathlib import Path
SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py";SPEC=importlib.util.spec_from_file_location("feedback",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
class Cursor:
 def __init__(self,connection):self.connection=connection;self.rows=[]
 def __enter__(self):return self
 def __exit__(self,*_):return None
 def execute(self,sql,args=None):
  if "MAX(id)"in sql:self.rows=[{"value":1}]
  elif "FROM t_feedback"in sql:self.rows=self.connection.rows if args[0]==0 else []
  else:self.rows=[]
 def fetchone(self):return self.rows[0]
 def fetchall(self):return self.rows
class Connection:
 def __init__(self):self.rows=[{"id":1,"username":"User","email":"u@example.com","phone":None,"category":"BUG","title":"Title","content":"Body","status":"RECEIVED","created_time":"2026-01-01T00:00:00+08:00","update_time":"2026-01-01T00:00:00+08:00"}];self.rolled=False
 def cursor(self):return Cursor(self)
 def rollback(self):self.rolled=True
class Client:
 def migrate(self,key,dry,items):self.items=items;return {"dryRun":dry,"accepted":len(items),"skipped":0,"rejected":0}
def test_captures_complete_feedback_and_closes_counts():
 connection=Connection();client=Client();result=MODULE.execute(connection,client,"feedback-v1",True)
 assert result["exported"]==1 and result["accepted"]==1 and result["rejected"]==0
 assert client.items[0]["content"]=="Body" and connection.rolled and len(result["digestSha256"])==64
