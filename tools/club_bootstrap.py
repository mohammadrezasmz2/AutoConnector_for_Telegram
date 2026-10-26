#!/usr/bin/env python3
"""
Covert proxy-bootstrap generator for AutoConnector.

Two jobs:
  1. encrypt   — AES-256-GCM encrypt your proxy list into a blob you upload to
                 your site (looks like any static asset). The app downloads and
                 decrypts it; the hosting provider and any plain-HTTP observer
                 see only ciphertext.
  2. constants — print the obfuscated Java arrays (URL + key + label) to paste
                 into CovertBootstrap.java, so the domain/key are NOT visible to
                 `strings` or a casual decompile.

The key is random; keep tools/club_key.bin private (gitignored). Re-run both
commands whenever you rotate the key or change the URL.

Usage:
  python3 tools/club_bootstrap.py init   --url https://designclup.com/assets/img/bg-2x.webp  --label cloud
  python3 tools/club_bootstrap.py encrypt --in proxies.txt --out bg-2x.webp
  python3 tools/club_bootstrap.py constants
"""
import argparse, json, os, secrets, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CFG = HERE / "club_config.json"
KEYF = HERE / "club_key.bin"

# XOR seed used only to keep the arrays out of `strings`; not a security boundary.
XSEED = bytes([0x5A, 0xC3, 0x19, 0x7E, 0xA4, 0x6B, 0xD2, 0x0F])

def _xor(data: bytes) -> list:
    return [b ^ XSEED[i % len(XSEED)] for i, b in enumerate(data)]

def _java_array(name, data: bytes) -> str:
    vals = ", ".join(str(v) for v in _xor(data))
    return f"    private static final int[] {name} = {{ {vals} }};"

def cmd_init(a):
    if not KEYF.exists():
        KEYF.write_bytes(secrets.token_bytes(32))
        os.chmod(KEYF, 0o600)
    CFG.write_text(json.dumps({"url": a.url, "label": a.label}, indent=2))
    print(f"config  → {CFG}")
    print(f"key     → {KEYF}  (32 bytes, keep private)")
    print("next: python3 tools/club_bootstrap.py encrypt --in proxies.txt --out <asset>")

def _load():
    if not CFG.exists() or not KEYF.exists():
        sys.exit("run `init` first")
    return json.loads(CFG.read_text()), KEYF.read_bytes()

def cmd_encrypt(a):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    _, key = _load()
    pt = Path(a.inp).read_bytes()
    nonce = secrets.token_bytes(12)
    ct = AESGCM(key).encrypt(nonce, pt, None)
    Path(a.out).write_bytes(nonce + ct)
    print(f"wrote {a.out} ({len(nonce)+len(ct)} bytes) — upload this to the URL in your config")

def cmd_constants(a):
    cfg, key = _load()
    print("// ---- paste into CovertBootstrap.java (generated, do not edit by hand) ----")
    print(_java_array("U", cfg["url"].encode()))
    print(_java_array("K", key))
    print(_java_array("L", cfg["label"].encode()))
    print(f"    private static final int XS0 = {XSEED[0]}; // seed marker")

def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    i = sub.add_parser("init");     i.add_argument("--url", required=True); i.add_argument("--label", default="cloud"); i.set_defaults(f=cmd_init)
    e = sub.add_parser("encrypt");  e.add_argument("--in", dest="inp", required=True); e.add_argument("--out", required=True); e.set_defaults(f=cmd_encrypt)
    c = sub.add_parser("constants"); c.set_defaults(f=cmd_constants)
    a = p.parse_args(); a.f(a)

if __name__ == "__main__":
    main()
