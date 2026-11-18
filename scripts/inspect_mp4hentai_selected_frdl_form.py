#!/usr/bin/env python3
"""Inspect forms on exact MP4Hentai selected Mirror -> Freedl -> frdl.hk page.
Read only; never post a CAPTCHA, expose CSRF values or download unrelated files.
"""
import json,re,sys,urllib.parse
from html.parser import HTMLParser
from inspect_mp4hentai_selected_freedl_redirect import main as selected_chain
class FormParser(HTMLParser):
 def __init__(self):
  super().__init__();self.forms=[];self.active=None;self.gates=[];self.button=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="form":
   self.active={"method":a.get("method","GET").upper(),"action":a.get("action",""),
    "id":a.get("id","")[:45],"names":[],"types":[],"buttons":[]}
   self.forms.append(self.active)
  elif self.active and tag in ("input","select","textarea"):
   self.active["names"].append(a.get("name","")[:45])
   self.active["types"].append(a.get("type",tag)[:30])
  elif self.active and tag=="button":
   self.button={"type":a.get("type",""),"text":""}
   self.active["buttons"].append(self.button)
  if(tag in ("div","iframe","input") and
      ("captcha" in (a.get("class","")+" "+a.get("id","")).lower() or
       bool(a.get("data-sitekey")) or "recaptcha" in a.get("src","").lower())):
   self.gates.append({"element":tag,"sitekey_present":bool(a.get("data-sitekey")),
                      "inside_form":self.active is not None})
 def handle_data(self,data):
  if self.button and len(self.button["text"])<120:
   self.button["text"]+=data[:120]
 def handle_endtag(self,tag):
  if tag=="button":self.button=None
  if tag=="form":self.active=None
def main():
 source=selected_chain(return_selected_terminal=True)
 if not isinstance(source,dict) or source["http"]!=200 or source["mime"]!="text/html":
  raise RuntimeError("Publisher-selected file did not resolve to HTML")
 url=urllib.parse.urlsplit(source["selected_url"])
 if url.scheme!="https" or url.hostname not in ("frdl.hk","www.frdl.hk"):
  raise RuntimeError("Selected official terminal is not frdl.hk")
 doc=FormParser();doc.feed(source["html"])
 records=[]
 for form in doc.forms[:15]:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(source["selected_url"],form["action"]))
  records.append({"form_id":form["id"],"method":form["method"],
    "action_same_host":u.hostname==url.hostname,
    "action_path_depth":len([p for p in u.path.split("/") if p]),
    "action_has_query":bool(u.query),
    "field_names":sorted(set(form["names"])),
    "field_types":sorted(set(form["types"])),
    "button_has_download_label":any(re.search(r"download|free",b["text"],re.I)
                                 for b in form["buttons"])})
 page=source["html"]
 print(json.dumps({"provider":"mp4hentai","stage":"EXACT_SELECTED_FRDL_FREE_FORM_CONTRACT",
   "first_party_file_from_clicked_mirror":True,"terminal_host":url.hostname,
   "forms":records,"visible_captcha_widgets":doc.gates[:12],
   "captcha_code_in_page":bool(re.search(
      r"g-recaptcha|h-captcha|cf-turnstile|file-captcha|hcaptcha.render",page,re.I)),
   "complete_original_verified":False}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"mp4hentai","status":"EXACT_FRDL_FORM_UNVERIFIED",
    "error_type":type(e).__name__}),flush=True)
  sys.exit(3)
