#!/usr/bin/env python3
"""Verify selected JavGuru JK standard HLS AES-128 media without printing keys.

This is ordinary HLS playback processing of a publisher-declared key URI,
not a guessed key, challenge bypass or independent cryptanalysis.
Read exactly one true selected media segment and its officially published
16-byte key; verify decrypted MPEG-TS packet signature. Never save media.
"""
import json,re,sys,urllib.parse,urllib.request
from verify_javguru_owner_jk_full_pc import selected_final
from inspect_javguru_jk_hls_finality import manifest,UA
from cryptography.hazmat.primitives.ciphers import Cipher,algorithms,modes

HOST="s4.maxstream.org"
def get(url,referer):
    u=urllib.parse.urlsplit(url)
    if u.scheme!="https" or u.hostname!=HOST or u.username or u.password or u.port:
        raise RuntimeError("not exact selected publisher CDN")
    req=urllib.request.Request(url,headers={"User-Agent":UA,
        "Referer":referer,"Accept-Encoding":"identity"})
    with urllib.request.urlopen(req,timeout=26) as response:
        if response.status not in (200,206):
            raise RuntimeError("selected HLS HTTP failed")
        return response.read(12_000_000)
def extract(media,master):
    source=manifest(media,master)
    if source.count("#EXT-X-ENDLIST")!=1:
        raise RuntimeError("signed selected media not FINAL")
    key_rows=re.findall(r'(?m)^#EXT-X-KEY:([^\r\n]+)',source)
    if len(key_rows)!=1:raise RuntimeError("selected media must declare one AES key")
    spec=key_rows[0]
    method=re.search(r'(?:^|,)METHOD=([A-Z0-9_-]+)',spec)
    key_match=re.search(r'(?:^|,)URI="([^"]{1,2200})"',spec)
    iv_match=re.search(r'(?:^|,)IV=0x([A-Fa-f0-9]{32})',spec)
    if not method or method.group(1)!="AES-128" or not key_match:
        raise RuntimeError("only official standard AES-128 HLS is supported")
    key=urllib.parse.urljoin(media,key_match.group(1))
    lines=[line.strip() for line in source.splitlines() if line.strip()]
    item=next((line for line in lines if not line.startswith("#")),None)
    if item is None:raise RuntimeError("no selected media fragment")
    fragment=urllib.parse.urljoin(media,item)
    seq=re.search(r"(?m)^#EXT-X-MEDIA-SEQUENCE:([0-9]+)",source)
    sequence=int(seq.group(1)) if seq else 0
    iv=bytes.fromhex(iv_match.group(1)) if iv_match else sequence.to_bytes(16,"big")
    return key,fragment,iv,len(lines),iv_match is not None
def main():
    embed,master,media,stats=selected_final()
    key_url,fragment,iv,n,explicit_iv=extract(media,master)
    key=get(key_url,media)
    if len(key)!=16:raise RuntimeError("selected official AES key is not 16 bytes")
    encrypted=get(fragment,media)
    if len(encrypted)<10000 or len(encrypted)%16:
        raise RuntimeError("publisher segment is not complete CBC ciphertext")
    decryptor=Cipher(algorithms.AES(key),modes.CBC(iv)).decryptor()
    plaintext=decryptor.update(encrypted)+decryptor.finalize()
    padding=plaintext[-1]
    if 0<padding<=16 and plaintext[-padding:]==bytes([padding])*padding:
        plaintext=plaintext[:-padding]
    valid=len(plaintext)%188==0 and all(
        plaintext[i:i+1]==b"\x47"
        for i in (0,188,376,564) if i<len(plaintext))
    print(json.dumps({"provider":"jav_guru",
        "stage":"SELECTED_FINAL_JK_STANDARD_HLS_AES_FIRST_FRAGMENT",
        "selected_original_duration_s":stats["runtime_s"],
        "endlist":stats["endlist"],
        "key_method":"AES-128",
        "publisher_key_accessible":True,
        "explicit_iv":explicit_iv,
        "ciphertext_bytes":len(encrypted),
        "plaintext_bytes":len(plaintext),
        "standard_hls_ts_valid":valid,
        "all_fragments_complete":False,
        "whole_movie_verified":False}),flush=True)
    return 0 if valid else 4
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"provider":"jav_guru",
            "stage":"SELECTED_JK_HLS_AES_NOT_VERIFIED",
            "error_class":type(e).__name__}),flush=True)
        sys.exit(3)
