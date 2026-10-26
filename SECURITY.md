# Security & Safety Statement

_Last updated: 2026-10-27_

This document explains what **AutoConnector for Telegram** does, what it
deliberately does **not** do, and why it is not a threat to Telegram or to the
people who use it. The project is open-source, so every claim below can be
verified against the code in this repository.

## In one sentence

AutoConnector is a **proxy finder and local relay**. It discovers public, free
**MTProto** proxies (the proxy type Telegram itself supports), verifies that
they actually work from your own device, and lets Telegram connect through the
best one — automatically, so you don't have to paste proxy links by hand.

## Why it is not a threat to Telegram

- **It uses Telegram's own public MTProto proxy protocol.** It does not modify,
  patch, repack, or inject anything into the Telegram app. Telegram runs
  completely unchanged; AutoConnector simply provides it with a working proxy,
  exactly as if you had entered one in Telegram's own Settings → Data and Storage
  → Proxy.
- **It never touches your Telegram account.** It has no access to your phone
  number, login code, password, sessions, chats, contacts, or messages. It never
  asks you to log in and never sees your credentials. It only moves encrypted
  packets between Telegram and a proxy — it cannot read them.
- **It only relays; it does not intercept.** The app opens a local relay on
  `127.0.0.1` on your device. Telegram connects to that local address, and the
  app forwards the already-encrypted MTProto traffic to a public proxy. The
  payload stays end-to-end between your Telegram client and Telegram's servers.
- **The proxies are public and free.** AutoConnector collects proxies that are
  already published openly (Telegram channels, public subscription lists). It
  does not run attacks, does not scan or brute-force anything, and does not
  create or operate proxy servers.
- **No ads, no trackers, no analytics, no data collection.** The app sends no
  usage data anywhere. Proxy reachability is tested from your own device and
  network; nothing about you leaves your phone.

## What the app accesses on your device

| Permission / capability | Why |
|---|---|
| Internet | To download public proxy lists and relay Telegram's traffic. |
| Foreground service | To keep the local relay alive while you use Telegram. |
| (Optional) battery exemption | So the relay isn't killed in the background. |

It requests **no** access to contacts, SMS, storage, location, camera, or
microphone.

## Verifying the app is genuine

Release builds are signed with a single, stable key, so every real build comes
from the same source and installs as an update over the previous one. You can
confirm a downloaded APK is authentic by checking its signing certificate:

```
apksigner verify --print-certs AutoConnector-latest.apk
```

The SHA-256 fingerprint of the official signing certificate is:

```
EA:91:C1:27:F5:BA:EE:D5:CD:12:BD:B0:DF:2E:42:4B:90:23:2A:21:E1:18:52:43:1B:C7:84:CE:CD:DD:B1:2C
```

If an APK shows a different fingerprint, it was **not** built by this project —
do not install it.

## A note on "unsafe app" / "Install anyway" prompts

AutoConnector is distributed **outside the Google Play Store** (by design, since
it is a censorship-circumvention tool). Android shows an "unknown source" /
"Install anyway" prompt for **any** app installed outside the Play Store — this
is a normal Android safety feature, not a sign that this specific app is
harmful. The app being correctly signed (see the fingerprint above) is what lets
you trust that the file you installed is the real one.

## Reporting a problem

If you believe you have found a genuine security issue, please open an issue on
this repository describing it. Because the project is open-source, independent
review of the code is welcome and encouraged.
