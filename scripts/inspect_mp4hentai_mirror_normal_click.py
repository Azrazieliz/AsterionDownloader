#!/usr/bin/env python3
"""Normal first-party click of exact owner-selected MP4Hentai Mirrored.to link.

Uses selected publisher article's official Mirror host/ID and existing Edge.
Clicks only Mirrored.to's own "Click to view download links" control once,
waits for ordinary site JavaScript, then reports non-ad, publisher-selected
actual file-host link shapes. No CAPTCHA solving, token/URL export, media save
or unrelated listing/advertisement extraction.
"""
import json,os,re,socket,subprocess,sys,tempfile,time,urllib.parse,urllib.request,urllib.error,shutil
from pathlib import Path
from html.parser import HTMLParser
from cdp_stdlib_socket import connect
class PublicLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links=[]
        self.active=None
    def handle_starttag(self,tag,attributes):
        a=dict(attributes)
        if tag=="a" and a.get("href"):
            self.active={"href":a["href"],"text":"","class":a.get("class","")}
            self.links.append(self.active)
    def handle_data(self,s):
        if self.active and len(self.active["text"])<180:
            self.active["text"]+=s[:180]
    def handle_endtag(self,tag):
        if tag=="a":self.active=None
EDGE=Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
BASE="https://www.mirrored.to/files/DF7298C6/"
ALLOWED={"www.mirrored.to","mirrored.to"}
def freeport():
    with socket.socket() as x:x.bind(("127.0.0.1",0));return x.getsockname()[1]
def tabs(port):
    with urllib.request.urlopen("http://127.0.0.1:"+str(port)+"/json",timeout=3) as r:
        return json.load(r)
def eval(ws,i,js):
    ws.send(json.dumps({"id":i,"method":"Runtime.evaluate",
            "params":{"expression":js,"returnByValue":True,"awaitPromise":True}}))
    for _ in range(80):
        resp=json.loads(ws.recv(timeout=6))
        if resp.get("id")!=i:continue
        if "exceptionDetails" in resp.get("result",{}):
            return {"eval_error":True}
        return resp.get("result",{}).get("result",{}).get("value")
    raise RuntimeError("ordinary selected mirror browser timed out")
