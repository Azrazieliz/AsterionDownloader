#!/usr/bin/env python3
"""Real exact JAVIDEO film: try only site-published free DSTR or LLS player."""
import argparse,json,re,sys,time,urllib.parse
from pathlib import Path
from selenium.webdriver.common.by import By
from pc_full_selected_provider_acceptance_20261122 import driver,safe_url,host,full_file,FIXED
arg=argparse.ArgumentParser()
arg.add_argument("server",choices=["DSTR#01","LLS#01"])
server=arg.parse_args().server
selected=FIXED["javideo"]
result={"provider":"javideo","selected_article":selected,"server":server,
        "complete_selected_original":False}
d=driver()
try:
 d.set_page_load_timeout(23)
 try:d.get(selected)
 except Exception:pass
 if urllib.parse.urlsplit(d.current_url).path!="/en/fc2ppv-4994603":
  raise RuntimeError("NOT_THE_SELECTED_JAVIDEO_MOVIE")
 try:
  d.execute_script("""let b=document.querySelector('#clickfakeplayer');
    if(b&&!b.closest('aside,.related,.recommended,.ads,.advertisement,[data-ad]'))b.click();""")
 except Exception:pass
 button=None
 for _ in range(32):
  values=d.find_elements(By.CSS_SELECTOR,"button")
  choices=[v for v in values if v.text.strip()==server and v.is_displayed() and v.is_enabled()]
  if len(choices)==1:button=choices[0];break
  time.sleep(.6)
 if button is None:raise RuntimeError("NO_SELECTED_ALTERNATE_SERVER_BUTTON")
 button.click()
 frame=None
 for _ in range(24):
  if urllib.parse.urlsplit(d.current_url).path!="/en/fc2ppv-4994603":
   raise RuntimeError("SERVER_REDIRECTED_OUT_OF_SELECTED_ARTICLE")
  fields=d.find_elements(By.CSS_SELECTOR,".embed-responsive.player:not(.d-none) iframe[src]")
  urls=list(dict.fromkeys(v.get_attribute("src") for v in fields
        if safe_url(v.get_attribute("src") or "")))
  if len(urls)==1:frame=urls[0];break
  time.sleep(.7)
 if not frame:raise RuntimeError("PUBLISHER_SELECTED_SERVER_NO_UNIQUE_FRAME")
 parsed=urllib.parse.urlsplit(frame)
 if parsed.fragment or parsed.path in ("","/") or parsed.username or parsed.password:
  raise RuntimeError("INVALID_PUBLISHER_SELECTED_FRAME")
 result["player_host"]=host(frame)
 source,referer=frame,selected
 try:
  nodes=d.find_elements(By.CSS_SELECTOR,".embed-responsive.player:not(.d-none) iframe[src]")
  if len(nodes)==1 and nodes[0].get_attribute("src")==frame:
   d.switch_to.frame(nodes[0])
   for _ in range(17):
    v=d.execute_script("""return [...document.querySelectorAll('video')]
      .filter(e=>!e.closest('aside,.related,.recommended,.ads,[data-ad]'))
      .map(e=>({url:e.currentSrc||e.src||'',duration:Number.isFinite(e.duration)?e.duration:0}));""") or []
    direct=[x["url"] for x in v if float(x.get("duration") or 0)>=600 and
      safe_url(x.get("url") or "") and
      re.search(r"(?i)\.(?:m3u8|mp4)$",urllib.parse.urlsplit(x["url"]).path)]
    if len(set(direct))==1:
     source,referer=direct[0],frame
     result["long_media_from_selected_frame"]=True
     break
    time.sleep(.65)
 except Exception:pass
 finally:d.switch_to.default_content()
 result["selected_source_host"]=host(source)
 result.update(full_file(selected,{"source":source,"referer":referer,"duration":0}))
 result["source_attribution"]="publisher selected "+server
except Exception as exc:
 result["reason"]=re.sub(r"https?://\S+","[REDACTED]",str(exc))[:350]
finally:
 d.quit()
 Path("pc-javideo-alternate-"+server.split("#")[0]+".json").write_text(
   json.dumps(result,indent=2,sort_keys=True))
 print(json.dumps(result,sort_keys=True),flush=True)
if not result["complete_selected_original"]:sys.exit(2)
