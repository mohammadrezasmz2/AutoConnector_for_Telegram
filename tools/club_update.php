<?php
/**
 * Server-side auto-updater (PHP / cPanel) for the private bootstrap channel.
 *
 * Collects fresh MTProto proxies from public sources, AES-256-GCM encrypts them
 * with your key, and atomically writes the blob the app downloads.
 *
 * Place this and club_key.bin OUTSIDE public_html (e.g. ~/club/). Run via
 * cPanel → Cron Jobs:
 *     php /home/USER/club/club_update.php
 *
 * Edit the two paths below to match your account.
 */

$KEY_FILE = __DIR__ . '/club_key.bin';                                  // 32 raw bytes
$OUT_FILE = '/home/USER/public_html/assets/img/bg-texture-2x.webp';     // <-- set USER

$SOURCES = [
  "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/mtproto.txt",
  "https://raw.githubusercontent.com/Grim1313/mtproto-for-telegram/master/all_proxies.txt",
  "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
  "https://raw.githubusercontent.com/onlymeoneme/mtproto/refs/heads/main/all_proxies_list.txt",
  "https://raw.githubusercontent.com/weltimistar777-crypto/MTProxy/refs/heads/main/proxy.txt",
  "https://mtpro.xyz/api/?type=mtproto",
  "https://t.me/s/mtprotoF",
  "https://t.me/s/MTProxy4free",
];

function fetch_url($url) {
  $ctx = stream_context_create(['http' => [
    'method' => 'GET', 'timeout' => 20,
    'header' => "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36\r\nAccept: */*\r\n",
  ], 'ssl' => ['verify_peer' => true, 'verify_peer_name' => true]]);
  $r = @file_get_contents($url, false, $ctx);
  return $r === false ? '' : $r;
}

$seen = []; $out = [];
foreach ($SOURCES as $u) {
  $text = fetch_url($u);
  if ($text === '') { fwrite(STDERR, "skip $u\n"); continue; }
  if (preg_match_all('~(?:tg://proxy|https?://t\.me/proxy)\?[^\s"\'<>]+~i', $text, $m)) {
    foreach ($m[0] as $link) if (!isset($seen[$link])) { $seen[$link]=1; $out[]=$link; }
  }
  if (preg_match_all('~\b([a-z0-9.\-]+):(\d{2,5}):((?:ee|dd)?[0-9a-fA-F]{2,})\b~', $text, $m, PREG_SET_ORDER)) {
    foreach ($m as $p) { $line="$p[1]:$p[2]:$p[3]"; if (!isset($seen[$line])) { $seen[$line]=1; $out[]=$line; } }
  }
}

if (count($out) < 5) { fwrite(STDERR, "only ".count($out)." proxies — not publishing\n"); exit(1); }

$key = file_get_contents($KEY_FILE);
if ($key === false || strlen($key) !== 32) { fwrite(STDERR, "bad key file\n"); exit(1); }

$plain = implode("\n", $out);
$iv = random_bytes(12);
$tag = '';
$ct = openssl_encrypt($plain, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag, '', 16);
$blob = $iv . $ct . $tag;   // nonce(12) + ciphertext + tag(16) — matches the app

@mkdir(dirname($OUT_FILE), 0755, true);
$tmp = $OUT_FILE . '.tmp';
file_put_contents($tmp, $blob);
rename($tmp, $OUT_FILE);     // atomic swap
echo "published ".count($out)." proxies -> $OUT_FILE (".strlen($blob)." bytes)\n";
