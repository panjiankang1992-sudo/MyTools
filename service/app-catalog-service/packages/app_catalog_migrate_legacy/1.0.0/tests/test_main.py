import importlib.util
from pathlib import Path
SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py";SPEC=importlib.util.spec_from_file_location("catalog_migration",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
class Cursor:
 def __init__(self,connection):self.connection=connection;self.rows=[]
 def __enter__(self):return self
 def __exit__(self,*_):return None
 def execute(self,sql,args=None):
  if "MAX(id)"in sql:self.rows=[{"value":"2"}]
  elif "FROM t_app_market"in sql:self.rows=self.connection.apps if args[0]=="" else []
  elif "FROM t_app_version"in sql:self.rows=self.connection.versions.get(args[0],[])
  elif "FROM t_app_file"in sql:self.rows=self.connection.files.get(args[0],[])
  else:self.rows=[]
 def fetchone(self):return self.rows[0]
 def fetchall(self):return self.rows
class Connection:
 def __init__(self):
  self.apps=[{"id":"2","user_id":7,"name":"Sample","type":"app","version":"1","thumbnail_id":None,"content":"body","install_cmd":None,"download_url":None,"status":"PUBLISHED","created_time":"2026-01-01T00:00:00Z","update_time":"2026-01-01T00:00:00Z"}];self.versions={"2":[{"id":"3","version":"1","content":"body","file_id":"4","created_time":"2026-01-01T00:00:00Z"}]};self.files={"2":[{"id":"4","version_id":"3","file_type":"zip","file_name":"a.zip","file_path":"/legacy/a.zip","file_size":3,"created_time":"2026-01-01T00:00:00Z"}]};self.rolled=False
 def cursor(self):return Cursor(self)
 def rollback(self):self.rolled=True
class Client:
 def __init__(self):self.values=[]
 def import_app(self,value):self.values.append(value);return {"dryRun":value["dryRun"],"accepted":1,"skipped":0,"rejected":0}
def test_migrates_complete_aggregate_from_one_snapshot():
 connection=Connection();client=Client();result=MODULE.execute(connection,client,"catalog-v1",True)
 assert result["apps"]==1 and result["versions"]==1 and result["files"]==1 and result["accepted"]==1
 assert client.values[0]["files"][0]["legacyStoragePath"]=="/legacy/a.zip"
 assert connection.rolled and len(result["digestSha256"])==64
