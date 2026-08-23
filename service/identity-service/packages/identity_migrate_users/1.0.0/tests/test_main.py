"""Tests for identity user migration."""
import importlib.util
from pathlib import Path
import unittest
SCRIPT=Path(__file__).parents[1]/"scripts"/"main.py";SPEC=importlib.util.spec_from_file_location("identity_migrate",SCRIPT);MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)
class Client:
 def __init__(self):self.imported=[]
 def page(self,after):
  if after:return {"users":[],"nextAfterId":after,"complete":True}
  return {"users":[{"id":7,"externalUserId":"mytools:7","username":"alice","email":"a@example.com","passwordHash":"$2a$10$"+"x"*53,"status":"ACTIVE","credentialVersion":0,"roles":["USER"]}],"nextAfterId":7,"complete":False}
 def import_user(self,user):self.imported.append(user)
class IdentityMigrationTest(unittest.TestCase):
 def test_migrates_hash_but_no_session_tokens(self):
  client=Client();result=MODULE.execute(client);self.assertEqual(1,result["migrated"]);self.assertIn("passwordHash",client.imported[0]);self.assertNotIn("refreshToken",client.imported[0]);self.assertNotIn("accessToken",client.imported[0])
if __name__=="__main__":unittest.main()
