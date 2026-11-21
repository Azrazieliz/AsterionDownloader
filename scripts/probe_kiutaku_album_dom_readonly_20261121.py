#!/usr/bin/env python3
"""Read-only HTML structure probe for owner-reported Kiutaku numeric galleries.
Fetches ONLY publisher HTML (no image download); prints attribute/count
structure without original image URLs or user media identifiers.
"""
import collections, html.parser, json, urllib.request, urllib.error
from urllib.parse import urljoin, urlsplit

class Structure(html.parser.HTMLParser):
    def __init__(self,base):
        super().__init__(); self.base=base; self.stack=[]
        self.images=[];self.image_links=0;self.script_types=collections.Counter()
        self.containers=collections.Counter()
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag in ("main","article","div","section"):
            cls=a.get("class","")
            if cls:self.containers.update(cls.split())
        if tag=="img":
            names=sorted(a)
            address=""
            for key in ("data-original","data-full","data-full-src",
                        "data-highres","data-zoom","srcset","data-src","src"):
                if a.get(key):
                    address=a[key].split(",")[0].split(" ")[0]; break
            parts=urlsplit(urljoin(self.base,address))
            ancestors=tuple(z for z in self.stack[-8:] if z[0] in ("main","article","div"))
            self.images.append({
                "attrs":names,
                "host":parts.hostname or "",
                "ext":parts.path.rsplit(".",1)[-1].lower()[:8],
                "ancestor_classes":[c[1] for c in ancestors if c[1]][-5:],
                "has_srcset":bool(a.get("srcset")),
                "has_data_original":bool(a.get("data-original")),
            })
        if tag=="a":
            p=urlsplit(urljoin(self.base,a.get("href",""))).path.lower()
            if p.endswith((".jpeg",".jpg",".png",".webp",".avif")):
                self.image_links+=1
        if tag=="script":
            self.script_types[a.get("type","(no type)")]+=1
        if tag not in ("meta","img","input","source","link","br","hr","area"):
            self.stack.append((tag,a.get("class","")))
    def handle_endtag(self,tag):
        for i in range(len(self.stack)-1,-1,-1):
            if self.stack[i][0]==tag:
                del self.stack[i:];break

def main():
    for id in (7466,7440):
        url="https://kiutaku.com/"+str(id)
        try:
            req=urllib.request.Request(url,headers={
              "User-Agent":"Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36 Kyora/0.6.0",
              "Accept":"text/html,application/xhtml+xml",
              "Accept-Language":"en-US,en;q=0.8"})
            with urllib.request.urlopen(req,timeout=17) as response:
                html=response.read(850000).decode("utf-8","replace")
                parser=Structure(response.url);parser.feed(html)
                print(json.dumps({"gallery_id":id,"http":response.status,
                    "html_length":len(html),
                    "images":len(parser.images),"image_links":parser.image_links,
                    "article_fulltext":html.count("article-fulltext"),
                    "main":html.count("<main"),
                    "script_count":sum(parser.script_types.values()),
                    "containers":parser.containers.most_common(18),
                    "image_structures":parser.images[:14]}),flush=True)
        except Exception as e:
            print(json.dumps({"gallery_id":id,"error":type(e).__name__,
                "message":str(e)[:100]}),flush=True)

if __name__=="__main__":main()
