#!/usr/bin/env python3
"""
AutoConnector — proxy web page builder.

Collects public MTProto proxies from the sources in sources.txt, checks every
one with a real MTProto handshake (the proxy must relay a request to Telegram
and return a valid resPQ), ranks them by stability and speed, and writes:

    <out>/index.html     the page (Persian, RTL, one-tap "connect" buttons)
    <out>/proxies.json   ranked list the page reads
    <out>/proxies.txt    tg:// links of every live proxy, best first

Needs Python 3 and `pip install cryptography`. Runs the same way in GitHub
Actions (-> GitHub Pages) and on your own server (cron).

    python3 web/build_site.py --out site
    python3 web/build_site.py --out /var/www/html/proxy --top 300

NOTE: latency is measured from the machine that runs this script. The page
has a "test from my device" button that re-ranks the list from the visitor's
own network.
"""
import argparse, asyncio, html, json, re, shutil, ssl, statistics, sys, time
import urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

RE_LINK = re.compile(r'(?:tg://proxy|https?://t\.me/proxy)\?[^\s"\'<>]+', re.I)
RE_BARE = re.compile(r'\b([a-z0-9][a-z0-9.\-]*\.[a-z0-9\-]+|\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5}):'
                     r'((?:ee|dd)?[0-9a-fA-F]{32,}|[A-Za-z0-9_\-]{22,}={0,2})\b', re.I)
RE_SECRET = re.compile(r'^[A-Za-z0-9_\-+/=]{16,}$')
RE_HOST = re.compile(r'^[a-z0-9.\-]{3,253}$')


# ---------------------------------------------------------------- collect --
def fetch(url, timeout=20):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
        with urllib.request.urlopen(req, timeout=timeout,
                                    context=ssl.create_default_context()) as r:
            return r.read(8_000_000).decode("utf-8", "ignore")
    except Exception as e:
        print(f"  skip {url}: {e.__class__.__name__}", file=sys.stderr)
        return ""


def norm(host, port, secret):
    host = (host or "").strip().lower().rstrip(".")
    secret = (secret or "").strip()
    try:
        port = int(port)
    except (TypeError, ValueError):
        return None
    if not (RE_HOST.match(host) and 1 <= port <= 65535 and RE_SECRET.match(secret)):
        return None
    return host, port, secret


def parse(text):
    out = []
    # JSON APIs, e.g. mtpro.xyz -> [{"host":..,"port":..,"secret":..}, ...]
    s = text.lstrip()
    if s[:1] in "[{":
        try:
            data = json.loads(s)
            items = data if isinstance(data, list) else data.get("proxies") or data.get("data") or []
            for it in items:
                if isinstance(it, dict):
                    p = norm(it.get("host") or it.get("server") or it.get("ip"),
                             it.get("port"), it.get("secret"))
                    if p:
                        out.append(p)
        except Exception:
            pass
    text = html.unescape(text)
    for link in RE_LINK.findall(text):
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)
        p = norm(q.get("server", [""])[0], q.get("port", [""])[0], q.get("secret", [""])[0])
        if p:
            out.append(p)
    for host, port, secret in RE_BARE.findall(text):
        p = norm(host, port, secret)
        if p:
            out.append(p)
    return out


def collect(sources):
    seen, proxies = set(), []
    with ThreadPoolExecutor(max_workers=16) as ex:
        for url, text in zip(sources, ex.map(fetch, sources)):
            n0 = len(proxies)
            for p in parse(text):
                if p not in seen:
                    seen.add(p)
                    proxies.append(p)
            if text:
                print(f"  {url}: +{len(proxies) - n0}", file=sys.stderr)
    return proxies


