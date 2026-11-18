#!/usr/bin/env python3
"""Submit only the selected frdl.hk publisher's public FREE download form.

The official MP4Hentai article -> selected Mirrored.to click -> Freedl ->
frdl.io -> frdl.hk chain supplies the file and server-issued form values.
No CAPTCHA, paid-tier, anti-bot challenge or random IDs are solved or forged.
Only exact hidden normal fields (incl. download_free) are submitted to the
same-host original path with server-issued cookies; never follow new hosts
or claim HTML as downloaded media. No file/token/query values are printed.
"""
import html,json,re,sys,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from inspect_mp4hentai_selected_freedl_redirect import main as selected,NoRedirect,UA
class Forms(HTMLParser):
 def __init__(self):
  super().__init__();self.forms=[];self.active=None;self.button=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="form":
   self.active={"method":a.get("method","get").upper(),
     "action":a.get("action",""),"id":a.get("id",""),
     "inputs":[],"buttons":[]}
   self.forms.append(self.active)
  elif tag=="input" and self.active:
   self.active["inputs"].append(a)
  elif tag=="button" and self.active:
   self.button={"type":a.get("type",""),"text":""}
   self.active["buttons"].append(self.button)
 def handle_data(self,data):
  if self.button and len(self.button["text"])<120:self.button["text"]+=data
 def handle_endtag(self,tag):
  if tag=="form":self.active=None
  if tag=="button":self.button=None
def main():
 selected_file=selected(return_selected_terminal=True)
 if not isinstance(selected_file,dict) or selected_file["http"]!=200 or (
    selected_file["mime"]!="text/html"):
  raise RuntimeError("Selected official chain did not yield a public page")
 base=selected_file["selected_url"]
 p=urllib.parse.urlsplit(base)
 if p.scheme!="https" or p.hostname not in ("frdl.hk","www.frdl.hk"):
  raise RuntimeError("Unverified selected file terminal")
 doc=Forms();doc.feed(selected_file["html"])
 accepted=[]
 for form in doc.forms:
  if form["id"]!="DownloadForm" or form["method"]!="POST":continue
  action=urllib.parse.urlsplit(urllib.parse.urljoin(base,form["action"]))
  print(json.dumps({"provider":"mp4hentai",
   "stage":"EXACT_FRDL_FREE_FORM_ACTION_SCOPE",
   "same_hostname":action.hostname==p.hostname,
   "same_path":action.path==p.path,"has_query":bool(action.query),
   "complete_original_verified":False}),flush=True)
  if (action.scheme!="https" or action.hostname!=p.hostname or
      action.path!=p.path or action.query or action.fragment or action.username or
      action.password):
   continue
  fields={i.get("name"):i.get("value","") for i in form["inputs"]
          if i.get("name") and i.get("type","hidden").lower()=="hidden"}
  original_names=[i.get("name") for i in form["inputs"] if i.get("name")]
  wanted={"op","id","hash","rand","referer","download_free",
          "adblock_detected","hls_direct"}
  print(json.dumps({"provider":"mp4hentai",
   "stage":"EXACT_FRDL_FREE_FORM_FIELD_SCOPE",
   "field_names_match":set(original_names)==wanted,
   "hidden_fields_match":set(fields)==wanted,
   "id_nonempty":bool(fields.get("id")),
   "hash_nonempty":bool(fields.get("hash")),
   "has_download_button":any(re.search("download|free",x["text"],re.I)
      for x in form["buttons"]),
   "captcha_field":any("captcha" in str(x).lower() for x in original_names),
   "complete_original_verified":False}),flush=True)
  if (set(original_names)!=wanted or set(fields)!=wanted or
      any(len(value)>3000 for value in fields.values()) or
      not fields["id"] or not fields["hash"] or
      "captcha" in "".join(original_names).lower() or
      not any(re.search("download|free",b["text"],re.I)
              for b in form["buttons"])):
   continue
  accepted.append((action.geturl(),fields))
 if len(accepted)!=1:
  print(json.dumps({"provider":"mp4hentai",
    "stage":"EXACT_FRDL_PUBLIC_FORM_REJECTED",
    "matching_free_forms":len(accepted),
    "required_captcha_or_different_form":True,
    "complete_original_verified":False}),flush=True)
  return 3
 action,fields=accepted[0]
 data=urllib.parse.urlencode(fields).encode("utf8")
 req=urllib.request.Request(action,data=data,method="POST",headers={
  "User-Agent":UA,"Referer":base,"Origin":p.scheme+"://"+p.hostname,
  "Content-Type":"application/x-www-form-urlencoded",
  "Accept-Encoding":"identity"})
 opener=urllib.request.build_opener(NoRedirect,
  urllib.request.HTTPCookieProcessor(selected_file["cookies"]))
 try:
  with opener.open(req,timeout=25) as response:
   code=response.status;headers=response.headers;body=response.read(200000)
 except urllib.error.HTTPError as ex:
  code=ex.code;headers=ex.headers;body=ex.read(200000)
 mime=headers.get("Content-Type","").split(";")[0].lower()
 location=urllib.parse.urlsplit(urllib.parse.urljoin(action,
    headers.get("Location",""))) if headers.get("Location") else None
 content=body.decode("utf8","replace") if "html" in mime else ""
 print(json.dumps({"provider":"mp4hentai",
  "stage":"EXACT_SELECTED_PUBLIC_FREE_DOWNLOAD_POST",
  "source_from_official_selected_mirror":True,
  "public_free_form_only":True,
  "http":code,"mime":mime,
  "has_content_disposition":bool(headers.get("Content-Disposition")),
  "response_sample_bytes":len(body),
  "video_mp4_magic":body[4:8]==b"ftyp",
  "hls_magic":body.lstrip().startswith(b"#EXTM3U"),
  "redirect_host":location.hostname if location else None,
  "redirect_ext":location.path.rsplit(".",1)[-1][-8:] if location and "." in location.path else None,
  "captcha_after_free_form":bool(re.search(
      r"g-recaptcha|h-captcha|cf-turnstile|file-captcha|hcaptcha",content,re.I)),
  "complete_original_verified":False}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"mp4hentai",
    "status":"SELECTED_PUBLIC_FREE_FORM_NOT_VERIFIED",
    "error_type":type(e).__name__}),flush=True)
  sys.exit(4)
