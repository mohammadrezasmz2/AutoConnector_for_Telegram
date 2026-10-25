# 1.2.0-speed — speed-first relay

## Why Telegram was "connected but slow"
The relay picked upstreams by *handshake* success / latency, and with the default
settings (breadth 50 %, race of 20) the candidate list was shuffled at random.
Whichever proxy answered the handshake first won — even if it could barely
download. Some public proxies also accept the handshake and then forward nothing;
the relay waited up to 180 s before dropping them.

## What changed

### Real download-speed ranking (`SpeedMeter`, `SpeedBoard`)
* Every relayed connection measures its real **download** throughput
  (upstream → Telegram) in 250 ms buckets: sustained 2 s burst windows for fast
  hosts, transfer-period averaging for slow ones. Control traffic (pings, acks)
  is ignored, so idle sessions never produce a fake low speed.
* Samples are folded into a per-host, **per-network-mode** EWMA (a host can fly
  on Wi-Fi and crawl on LTE), persisted in the new `proxy_speed` table (DB v14).
* Stale measurements and recent stalls are discounted.

### Speed-first upstream selection (`RelayConnection.acquireUpstream`)
* Proven-fast hosts get their own short **fast-lane race** first, so a host that
  merely handshakes quickly can't steal the slot.
* A controlled share of connections explores unmeasured hosts so new fast
  proxies are discovered (exploration rate set by auto-tune).
* Hosts on cooldown are filtered with one batched query.
* A pinned host ("use in relay") is now always tried first — wide breadth used
  to shuffle it away.

### Dead / frozen connection guard (`StallWatchdog`)
* Upstream never replies within 12 s → connection is cut, host demoted.
* Download freezes for 20 s while Telegram keeps asking → cut, host demoted.
* Telegram reconnects immediately through another host. Pinned hosts are never cut.

### Smart auto-tune (`AutoTuner`)
Every 20 s, from live measurements: connect timeout = 2.5 × p80 of real connect
times (1.5–8 s), race width from the handshake success rate, breadth and
exploration from the number of proven-fast hosts. The user's own values are kept
and used as fallback when auto-tune is off.

### Telegram first (`TrafficGovernor`)
While Telegram downloads through the relay, background probing drops to 4
parallel checks and routine subscription refreshes wait.

### Catalog
* Built with **one SQL query** per mode off the UI thread (was 100+ queries per
  recomposition on the main thread) and re-ranked live every 2 s.
* Order: pinned → proven-fast (by speed) → unmeasured (by rating) → slow →
  cooling down → dead. Each tile shows the measured speed (green / amber / red).
* "Connect best in Telegram", "Copy top N" and a one-tap ✈ button per tile.

### UI / language
* All ~300 hard-coded Russian strings in the engine (notification, logs, engine
  names and descriptions, LAN web page, units) are now English.
* New "Speed & smart mode" settings section; (i) explanation buttons on the
  individual settings. New strings in English + Persian.

### Build
* `.github/workflows/android-apk.yml` builds an installable debug APK on every push.
