"""
Real MTProto proxy check, ported from the app's HealthChecker
(MtHandshakeProbe + Obfuscated2 + IntermediateTransport + FakeTlsClient).

A proxy only passes if it relays a req_pq_multi to Telegram DC 2 and returns a
resPQ that echoes our nonce. A port that merely accepts TCP (Cloudflare, a
web server, a dead backend, a wrong secret) fails.

Needs: pip install cryptography
"""
import asyncio, base64, hashlib, hmac, os, re, struct, time

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

DC_ID = 2
REQ_PQ_MULTI = 0xbe7e8ef1
RES_PQ = 0x05162463
TAG_INTERMEDIATE = 0xeeeeeeee
TAG_PADDED = 0xdddddddd
RE_HEX = re.compile(r'^[0-9a-fA-F]+$')


def secret_hex(secret):
    """Any secret form (hex or base64url) -> lower-case hex, or None."""
    s = secret.strip()
    if RE_HEX.match(s) and len(s) % 2 == 0:
        return s.lower()
    try:
        raw = base64.urlsafe_b64decode(s.replace('+', '-').replace('/', '_') + '=' * (-len(s) % 4))
        return raw.hex() if len(raw) >= 16 else None
    except Exception:
        return None


def _ctr(key, iv):
    return Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()


class _Obf2:
    """Client side of obfuscated2 (Obfuscated2.perform)."""

    def __init__(self, secret16, tag):
        while True:
            init = bytearray(os.urandom(64))
            first = struct.unpack('>I', init[:4])[0]
            if init[0] != 0xef and first not in (
                    0x48454144, 0x504f5354, 0x47455420, 0x4f505449,
                    0x16030102, 0xdddddddd, 0xeeeeeeee) and init[4:8] != b'\0\0\0\0':
                break
        struct.pack_into('<I', init, 56, tag)
        struct.pack_into('<h', init, 60, DC_ID)
        rev = bytes(init[55:7:-1])
        enc_key, enc_iv = bytes(init[8:40]), bytes(init[40:56])
        dec_key, dec_iv = rev[:32], rev[32:48]
        enc_key = hashlib.sha256(enc_key + secret16).digest()
        dec_key = hashlib.sha256(dec_key + secret16).digest()
        self.enc, self.dec = _ctr(enc_key, enc_iv), _ctr(dec_key, dec_iv)
        encrypted = self.enc.update(bytes(init))
        self.frame = bytes(init[:56]) + encrypted[56:]


def _req_pq_multi():
    nonce = os.urandom(16)
    body = struct.pack('<I', REQ_PQ_MULTI) + nonce
    msg = b'\0' * 8 + struct.pack('<qI', int(time.time()) << 32, len(body)) + body
    return msg, nonce


def _check_res_pq(resp, nonce):
    if len(resp) < 40:
        raise IOError(f'resPQ too short: {len(resp)}')
    if struct.unpack_from('<I', resp, 20)[0] != RES_PQ:
        raise IOError('not resPQ')
    if resp[24:40] != nonce:
        raise IOError('nonce mismatch')


# ---------------------------------------------------------------- FakeTLS --
def _ext(t, data):
    return struct.pack('>HH', t, len(data)) + data