# ------------------------------------------------------------------ check --
async def check_all(proxies, rounds, timeout, concurrency):
    """Real MTProto handshake (resPQ from Telegram DC 2) for every proxy.
    Returns {(host, port, secret): [ms or None, ...]}."""
    from mtproto_check import check
    sem = asyncio.Semaphore(concurrency)
    results = {p: [] for p in proxies}

    async def one(p):
        async with sem:
            results[p].append(await check(p[0], p[1], p[2], timeout))

    todo = list(proxies)
    for r in range(rounds):
        await asyncio.gather(*(one(p) for p in todo))
        if r == 0:  # only proxies that passed once get the extra rounds
            todo = [p for p in todo if results[p][0] is not None]
            print(f"  round 1: {len(todo)} / {len(proxies)} answered a real MTProto handshake",
                  file=sys.stderr)
        await asyncio.sleep(0.3)
    return results


# ------------------------------------------------------------------ build --
def kind(secret):
    from mtproto_check import secret_hex
    s = secret_hex(secret) or ""
    if s.startswith("ee") and len(s) > 34:
        return "faketls"
    if s.startswith("dd"):
        return "secure"
    return "classic"


def build(proxies, results, rounds, top):
    live = []
    for host, port, secret in proxies:
        samples = results.get((host, port, secret)) or []
        ok = [x for x in samples if x is not None]
        if not ok:
            continue
        q = urllib.parse.urlencode({"server": host, "port": port, "secret": secret})
        live.append({
            "host": host, "port": port, "secret": secret, "type": kind(secret),
            "ms": round(statistics.median(ok)),
            "ok": len(ok), "tries": len(samples),
            "tg": f"tg://proxy?{q}", "tme": f"https://t.me/proxy?{q}",
        })
    # stable first (answered every round), then fastest; FakeTLS wins ties
    live.sort(key=lambda p: (-p["ok"] / max(p["tries"], 1), p["ms"], p["type"] != "faketls"))
    # several secrets often share one host:port — keep the list diverse
    per_ep, ranked = {}, []
    for p in live:
        k = (p["host"], p["port"])
        per_ep[k] = per_ep.get(k, 0) + 1
        if per_ep[k] <= 2:
            ranked.append(p)
    return ranked[:top], live


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="site", help="output folder")
    ap.add_argument("--sources", default=str(HERE / "sources.txt"))
    ap.add_argument("--top", type=int, default=300, help="proxies shown on the page")
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--timeout", type=float, default=6.0)
    ap.add_argument("--concurrency", type=int, default=300)
    ap.add_argument("--input", help="read proxies from a local file instead of the sources (testing)")
    a = ap.parse_args()

    if a.input:
        proxies = list(dict.fromkeys(parse(Path(a.input).read_text("utf-8", "ignore"))))
    else:
        srcs = [l.strip() for l in Path(a.sources).read_text().splitlines()
                if l.strip() and not l.lstrip().startswith("#")]
        print(f"fetching {len(srcs)} sources…", file=sys.stderr)
        proxies = collect(srcs)
    print(f"collected {len(proxies)} unique proxies", file=sys.stderr)
    if len(proxies) < 5:
        sys.exit("too few proxies collected — keeping the previous page")

    print(f"handshake-checking {len(proxies)} proxies…", file=sys.stderr)
    results = asyncio.run(check_all(proxies, a.rounds, a.timeout, a.concurrency))
    ranked, live = build(proxies, results, a.rounds, a.top)
    print(f"live: {len(live)}  on page: {len(ranked)}", file=sys.stderr)
    if not ranked:
        sys.exit("no live proxies — keeping the previous page")

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    meta = {"updated": int(time.time()), "collected": len(proxies),
            "live": len(live), "proxies": ranked}
    (out / "proxies.json").write_text(json.dumps(meta, ensure_ascii=False, separators=(",", ":")))
    (out / "proxies.txt").write_text("\n".join(p["tg"] for p in live) + "\n")
    shutil.copy(HERE / "index.html", out / "index.html")
    (out / ".nojekyll").write_text("")
    print(f"wrote {out}/index.html, proxies.json, proxies.txt", file=sys.stderr)


if __name__ == "__main__":
    main()
