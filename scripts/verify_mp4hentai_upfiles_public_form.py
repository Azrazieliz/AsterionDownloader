#!/usr/bin/env python3
"""Selected MP4Hentai -> public UpFiles free-download form real HTTP proof.

Only POSTs the publisher's user-visible public FREE download form with its
server-issued CSRF token, same host/cookies/referrer; does not bypass a
login, CAPTCHA, paid tier, queue/wait timer or forge tokens. Inspects only
MIME/first 32 bytes and published file size; no complete-file claim.
Never prints ephemeral CSRF values or signed URLs.
"""
import http.cookiejar,html,json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
ARTICLE="https://mp4hentai.com/2026/11/nagi-kannagi-18min50s-v/"
LINK="https://upfiles.com/nGfW"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Forms(HTMLParser):
 def __init__(self):super().__init__();self.current=None;self.forms=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="form":
   self.current={"id":a.get("id",""),"method":a.get("method","GET"),
    "action":a.get("action",""),"fields":{}}
   self.forms.append(self.current)
  if tag=="input" and self.current is not None:
   name=a.get("name","")
   if name and a.get("type","hidden")=="hidden":
    self.current["fields"][name]=a.get("value","")
 def handle_endtag(self,tag):
  if tag=="form":self.current=None
def request(url,ref="",data=None):
 h={"User-Agent":UA,"Accept-Encoding":"identity","Accept":"*/*"}
 if ref:h["Referer"]=ref
 if data is not None:
  h["Content-Type"]="application/x-www-form-urlencoded"
  o=urllib.parse.urlsplit(url)
  h["Origin"]=o.scheme+"://"+o.netloc
 return urllib.request.Request(url,headers=h,data=data)
def main():
 with urllib.request.urlopen(request(ARTICLE),timeout=23) as source:
  article=source.read(2000000).decode("utf-8","replace")
 assert ("upfiles.com/nGfW" in article or
  "upfiles.com\/nGfW" in article or
  "https://upfiles.com/nGfW" in html.unescape(article)),(
  "This file host is not tied to exact selected publisher article")
 jar=http.cookiejar.CookieJar()
 session=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
 with session.open(request(LINK,ARTICLE),timeout=23) as response:
  assert response.status==200
  page=response.read(400000).decode("utf8","replace")
  final=response.url
 u=urllib.parse.urlsplit(final)
 assert u.scheme=="https" and u.hostname in ("upfilesgo.com","www.upfilesgo.com")
 scan=Forms();scan.feed(page)
 candidates=[form for form in scan.forms if form["id"]=="free-download-form"]
 assert len(candidates)==1,("not exact public free form",len(candidates))
 form=candidates[0]
 assert form["method"].lower()=="post" and set(form["fields"])=={
  "_token","download_type"}
 assert form["fields"]["_token"] and form["fields"]["download_type"].lower()=="free"
 target=urllib.parse.urljoin(final,form["action"])
 dest=urllib.parse.urlsplit(target)
 assert dest.scheme=="https" and dest.hostname==u.hostname and dest.path=="/nGfW"
 print(json.dumps({"publisher":"mp4hentai","stage":"SELECTED_PUBLIC_FREE_FORM",
  "server":"upfilesgo.com","form":"free-download-form",
  "csrf_from_same_host":True,"cookie_count":len(jar),
  "form_keys":sorted(form["fields"])}),flush=True)
 data=urllib.parse.urlencode(form["fields"]).encode("utf-8")
 try:
  with session.open(request(target,final,data),timeout=27) as response:
   typ=response.headers.get("Content-Type","").split(";")[0]
   sample=response.read(48)
   media=(typ.lower() in ("video/mp4","application/octet-stream") and
    sample[4:8]==b"ftyp")
   print(json.dumps({"publisher":"mp4hentai","stage":"PUBLIC_FREE_FORM_RESPONSE",
    "status":response.status,"host":urllib.parse.urlsplit(response.url).hostname,
    "mime":typ,"declared":response.headers.get("Content-Length",""),
    "sample_bytes":len(sample),"ftyp_mp4":sample[4:8]==b"ftyp",
    "actual_media":bool(media)}),flush=True)
   return 0 if media else 2
 except urllib.error.HTTPError as exc:
  print(json.dumps({"publisher":"mp4hentai","stage":"PUBLIC_FREE_FORM_DENIED",
    "status":exc.code,"host":urllib.parse.urlsplit(exc.url).hostname}),flush=True)
  return 3
if __name__=="__main__":
 try:raise SystemExit(main())
 except Exception as exc:
  print(json.dumps({"provider":"mp4hentai","status":"FAILED_NOT_COMPLETE",
    "type":type(exc).__name__,"reason":str(exc)[:200]}),flush=True)
  raise SystemExit(4)
