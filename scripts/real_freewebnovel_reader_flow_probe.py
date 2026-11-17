#!/usr/bin/env python3
"""Inspect publisher's observed chapter navigation and reader structure.
No book text or account data is retained, no guessed chapter identifiers.
"""
from html.parser import HTMLParser
from urllib.parse import urljoin,urlsplit
from urllib.request import Request,urlopen
import json,re
HOME="https://freewebnovel.com/novel/dual-cultivation-novel"
PAGES=[HOME,HOME+"/chapter-1",HOME+"/chapter-1147"]
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"

class Page(HTMLParser):
 def __init__(self):
  super().__init__();self.anchors=[];self.headings=[];self.inside=None;self.textbuf=""
  self.ids={};self.classes={};self.reader_blocks=0;self.p_count=0
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if d.get("id"):self.ids[d["id"]]=self.ids.get(d["id"],0)+1
  if d.get("class"):
   for c in d["class"].split():self.classes[c]=self.classes.get(c,0)+1
  if tag=="p":self.p_count+=1
  if tag=="a" and d.get("href"):
   self.inside={"attrs":d,"text":""};self.anchors.append(self.inside)
  if tag in ("h1","h2","h3","h4","h5","h6"):self.headings.append({"tag":tag,"text":""})
 def handle_data(self,s):
  if self.inside and len(self.inside["text"])<130:self.inside["text"]+=s[:130]
  if self.headings and len(self.headings[-1]["text"])<180:self.headings[-1]["text"]+=s[:180]
 def handle_endtag(self,tag):
  if tag=="a":self.inside=None

def inspect(url):
 req=Request(url,headers={"User-Agent":UA,"Accept-Encoding":"identity"})
 try:
  with urlopen(req,timeout=25) as res:
   status=res.status;url=res.url;body=res.read(1500000).decode("utf-8","replace")
 except Exception as e:
  return {"url":url,"error":type(e).__name__+": "+str(e)[:110]}
 parser=Page();parser.feed(body)
 normalized=urlsplit(url)
 series=normalized.path.rsplit("/chapter-",1)[0]
 def classify(a):
  d=a["attrs"];dest=urljoin(url,d["href"]);p=urlsplit(dest)
  return {"path":p.path[-110:],"query":p.query[:45],
   "id":d.get("id",""),"class":d.get("class","")[:80],
   "rel":d.get("rel",""),"text":a["text"].strip()[:65],
   "chapter":bool(re.search(r"chapter[-_/]\d+",p.path,re.I)),
   "in_series":p.hostname==normalized.hostname and p.path.startswith(series+"/")}
 highlights=[classify(a) for a in parser.anchors if
  re.search(r"\bnext\b|\bprevious\b|\bprev\b|\bchapter\b",a["text"],re.I)
  or re.search(r"next|prev",str(a["attrs"].get("id",""))+" "+str(a["attrs"].get("class","")),re.I)]
 links=[classify(a) for a in parser.anchors if re.search(r"/chapter-\d+",a["attrs"]["href"],re.I)]
 return {"url":url,"http":status,"html_length":len(body),"has_challenge":bool(re.search(
   "just a moment|verify you are human",body[:5000],re.I)),
   "p_count":parser.p_count,"headings":parser.headings[:10],
   "relevant_ids":{k:v for k,v in parser.ids.items() if
     re.search(r"article|chapter|reader|next|prev|page",k,re.I)},
   "relevant_classes":{k:v for k,v in parser.classes.items() if
     re.search(r"article|chapter|reader|next|prev|page",k,re.I)},
   "chapter_links":len(links),"sample_chapters":links[:6],
   "next_prev_candidates":highlights[-18:]}

for page in PAGES:
 print(json.dumps(inspect(page),ensure_ascii=False),flush=True)
