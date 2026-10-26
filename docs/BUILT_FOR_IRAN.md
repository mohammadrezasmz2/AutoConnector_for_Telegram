# Built and customized for Iran's internet

_Last updated: 2026-10-27_

AutoConnector for Telegram is general-purpose, but this fork is **tuned
specifically for the conditions people face on the Iranian internet**, where
Telegram is blocked, bandwidth is unreliable, and even the public proxy lists
that would normally help are themselves often unreachable. The customizations
below exist to keep Telegram working under exactly those conditions.

## The problem this fork solves

On the Iranian network:

1. Telegram is blocked, so a working MTProto proxy is needed just to connect.
2. The public sites and channels that publish free proxies are frequently
   blocked too, so an app that only reads those sources can end up with an empty
   proxy pool and no way to recover.
3. DPI (deep packet inspection) actively interferes with proxy connections, so a
   proxy that works one minute can be throttled or cut the next.
4. Connections are slow and lossy, so a proxy that is merely "reachable" is not
   enough — it has to be fast *from your device, on your network, right now*.

## What was customized for these conditions

- **National-intranet bootstrap channel.** When the public proxy sources are
  blocked, the app can still fetch a fresh, working proxy list from a site that
  remains reachable on Iran's national internet (the "intranet"). The list is
  downloaded **encrypted** (AES-256-GCM) and the endpoint is not exposed in the
  app's visible source, so the channel keeps working and stays low-profile. This
  is what lets a device that can reach *almost nothing* still refill its proxy
  pool and connect out. (The list contains only public, free proxies — see
  [SECURITY.md](../SECURITY.md).)

- **Works on Wi-Fi + mobile data without a VPN.** No VPN is required. The app
  keeps separate proxy pools and speed ratings per network type (Wi-Fi / mobile
  data / etc.), because what is fast on one network is often dead on another in
  Iran.

- **Anti-DPI masking.** A set of techniques (browser-like TLS "FakeTLS", packet
  splitting, and others) helps connections survive DPI. An auto-rotate mode
  tries them and keeps whichever one currently gets through, since the blocking
  behavior changes over time and from one ISP to another.

- **Tested from your own device.** Every proxy is checked with a real MTProto
  handshake from your phone on your current network — not from a server
  somewhere — so the app recommends proxies that actually work *for you*, not
  ones that only look alive elsewhere.

- **Aggressive, self-healing proxy collection.** The app continuously gathers
  proxies from many public sources, drops the dead ones, spreads load across
  several good proxies at once, and automatically moves to a faster one when it
  finds it — so a bad connection repairs itself without you touching anything.

- **Localized for the region.** The interface includes Persian (and many other
  regional languages) with full right-to-left support and automatic language
  detection.

## What it is *not*

It is not a VPN, and it does not route your whole device's traffic — it only
helps Telegram. It does not collect data about you, and it does not require a
Telegram login. See [SECURITY.md](../SECURITY.md) for the full safety statement.
