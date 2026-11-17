#!/usr/bin/env python3
"""Read only the publisher-attested MP4Hentai mirror page's publicly shown
download-link controls. Return host/route shapes, never private tokens or URLs.
"""
import json,urllib.request,urllib.parse,re,html
from html.parser import HTMLParser
PAGE="https://www.mirrored.to/files/DF7298C6/"
ARTICLE="https://mp4hentai.com/2026/11/nagi-kannagi-18min50s-v/"
class P(HTMLParser):
 def __init__(self):super().__init__();self.links=[];self.forms=[];self.current=None;self.scripts=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a":
   self.current={"href":a.get("href",""),"class":a.get("class",""),"id":a.get("id",""),"text":"","onclick":a.get("onclick","")}
   self.links.append(self.current)
  if tag=="form":self.forms.append({"method":a.get("method",""),"action":a.get("action",""),"id":a.get("id","")})
  if tag=="script" and a.get("src"):self.scripts.append(a["src"])
 def handle_data(self,text):
  if self.current:self.current["text"]+=text[:80]
 def handle_endtag(self,tag):
  if tag=="a":self.current=None
def shape(raw):
 u=urllib.parse.urlsplit(urllib.parse.urljoin(PAGE,html.unescape(raw)))
 return {"host":u.hostname,"route":"/"+"/".join(
   x if x in {"files","links","download","go","out","d","dl","get","redirect","fetch"} else "{id}"
   for x in u.path.split("/") if x)[:130],"has_query":bool(u.query)}
r=urllib.request.Request(PAGE,headers={"Referer":ARTICLE,"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept-Encoding":"identity"})
with urllib.request.urlopen(r,timeout=25) as response:
 doc=response.read(2000000).decode("utf-8","replace")
 p=P();p.feed(doc)
 selected=[]
 for a in p.links:
  title=(a["text"]+" "+a["id"]+" "+a["class"]).lower()
  if re.search(r"click to view|download|hosting links|get links|dl",title):
   selected.append({"text":a["text"].strip()[:55],"id":a["id"][:50],
      "class":a["class"][:75],"target":shape(a["href"]),
      "onclick_present":bool(a["onclick"])})
 print(json.dumps({"status":response.status,"html_bytes":len(doc),
  "links":len(p.links),"link_controls":selected[:22],
  "forms":[{"id":x["id"],"method":x["method"],"action":shape(x["action"])}
   for x in p.forms[:7]],
  "scripts_hosts":sorted({urllib.parse.urlsplit(
   urllib.parse.urljoin(PAGE,x)).hostname for x in p.scripts}),
  "ajax_likely":len(re.findall(r"fetch\s*\(|XMLHttpRequest|/api/|dl=0|hosting links",doc,re.I))
  }),flush=True)
 selected_links=[a for a in p.links if a["id"]=="download-link" and
   "click to view the download links" in a["text"].lower()]
 if len(selected_links)==1:
  dest=urllib.parse.urlsplit(urllib.parse.urljoin(PAGE,
      html.unescape(selected_links[0]["href"])))
  origin=urllib.parse.urlsplit(PAGE)
  if (dest.scheme=="https" and dest.hostname in
      ("mirrored.to","www.mirrored.to") and
      dest.path==origin.path and
      urllib.parse.parse_qs(dest.query).get("dl")==["0"]):
   try:
    extra=urllib.request.Request(dest.geturl(),headers={
       "Referer":PAGE,"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
       "Accept-Encoding":"identity"})
    with urllib.request.urlopen(extra,timeout=25) as res:
     after=res.read(1_600_000).decode("utf-8","replace")
     parser=P();parser.feed(after)
     hosts=[]
     for a in parser.links:
      u=urllib.parse.urlsplit(urllib.parse.urljoin(dest.geturl(),a["href"]))
      if (u.scheme=="https" and u.hostname and
          u.hostname not in ("mirrored.to","www.mirrored.to") and
          not re.search("ads|analytics|doubleclick",u.hostname)):
       hosts.append(u.hostname)
     print(json.dumps({"stage":"PUBLISHER_SELECTED_DL0",
        "http":res.status,"html_bytes":len(after),
        "host_candidates":sorted(set(hosts))[:15],
        "placeholder":bool(re.search("fetching hosting links",after,re.I)),
        "anchors":len(parser.links)}),flush=True)
     if re.search("fetching hosting links",after,re.I):
      import subprocess,tempfile
      EDGE=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
      with tempfile.TemporaryDirectory(prefix="asterion_mirror_") as temp:
       try:
        result=subprocess.run([EDGE,"--headless=new","--disable-gpu",
          "--no-first-run","--no-default-browser-check","--disable-extensions",
          "--mute-audio","--user-data-dir="+temp,
          "--virtual-time-budget=18000","--dump-dom",dest.geturl()],
          capture_output=True,timeout=32)
        rendered=result.stdout.decode("utf-8","replace")
        front=P();front.feed(rendered)
        names=set()
        for a in front.links:
         u=urllib.parse.urlsplit(urllib.parse.urljoin(dest.geturl(),a["href"]))
         if (u.scheme=="https" and u.hostname not in
             ("mirrored.to","www.mirrored.to") and u.hostname):
          names.add(u.hostname)
        interesting=[]
        for a in front.links:
         label=a["text"].strip()
         if re.search(r"download|mirror|upfile|file|host|click|direct|go to",label,re.I):
          interesting.append({"label":label[:55],"id":a["id"][:45],
             "class":a["class"][:65],"route":shape(a["href"]),
             "onclick":bool(a["onclick"])})
        print(json.dumps({"stage":"PUBLISHER_SELECTED_JS_LINKS",
          "browser_exit":result.returncode,
          "rendered_bytes":len(rendered),"hosts":sorted(names)[:16],
          "placeholder":bool(re.search("fetching hosting links",rendered,re.I)),
          "anchors":len(front.links),
          "download_controls":interesting[:18]}),flush=True)
       except Exception as error:
        print(json.dumps({"stage":"PUBLISHER_SELECTED_JS_LINKS",
          "error_class":type(error).__name__}),flush=True)

   except Exception as e:
    print(json.dumps({"stage":"PUBLISHER_SELECTED_DL0",
      "error_class":type(e).__name__}),flush=True)