def main(return_selected_buzzheavier=False,return_selected_rapidgator=False):
    if not EDGE.is_file():raise RuntimeError("existing Windows Edge missing")
    folder=tempfile.mkdtemp(prefix="asterion_owner_mp4hentai_mirror_")
    port=freeport()
    proc=subprocess.Popen([str(EDGE),"--headless=new","--disable-gpu",
       "--no-first-run","--no-default-browser-check","--disable-extensions",
       "--disable-background-networking","--mute-audio",
       "--remote-debugging-port="+str(port),"--remote-allow-origins=*",
       "--user-data-dir="+folder,BASE],
       stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        target=None
        for _ in range(75):
            try:
                target=next((x for x in tabs(port) if x.get("type")=="page" and
                    "mirrored.to" in x.get("url","")),None)
                if target:break
            except Exception:pass
            if proc.poll() is not None:break
            time.sleep(.25)
        if not target:raise RuntimeError("selected Mirrored.to page did not open")
        with connect(target["webSocketDebuggerUrl"],open_timeout=8,
            close_timeout=2,max_size=2_000_000) as ws:
            state={}
            for i in range(40):
                state=eval(ws,20+i,r"""(() => {
                    const link=document.querySelector('a#download-link');
                    return {ready:document.readyState,host:location.hostname,
                      path:location.pathname,has_control:!!link,
                      link_href:link?.href||null,
                      controls:[...document.querySelectorAll('a[href]')]
                       .filter(x=>/download|hosting links|file|upfiles|mirror/i
                         .test(x.textContent||'')).length};
                })()""") or {}
                if state.get("has_control"):break
                time.sleep(.3)
            print(json.dumps({"provider":"mp4hentai",
                "stage":"EXACT_OWNER_MIRROR_NORMAL_BROWSER",
                "actual_host":state.get("host"),
                "has_download_control":state.get("has_control"),
                "visible_download_controls":state.get("controls",0)}),flush=True)
            href=state.get("link_href")
            if state.get("host") not in ALLOWED or not href:
                return 3
            url=urllib.parse.urlsplit(href)
            if (url.scheme!="https" or url.hostname not in ALLOWED or
                url.path!="/files/DF7298C6/" or
                urllib.parse.parse_qs(url.query).get("dl")!=["0"]):
                raise RuntimeError("selected Mirrored control links outside official file")
            clicked=eval(ws,100,r"""(() => {
                const link=document.querySelector('a#download-link');
                if(!link)return false;
                if(link.dataset.asterionClicked)return false;
                link.dataset.asterionClicked='1';
                link.click();return true;
            })()""")
            if clicked is not True:raise RuntimeError("selected host button not clicked")
            result={}
            for iteration in range(48):
                result=eval(ws,200+iteration,r"""(() => ({
                  host:location.hostname,path:location.pathname,
                  placeholder:/fetching hosting links/i
                    .test(document.body?.innerText||''),
                  gates:!!document.querySelector(
                    '.cf-turnstile,.g-recaptcha,.h-captcha,form#file-captcha'),
                  anchors:[...document.querySelectorAll('a[href]')]
                    .filter(x=>/download|get link|upfiles|mirror|file host|direct/i
                      .test((x.textContent||'')+' '+(x.title||'')))
                    .filter(x=>!x.closest(
                      'aside,.ads,.related,.recommendations,.sidebar,[data-ad]'))
                    .slice(0,38).map(x=>({
                      text:(x.textContent||'').trim().slice(0,70),
                      host:new URL(x.href).hostname,
                      path:new URL(x.href).pathname,
                      query:new URL(x.href).search
                    }))
                }))()""") or {}
                if result.get("host") not in ALLOWED:break
                meaningful=[x for x in result.get("anchors",[]) if
                    x.get("host") not in ALLOWED and x.get("host")]
                if meaningful and not result.get("placeholder"):break
                time.sleep(.5)
            links=result.get("anchors") or []
            shapes=[]
            for x in links:
                host=x.get("host","")
                if not host or re.search("ads|analytics|doubleclick",host,re.I):
                    continue
                raw_path=x.get("path") or ""
                shapes.append({"title":x.get("text","")[:45],
                  "host":host,"first_route":raw_path.strip("/").split("/")[0][:30],
                  "query_keys":sorted(urllib.parse.parse_qs(
                    x.get("query") or "").keys())[:7],
                  "is_selected_mirror":host in ALLOWED})
            print(json.dumps({"provider":"mp4hentai",
                "stage":"EXACT_CLICKED_OWNER_MIRROR_HOSTERS",
                "browser_host":result.get("host"),
                "same_selected_file":result.get("path")=="/files/DF7298C6/",
                "placeholder":result.get("placeholder"),
                "challenge_detected":result.get("gates"),
                "published_link_count":len(shapes),
                "link_shapes":shapes[:25],
                "complete_original_verified":False}),flush=True)
            # Clicked owner file #DF7298C6 now publishes four real
            # same-site /getlink download buttons. Inspect each legitimate
            # server redirect without following unknown hosts or treating
            # another file/advertisement as selected media.
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self,req,fp,code,msg,headers,url):
                    return None
            opener=urllib.request.build_opener(NoRedirect)
            redirects=[]
            selected_buzzheavier=[]
            selected_rapidgator=[]
            published_shapes=[]
            for link in links:
                raw_path=link.get("path") or ""
                if not raw_path.startswith("/getlink"):continue
                raw_query=(link.get("query") or "").lstrip("?")
                published_shapes.append({"first_segment":raw_path.strip("/").split("/")[0][:45],
                    "route_segment_count":len([x for x in raw_path.split("/") if x]),
                    "query_keys":sorted(urllib.parse.parse_qs(raw_query).keys())})
            print(json.dumps({"provider":"mp4hentai",
                "stage":"SELECTED_GETLINK_ROUTE_SHAPES",
                "controls":len(published_shapes),
                "shapes":published_shapes[:8]}),flush=True)
            for link in links:
                u=urllib.parse.urlsplit("https://www.mirrored.to"+
                    (link.get("path") or "")+(link.get("query") or ""))
                keys=urllib.parse.parse_qs(u.query.lstrip("?"))
                if(u.scheme!="https" or u.hostname not in ALLOWED or
                   not u.path.startswith("/getlink") or set(keys)!={"hid","tid"} or
                   any(len(values)!=1 for values in keys.values())):
                    continue
                req=urllib.request.Request(u.geturl(),headers={
                    "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) "+
                        "AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
                    "Referer":"https://www.mirrored.to/files/DF7298C6/?dl=0",
                    "Accept-Encoding":"identity"})
                try:
                    with opener.open(req,timeout=20) as response:
                        body=response.read(600000).decode("utf8","replace")
                        dom=PublicLinks()
                        dom.feed(body)
                        actual=[]
                        for candidate in dom.links:
                            text_label=(candidate["text"]+" "+candidate["class"]).lower()
                            if not re.search(
                                r"download|get file|link|continue|file host",text_label):
                                continue
                            dest=urllib.parse.urlsplit(urllib.parse.urljoin(
                                u.geturl(),candidate["href"]))
                            if dest.scheme!="https" or not dest.hostname:continue
                            if re.search(r"ads|doubleclick|analytics",dest.hostname):
                                continue
                            actual.append({"host":dest.hostname,
                                "first_route":dest.path.strip("/").split("/")[0][:35],
                                "query_keys":sorted(urllib.parse.parse_qs(
                                    dest.query).keys())[:8],
                                "direct_media":dest.path.lower().endswith(
                                    (".mp4",".webm",".mkv"))})
                            # Reuse ONLY the real user-selected file hoster
                            # listed by the four clicked Mirror download
                            # controls. Do not discover or guess adjacent
                            # BuzzHeavier file identifiers.
                            if(dest.hostname in ("buzzheavier.com",
                                    "www.buzzheavier.com") and
                               dest.path and len(dest.path.strip("/").split("/"))==1):
                                selected_buzzheavier.append(dest.geturl())
                            if(dest.hostname in ("rapidgator.net",
                                    "www.rapidgator.net") and
                               dest.path.startswith("/file/") and
                               len(dest.path)<240 and
                               not dest.username and not dest.password):
                                selected_rapidgator.append(dest.geturl())
                        redirects.append({"http":response.status,
                            "mime":response.headers.get("Content-Type","").split(";")[0],
                            "html_bytes":len(body),
                            "selected_labeled_link_shapes":actual[:8],
                            "captcha_present":bool(re.search(
                                r"cf-turnstile|g-recaptcha|h-captcha|file-captcha",body,re.I)),
                            "direct_file":False})
                except urllib.error.HTTPError as error:
                    location=error.headers.get("Location") or ""
                    redirected=urllib.parse.urlsplit(
                        urllib.parse.urljoin(u.geturl(),location))
                    redirects.append({"http":error.code,
                        "redirect_host":redirected.hostname,
                        "redirect_first_route":
                            redirected.path.strip("/").split("/")[0][:30],
                        "direct_media_extension":
                            redirected.path.lower().endswith(
                                (".mp4",".mkv",".webm",".m3u8")),
                        "https":redirected.scheme=="https"})
                except Exception as error:
                    redirects.append({"error_class":type(error).__name__})
            print(json.dumps({"provider":"mp4hentai",
                "stage":"FOUR_CLICKED_OFFICIAL_GETLINK_REDIRECTS",
                "checked":len(redirects),
                "redirect_shapes":redirects,
                "complete_original_verified":False}),flush=True)
            if return_selected_buzzheavier:
                return list(dict.fromkeys(selected_buzzheavier))
            if return_selected_rapidgator:
                return list(dict.fromkeys(selected_rapidgator))
            return 0 if result.get("host") in ALLOWED else 3
    finally:
        subprocess.run(["taskkill","/PID",str(proc.pid),"/T","/F"],
          capture_output=True,timeout=12)
        try:proc.wait(timeout=4)
        except Exception:pass
        shutil.rmtree(folder,ignore_errors=True)
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"provider":"mp4hentai",
          "status":"SELECTED_MIRROR_CLICK_UNVERIFIED",
          "error_type":type(e).__name__}),flush=True)
        sys.exit(4)
