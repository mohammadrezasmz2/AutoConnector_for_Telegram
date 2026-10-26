<?php
/**
 * Server-side auto-updater (PHP / cPanel) for the private bootstrap channel.
 *
 * Collects fresh MTProto proxies from the full public source list, drops the
 * dead ones with a fast parallel TCP check (so the phone connects quickly even
 * on a bad network), AES-256-GCM encrypts the rest with your key, and
 * atomically writes the blob the app downloads.
 *
 * Place this and club_key.bin OUTSIDE public_html (e.g. ~/club/). Run via
 * cPanel -> Cron Jobs:
 *     php /home/mgnxkdud/club/club_update.php
 *
 * Edit $OUT_FILE below to match your account. Set CLUB_FILTER=0 to skip the
 * reachability check (publish everything).
 */

$KEY_FILE = __DIR__ . '/club_key.bin';                                     // 32 raw bytes
$OUT_FILE = '/home/mgnxkdud/public_html/assets/img/bg-texture-2x.webp';    // <-- your path
$FILTER   = getenv('CLUB_FILTER') !== '0';                                 // TCP pre-check on by default

$SOURCES = [
  "https://widum.ru/proxy/",
  "https://free-telegram.link/",
  "https://mtproto.cloud/",
  "https://vanced.to/telegram/",
  "https://daintycloud.com/proxy-telegram-free-mtproto-proxy-list-real-time-updates/",
  "https://mtpro.xyz/mtproto-ru",
  "https://mtpro.xyz/api/?type=mtproto",
  "https://t.me/s/ProXyMTproto", "https://t.me/s/TelMTProto", "https://t.me/s/MTProtoProxies",
  "https://t.me/s/proxymtproto", "https://t.me/s/mtp4tg", "https://t.me/s/TURKMEN_VPNLAR",
  "https://t.me/s/proxy_tm_unlimited", "https://t.me/s/TProxyRU", "https://t.me/s/ProxyFreeMTProto",
  "https://t.me/s/mtprotoF", "https://t.me/s/mtproto6", "https://t.me/s/mtprotoproxy_telegaram",
  "https://t.me/s/MTProxy4free", "https://t.me/s/proxytgtestperiod1", "https://t.me/s/MTPROTO_MTPROXY_Telegram",
  "https://t.me/s/telega_proxies", "https://t.me/s/proxy_telegramo", "https://t.me/s/proxy_telegramt",
  "https://t.me/s/proxy_Telegram6", "https://t.me/s/tgmtproxylol",
  "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/mtproto.txt",
  "https://raw.githubusercontent.com/Grim1313/mtproto-for-telegram/master/all_proxies.txt",
  "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
  "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/refs/heads/main/proxy_all.txt",
  "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_eu.txt",
  "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_ru.txt",
  "https://raw.githubusercontent.com/Argh94/Proxy-List/refs/heads/main/MTProto.txt",
  "https://raw.githubusercontent.com/LoneKingCode/free-proxy-db/refs/heads/main/proxies/all.txt",
  "https://raw.githubusercontent.com/LoneKingCode/free-proxy-db/refs/heads/main/proxies/mtproto.txt",
  "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no1.txt",
  "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no2.txt",
  "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no3.txt",
  "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no4.txt",
  "https://raw.githubusercontent.com/MahsaNetConfigTopic/proxy/main/proxies.txt",
  "https://raw.githubusercontent.com/onlymeoneme/mtproto/refs/heads/main/all_proxies_list.txt",
  "https://raw.githubusercontent.com/iwh3n/tg-proxy/refs/heads/main/proxys/All_Proxys.txt",
  "https://raw.githubusercontent.com/weltimistar777-crypto/MTProxy/refs/heads/main/archives.txt",
  "https://raw.githubusercontent.com/weltimistar777-crypto/MTProxy/refs/heads/main/proxy.txt",
  "https://raw.githubusercontent.com/MustafaBaqer/VestraNet-Nodes/main/protocols/mtproto.txt",
  "https://raw.githubusercontent.com/Argh94/telegram-proxy-scraper/refs/heads/main/proxy.txt",
  "https://raw.githubusercontent.com/darkvibez456/mtproto-proxy-auto/refs/heads/main/proxies.txt",
  "https://raw.githubusercontent.com/shablin/mtproto-proxy/refs/heads/main/data/valid_proxy.txt",
  "https://raw.githubusercontent.com/Flowseala/Telegram-Proxy/refs/heads/main/all_proxies.txt",
  "https://raw.githubusercontent.com/zilvoog/Ultimate-v2ray-mtproto-parser/refs/heads/main/Config/mtproto.txt",
  "https://zakky8.github.io/mtproto-proxy-pro/all_proxies.txt",
  "https://zakky8.github.io/mtproto-proxy-pro/censorship_resistant.txt",
  "https://raw.githubusercontent.com/yoiambugger/mtproto-hunter/refs/heads/main/proxy_all.txt",
  "https://raw.githubusercontent.com/yoiambugger/mtproto-hunter/refs/heads/main/proxy_eu.txt",
  "https://raw.githubusercontent.com/Therealwh/MTPproxyLIST/refs/heads/main/verified/proxy_all_verified.txt",
  "https://raw.githubusercontent.com/Therealwh/MTPproxyLIST/refs/heads/main/verified/proxy_all_tme_verified.txt",
  "https://raw.githubusercontent.com/Therealwh/MTPproxyLIST/refs/heads/main/verified/proxy_eu_verified.txt",
  "https://raw.githubusercontent.com/Surfboardv2ray/TGProto/refs/heads/main/proxies.txt",
  "https://raw.githubusercontent.com/Yagami200/free-mtproto-proxies/refs/heads/main/proxies.txt",
  "https://raw.githubusercontent.com/Telegram-FZ-LLC/Telegram-Proxy/refs/heads/main/proxies.txt",
];

