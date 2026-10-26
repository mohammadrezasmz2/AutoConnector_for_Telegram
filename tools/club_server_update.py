#!/usr/bin/env python3
"""
Server-side auto-updater for the private bootstrap channel.

Runs on your site's host (which HAS open-internet access), fetches fresh
MTProto proxies from public sources, AES-256-GCM encrypts them with your key,
and writes the blob to the web path the app downloads. Put it on cron:

    */120 * * * * /usr/bin/python3 /path/club_server_update.py >> /path/club.log 2>&1

Setup on the server:
    pip3 install cryptography
    # copy club_key.bin here (the SAME key you used for the constants in the app)
    export CLUB_KEY=/path/club_key.bin
    export CLUB_OUT=/var/www/designclup.com/assets/img/bg-texture-2x.webp

Only needs the stdlib + cryptography.
"""
import os, re, ssl, sys, secrets, urllib.request
from pathlib import Path

KEYF = Path(os.environ.get("CLUB_KEY", "club_key.bin"))
OUT  = Path(os.environ.get("CLUB_OUT", "bg-texture-2x.webp"))

# Public MTProto-proxy lists. Add/remove freely; unreachable ones are skipped.
SOURCES = [
    "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/mtproto.txt",
    "https://raw.githubusercontent.com/Grim1313/mtproto-for-telegram/master/all_proxies.txt",
    "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
    "https://raw.githubusercontent.com/onlymeoneme/mtproto/refs/heads/main/all_proxies_list.txt",
    "https://raw.githubusercontent.com/weltimistar777-crypto/MTProxy/refs/heads/main/proxy.txt",
    "https://mtpro.xyz/api/?type=mtproto",
    "https://t.me/s/mtprotoF",
    "https://t.me/s/MTProxy4free",
]

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

# tg://proxy / https://t.me/proxy links, and bare host:port:secret lines.
RE_LINK = re.compile(r'(?:tg://proxy|https?://t\.me/proxy)\?[^\s"\'<>]+', re.I)
RE_BARE = re.compile(r'\b([a-z0-9.\-]+):(\d{2,5}):([0-9a-fA-F]{2,}|ee[0-9a-fA-F]+)\b')

def fetch(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
        ctx = ssl.create_default_context()
        with urllib.request.urlopen(req, timeout=20, context=ctx) as r:
            return r.read().decode("utf-8", "ignore")
    except Exception as e:
        print(f"  skip {url}: {e}")
        return ""

def collect():
    seen, out = set(), []
    for u in SOURCES:
        text = fetch(u)
        if not text:
            continue
        n0 = len(out)
        for m in RE_LINK.findall(text):
            if m not in seen:
                seen.add(m); out.append(m)
        for host, port, sec in RE_BARE.findall(text):
            line = f"{host}:{port}:{sec}"
            if line not in seen:
                seen.add(line); out.append(line)
        print(f"  {u}: +{len(out)-n0}")
    return out

def main():
    if not KEYF.exists():
        sys.exit(f"key not found: {KEYF} (set CLUB_KEY)")
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    key = KEYF.read_bytes()
    proxies = collect()
    if len(proxies) < 5:
        sys.exit(f"only {len(proxies)} proxies collected — not publishing (sources may be down)")
    payload = "\n".join(proxies).encode()
    nonce = secrets.token_bytes(12)
    blob = nonce + AESGCM(key).encrypt(nonce, payload, None)
    tmp = OUT.with_suffix(OUT.suffix + ".tmp")
    tmp.write_bytes(blob)
    os.replace(tmp, OUT)   # atomic swap so the app never reads a half-written file
    print(f"published {len(proxies)} proxies → {OUT} ({len(blob)} bytes)")

if __name__ == "__main__":
    main()
