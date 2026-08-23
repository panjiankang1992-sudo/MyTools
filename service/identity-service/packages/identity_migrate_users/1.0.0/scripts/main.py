#!/usr/bin/env python3
"""Migrate legacy users and roles without copying reusable session tokens."""
from __future__ import annotations
import hashlib,json,os,tempfile,urllib.parse,urllib.request
from pathlib import Path
PAGE_SIZE=100
class Client:
 """Legacy export and Identity import client."""
 def __init__(self,mytools_url,mytools_token,identity_url,identity_token):
  if not mytools_token or not identity_token: raise ValueError("Identity migration tokens are missing")
  self.mytools=mytools_url.rstrip("/");self.mytools_token=mytools_token;self.identity=identity_url.rstrip("/");self.identity_token=identity_token
 def page(self,after_id):
  """Read one protected legacy page."""
  query=urllib.parse.urlencode({"afterId":after_id,"limit":PAGE_SIZE});return self._request(self.mytools+"/internal/v1/migration/identity-users?"+query,"GET",None,self.mytools_token)
 def import_user(self,user):
  """Idempotently import one user through the domain API."""
  return self._request(self.identity+"/internal/v1/identity/users","POST",user,self.identity_token)
 def _request(self,url,method,payload,token):
  data=None if payload is None else json.dumps(payload,separators=(",",":")).encode();request=urllib.request.Request(url,data=data,method=method,headers={"Authorization":f"Bearer {token}","Accept":"application/json","Content-Type":"application/json"})
  with urllib.request.urlopen(request,timeout=30) as response:return json.loads(response.read().decode())
def execute(client):
 """Migrate all users and return a non-secret reconciliation digest."""
 after=0;count=0;identities=[]
 while True:
  page=client.page(after);users=page.get("users")
  if not isinstance(users,list):raise RuntimeError("Identity migration page is invalid")
  for user in users:
   if not str(user.get("passwordHash","")).startswith(("$2a$","$2b$","$2y$")):raise ValueError("Legacy password hash is not BCrypt")
   client.import_user(user);count+=1;identities.append(f"{user['id']}:{user['externalUserId']}:{user['username']}:{','.join(user['roles'])}")
  next_after=int(page.get("nextAfterId",after))
  if users and next_after<=after:raise RuntimeError("Identity migration cursor did not advance")
  after=next_after
  if page.get("complete") is True:break
 return {"migrated":count,"digestSha256":hashlib.sha256("\n".join(identities).encode()).hexdigest()}
def main():
 """Run identity migration."""
 client=Client(os.getenv("MYTOOLS_INTERNAL_URL","http://127.0.0.1:23110"),os.getenv("IDENTITY_MIGRATION_INTERNAL_TOKEN",""),os.getenv("IDENTITY_SERVICE_URL","http://127.0.0.1:23290"),os.getenv("IDENTITY_INTERNAL_TOKEN",""));result=execute(client)
 target=Path(os.environ["TASK_RESULT_FILE"]);target.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.NamedTemporaryFile("w",encoding="utf-8",dir=target.parent,delete=False) as handle:json.dump(result,handle,separators=(",",":"));temporary=Path(handle.name)
 temporary.replace(target)
if __name__=="__main__":main()
