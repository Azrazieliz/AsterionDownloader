#!/usr/bin/env python3
"""Metadata-only test of an exact selected OnScreens player using APK-pinned yt-dlp.
No video bytes or unrelated embeds downloaded, no auth bypass. Outputs no URLs.
"""
import sys,json,os,re,urllib.parse,urllib.request
from html.parser import HTMLParser
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/
    "app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"))
from yt_dlp import YoutubeDL
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Links(HTMLParser):
 def __init__(self):super().__init__();self.list=[];self.active=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):
   self.active={"href":a["href"],"label":""};self.list.append(self.active)
 def handle_data(self,text):
  if self.active:self.active["label"]+=text[:150]
 def handle_endtag(self,tag):
  if tag=="a":self.active=None
def fetch(address):
 request=urllib.request.Request(address,headers={"User-Agent":UA,"Accept-Encoding":"identity"})
 with urllib.request.urlopen(request,timeout=17) as reply:
  return reply.url,reply.read(2_000_000).decode("utf8","replace")
def main():
 base,html=fetch("https://www.onscreens.me/")
 first=Links();first.feed(html)
 recordings=[]
 for item in first.list:
  href=urllib.parse.urljoin(base,item["href"]);u=urllib.parse.urlsplit(href)
  if u.scheme=="https" and u.hostname in ("onscreens.me","www.onscreens.me") and re.fullmatch(r"/[a-f0-9-]{36}/[a-z0-9-]{9,255}/?",u.path,re.I):
   if href not in recordings:recordings.append(href)
 if not recordings:print(json.dumps({"stage":"listing","error":"NO_RECORDING"}));return 1
 article,html=fetch(recordings[0]);doc=Links();doc.feed(html)
 players=[]
 for item in doc.list:
  if item["label"].strip().lower()!="open the video player":continue
  u=urllib.parse.urlsplit(urllib.parse.urljoin(article,item["href"]))
  if u.scheme=="https" and u.hostname in ("voe.sx","doodstream.com","dood.to","dood.watch") and re.fullmatch(r"/(?:e|d)/[a-z0-9_-]{5,100}/?",u.path,re.I):
   players.append(u.geturl())
 players=list(dict.fromkeys(players))
 if len(players)!=1:print(json.dumps({"stage":"selected_player","count":len(players),"error":"NOT_ONE_PLAYER"}));return 2
 print(json.dumps({"stage":"selected_player","publisher":"onscreens.me","player_host":urllib.parse.urlsplit(players[0]).hostname,"player_verified":True}),flush=True)
 class Log:
  def warning(self,msg):pass
  def debug(self,msg):pass
  def error(self,msg):pass
 opts={"skip_download":True,"no_warnings":True,"quiet":True,"logger":Log(),
       "socket_timeout":14,"retries":1,"extractor_retries":1,"noplaylist":True}
 try:
  with YoutubeDL(opts) as engine:
   meta=engine.extract_info(players[0],download=False)
  if not meta:raise ValueError("EMPTY_METADATA")
  print(json.dumps({"stage":"selected_extractor","status":"metadata",
      "extractor":meta.get("extractor_key"),
      "duration_s":meta.get("duration"),
      "formats":len(meta.get("formats") or []),
      "full_original_bytes":False}),flush=True)
  return 0 if meta.get("formats") else 3
 except Exception as e:
  print(json.dumps({"stage":"selected_extractor","error":e.__class__.__name__,
   "reason":str(e)[:160],"full_original_bytes":False}),flush=True)
  return 4
if __name__=="__main__":sys.exit(main())
