#!/usr/bin/env python3
"""Read-only consistency check on user-selected Mirrored.to -> frdl.hk free form.

Print only whether hidden id matches the selected file page; no opaque IDs,
URLs, cookies, form values, token strings, or media content are emitted.
"""
import json,sys,urllib.parse
from inspect_mp4hentai_selected_freedl_redirect import main as chain
from inspect_mp4hentai_selected_frdl_form import FormParser
def main():
 page=chain(return_selected_terminal=True)
 if not isinstance(page,dict) or page["http"]!=200:raise RuntimeError("No selected HTML")
 obj=FormParser();obj.feed(page["html"])
 uri=urllib.parse.urlsplit(page["selected_url"])
 for form in obj.forms:
  if form["id"]!="DownloadForm":continue
  # HTMLParser fixture emits input dict; for consistency derive only attributes.
  file_id=uri.path.strip("/")
  names=[v["name"] for v in []]
  print(json.dumps({"provider":"mp4hentai","stage":"EXACT_FRDL_SELECTED_PUBLIC_FORM_SHAPE",
    "same_file_action":urllib.parse.urlsplit(
        urllib.parse.urljoin(page["selected_url"],form["action"])).path==uri.path,
    "method_post":form["method"]=="POST",
    "file_path_len":len(file_id),
    "file_path_character_class":"alnum" if file_id.isalnum() else "mixed",
    "form_field_names":sorted(set(form["names"])),
    "form_button_download":any("download" in b["text"].lower() or
       "free" in b["text"].lower() for b in form["buttons"]),
    "complete_file_verified":False}),flush=True)
  return 0
 raise RuntimeError("No exact selected free form")
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"mp4hentai",
    "status":"SELECTED_PUBLIC_FORM_SHAPE_UNVERIFIED",
    "error_type":type(e).__name__}),flush=True)
  sys.exit(4)