def _client_hello(secret16, domain):
    """517-byte ClientHello with the secret's HMAC in `random` (FakeTlsClient NORMAL)."""
    host = domain.encode('ascii', 'ignore')[:200] or b'www.cloudflare.com'
    sni = struct.pack('>HBH', len(host) + 3, 0, len(host)) + host
    ciphers = bytes.fromhex('130113021303c02bc02fc02cc030cca9cca8c013c014009c009d002f0035')

    def build(pad):
        ext = (_ext(0x0000, sni)
               + _ext(0x002b, bytes.fromhex('0403040303'))
               + _ext(0x000a, bytes.fromhex('0004001d0017'))
               + _ext(0x000d, bytes.fromhex('0006040308040401'))
               + _ext(0x0033, struct.pack('>HHH', 36, 0x001d, 32) + os.urandom(32))
               + _ext(0x002d, b'\x01\x01')
               + _ext(0x0015, b'\0' * pad))
        body = (b'\x03\x03' + b'\0' * 32 + b'\x20' + os.urandom(32)
                + struct.pack('>H', len(ciphers)) + ciphers + b'\x01\x00'
                + struct.pack('>H', len(ext)) + ext)
        hs = b'\x01' + struct.pack('>I', len(body))[1:] + body
        return bytearray(b'\x16\x03\x01' + struct.pack('>H', len(hs)) + hs)

    rec = build(max(0, 517 - len(build(0))))
    digest = bytearray(hmac.new(secret16, bytes(rec), hashlib.sha256).digest())
    now = struct.pack('<I', int(time.time()) & 0xffffffff)
    for i in range(4):
        digest[28 + i] ^= now[i]
    rec[11:43] = digest
    return bytes(rec)


async def _read_record(r):
    hdr = await r.readexactly(5)
    return hdr[0], await r.readexactly(struct.unpack('>H', hdr[3:5])[0])


class _Tls:
    def __init__(self, r, w):
        self.r, self.w, self.buf = r, w, b''

    def write(self, data):
        for i in range(0, len(data), 1 << 14):
            chunk = data[i:i + (1 << 14)]
            self.w.write(b'\x17\x03\x03' + struct.pack('>H', len(chunk)) + chunk)

    async def readexactly(self, n):
        while len(self.buf) < n:
            t, payload = await _read_record(self.r)
            if t == 0x17:
                self.buf += payload
            elif t == 0x15:
                raise IOError('TLS alert')
            elif t != 0x14:
                raise IOError(f'unexpected record {t:#x}')
        out, self.buf = self.buf[:n], self.buf[n:]
        return out


class _Plain:
    def __init__(self, r, w):
        self.r, self.w = r, w

    def write(self, data):
        self.w.write(data)

    async def readexactly(self, n):
        return await self.r.readexactly(n)


# ------------------------------------------------------------------ probe --
async def _probe(host, port, sec_hex):
    raw = bytes.fromhex(sec_hex)
    fake_tls = sec_hex.startswith('ee') and len(raw) > 17
    padded = fake_tls or sec_hex[:2] in ('dd', 'ee')
    secret16 = (raw[1:17] if sec_hex[:2] in ('dd', 'ee') else raw[:16])

    r, w = await asyncio.open_connection(host, port)
    try:
        if fake_tls:
            domain = raw[17:].decode('ascii', 'ignore') or 'www.cloudflare.com'
            w.write(_client_hello(secret16, domain))
            await w.drain()
            for _ in range(16):              # ServerHello / CCS / ApplicationData
                t, _p = await _read_record(r)
                if t == 0x17:
                    break
                if t == 0x15:
                    raise IOError('TLS alert')
            else:
                raise IOError('server flight not understood')
            w.write(b'\x14\x03\x03\x00\x01\x01')
            io = _Tls(r, w)
        else:
            io = _Plain(r, w)

        obf = _Obf2(secret16, TAG_PADDED if padded else TAG_INTERMEDIATE)
        io.write(obf.frame)
        msg, nonce = _req_pq_multi()
        pad = os.urandom(os.urandom(1)[0] % 16) if padded else b''
        io.write(obf.enc.update(struct.pack('<I', len(msg) + len(pad)) + msg + pad))
        await w.drain()

        n = struct.unpack('<i', obf.dec.update(await io.readexactly(4)))[0]
        if not 0 < n <= 1 << 16:
            raise IOError(f'bad frame length {n}')
        _check_res_pq(obf.dec.update(await io.readexactly(n)), nonce)
    finally:
        w.close()


async def check(host, port, secret, timeout=6.0):
    """Milliseconds until a valid resPQ came back, or None."""
    sec = secret_hex(secret)
    if not sec or len(sec) < 32:
        return None
    t0 = time.perf_counter()
    try:
        await asyncio.wait_for(_probe(host, port, sec), timeout)
    except Exception:
        return None
    return (time.perf_counter() - t0) * 1000