function fetch_url($url) {
  $ctx = stream_context_create(['http' => [
    'method' => 'GET', 'timeout' => 20, 'follow_location' => 1,
    'header' => "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36\r\nAccept: */*\r\n",
  ], 'ssl' => ['verify_peer' => true, 'verify_peer_name' => true]]);
  $r = @file_get_contents($url, false, $ctx);
  return $r === false ? '' : $r;
}

/** Parse host:port out of a proxy link or a bare host:port:secret line. */
function host_port($raw) {
  if (preg_match('~[?&]server=([^&\s]+).*?[?&]port=(\d+)~i', $raw, $m)) {
    return [strtolower(urldecode($m[1])), (int)$m[2]];
  }
  if (preg_match('~^([a-z0-9.\-]+):(\d{2,5}):~i', $raw, $m)) {
    return [strtolower($m[1]), (int)$m[2]];
  }
  return [null, 0];
}

/** Fast parallel TCP reachability check. Returns a set (host:port => true). */
function reachable(array $pairs, $concurrency = 400, $timeout = 3.0) {
  $ok = [];
  $pairs = array_values($pairs);
  for ($i = 0; $i < count($pairs); $i += $concurrency) {
    $batch = array_slice($pairs, $i, $concurrency);
    $socks = []; $map = [];
    foreach ($batch as $hp) {
      $pos = strrpos($hp, ':'); if ($pos === false) continue;
      $h = substr($hp, 0, $pos); $p = substr($hp, $pos + 1);
      $fp = @stream_socket_client("tcp://$h:$p", $e1, $e2, 0,
              STREAM_CLIENT_ASYNC_CONNECT | STREAM_CLIENT_CONNECT);
      if ($fp) { $id = (int)$fp; $socks[$id] = $fp; $map[$id] = $hp; }
    }
    $deadline = microtime(true) + $timeout;
    while ($socks && microtime(true) < $deadline) {
      $r = null; $w = array_values($socks); $e = array_values($socks);
      $tv = $deadline - microtime(true); if ($tv < 0) $tv = 0;
      $num = @stream_select($r, $w, $e, (int)$tv, (int)(($tv - (int)$tv) * 1e6));
      if ($num === false) break;
      foreach ($w as $fp) {
        $id = (int)$fp;
        if (@stream_socket_get_name($fp, true) !== false) $ok[$map[$id]] = true;
        @fclose($fp); unset($socks[$id]);
      }
    }
    foreach ($socks as $fp) @fclose($fp);   // timed out = unreachable
  }
  return $ok;
}

// --- collect ---------------------------------------------------------------
$seen = []; $entries = [];   // entries: [raw, "host:port"]
foreach ($SOURCES as $u) {
  $text = fetch_url($u);
  if ($text === '') { fwrite(STDERR, "skip $u\n"); continue; }
  $raws = [];
  if (preg_match_all('~(?:tg://proxy|https?://t\.me/proxy)\?[^\s"\'<>]+~i', $text, $m)) $raws = array_merge($raws, $m[0]);
  if (preg_match_all('~\b[a-z0-9.\-]+:\d{2,5}:(?:ee|dd)?[0-9a-fA-F]{2,}\b~', $text, $m)) $raws = array_merge($raws, $m[0]);
  foreach ($raws as $raw) {
    $raw = html_entity_decode($raw);
    if (isset($seen[$raw])) continue; $seen[$raw] = 1;
    list($h, $p) = host_port($raw);
    if (!$h || $p <= 0) continue;
    $entries[] = [$raw, "$h:$p"];
  }
}
fwrite(STDERR, "collected " . count($entries) . " unique proxies\n");
if (count($entries) < 5) { fwrite(STDERR, "too few — not publishing\n"); exit(1); }

// --- drop dead hosts (fail-open if the check nukes almost everything) -------
$final = array_column($entries, 0);
if ($FILTER) {
  $pairs = array_values(array_unique(array_column($entries, 1)));
  $live = reachable($pairs);
  $kept = [];
  foreach ($entries as $e) if (isset($live[$e[1]])) $kept[] = $e[0];
  fwrite(STDERR, "reachable: " . count($kept) . " / " . count($entries) . "\n");
  // Safety: if the check dropped almost everything (e.g. the server itself has
  // no outbound TCP to those ports), publish the unfiltered list instead.
  if (count($kept) >= max(200, (int)(count($entries) * 0.1))) $final = $kept;
  else fwrite(STDERR, "filter kept too few — publishing unfiltered\n");
}

// --- encrypt + write -------------------------------------------------------
$key = @file_get_contents($KEY_FILE);
if ($key === false || strlen($key) !== 32) { fwrite(STDERR, "bad key file\n"); exit(1); }
$plain = implode("\n", $final);
$iv = random_bytes(12); $tag = '';
$ct = openssl_encrypt($plain, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag, '', 16);
$blob = $iv . $ct . $tag;       // nonce(12) + ciphertext + tag(16) — matches the app
@mkdir(dirname($OUT_FILE), 0755, true);
$tmp = $OUT_FILE . '.tmp';
file_put_contents($tmp, $blob);
rename($tmp, $OUT_FILE);         // atomic swap
echo "published " . count($final) . " proxies -> $OUT_FILE (" . strlen($blob) . " bytes)\n";
