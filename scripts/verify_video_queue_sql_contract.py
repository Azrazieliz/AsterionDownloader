#!/usr/bin/env python3
"""Execute the EXACT SQLite pause/retry SQL extracted from the Kotlin app.

Not a mocked rewrite of the transition: this reads the Kotlin compileStatement
strings and checks every persisted state against actual sqlite3 behavior.
No user's database is touched.
"""
import json,re,sqlite3
from pathlib import Path
SOURCE=Path(__file__).resolve().parents[1]/"app/src/main/java/com/asterion/videoflow/VideoDb.kt"

def stmt(name):
 text=SOURCE.read_text(encoding="utf-8")
 marker="fun "+name+"(id:Long):Boolean"
 begin=text.index(marker)
 match=re.search(r'compileStatement\(\s*"""([\s\S]+?)"""',text[begin:begin+1500])
 assert match,(name,"missing exact literal SQL")
 return match.group(1)

def fixture(state):
 db=sqlite3.connect(":memory:")
 db.execute("""CREATE TABLE videos(
 id INTEGER PRIMARY KEY,state TEXT NOT NULL,progress INTEGER,
 attempts INTEGER,retry_after INTEGER,request_origin TEXT,error TEXT,
 lease INTEGER,updated INTEGER,saved_uri TEXT)""")
 db.execute("""INSERT INTO videos
 (id,state,progress,attempts,retry_after,request_origin,error,
 lease,updated,saved_uri)
 VALUES(1,?,75,3,99,'website','prior',7,100,'content://saved')""",(state,))
 return db

def row(db):
 return db.execute("""SELECT state,progress,attempts,retry_after,
 request_origin,error,lease,updated,saved_uri
 FROM videos WHERE id=1""").fetchone()

def main():
 pause=stmt("pause")
 retry=stmt("retry")
 allowed_pause={"queued","running","retry_wait","error","full_only_hold"}
 allowed_retry={"paused","retry_wait","error","full_only_hold","dismissed"}
 tested=0
 for state in sorted(allowed_pause|allowed_retry|{"done","unknown"}):
  with fixture(state) as db:
   before=row(db)
   changed=db.execute(pause,(10000,1)).rowcount
   after=row(db)
   assert changed==int(state in allowed_pause),(state,changed)
   if changed:
    assert after[0]=="paused" and after[3]==0 and after[6]==8
   else:assert after==before,(state,"pause mutated protected row")
   tested+=1
  with fixture(state) as db:
   before=row(db)
   changed=db.execute(retry,(10000,1)).rowcount
   after=row(db)
   assert changed==int(state in allowed_retry),(state,changed)
   if changed:
    assert (after[0],after[1],after[2],after[3],after[4],
            after[5],after[6])==("queued",0,0,0,"user",None,8)
   else:assert after==before,(state,"retry mutated protected row")
   tested+=1
 # Requeueing a newly corroborated full original must never silently
 # promote a website-discovered (non-user-selected) held video into a
 # user-authorized transfer. Extract and run the real Kotlin SQLite query.
 source=SOURCE.read_text(encoding="utf-8")
 method=source.split("fun requeueHeldByTitle(",1)[1].split(
     "fun requeuePythonStartupFailures",1)[0]
 query_block=method.split("rawQuery(",1)[1].split("null).use",1)[0]
 selection="".join(re.findall(r'"([^"]+)"',query_block))
 assert "request_origin='user'" in selection,selection
 with sqlite3.connect(":memory:") as db:
  db.execute("CREATE TABLE videos(id INTEGER,title TEXT,state TEXT,request_origin TEXT)")
  db.executemany("INSERT INTO videos VALUES(?,?,?,?)",[
   (1,"Selected scene","full_only_hold","user"),
   (2,"Discovered scene","full_only_hold","website"),
   (3,"Already saved","done","user")])
  actual=[r[0] for r in db.execute(selection).fetchall()]
  assert actual==[1],("Held websites incorrectly promoted",actual)
  tested+=1
 # Simulated stale pause/restore after the actual saved receipt. Neither
 # operation may turn a completed selected original into another job.
 with fixture("done") as db:
  old=row(db)
  assert db.execute(pause,(11111,1)).rowcount==0
  assert db.execute(retry,(11111,1)).rowcount==0
  assert row(db)==old
  tested+=1
 print(json.dumps({"status":"PASS","sqlite_contracts_checked":tested,
  "pause_allowed":sorted(allowed_pause),"retry_allowed":sorted(allowed_retry),
  "running_retry_rejected":True,"saved_pause_retry_rejected":True,
  "lease_increment_verified":True,
  "held_website_discovery_excluded":True}),flush=True)

if __name__=="__main__":main()
