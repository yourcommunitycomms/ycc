"""The app's channel crypto in plain Python (no add-ons): PBKDF2 keys, AES-256-CTR, 10-byte HMAC.

Same as ChannelCrypto.java and docs/PROTOCOL.md section 1. Uses the 'cryptography' package for AES
when it is installed (faster); otherwise a small pure-Python AES, plenty for a few voice streams.
"""
import hashlib
import hmac

SALT = b"communitycomms/v1"
ITERATIONS = 20000
MAC_LEN = 10

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

    def _ctr(key, iv, data):
        e = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
        return e.update(data) + e.finalize()
except ImportError:  # pure Python AES (encrypt direction only, which is all CTR needs)
    _SBOX = bytes.fromhex(
        "637c777bf26b6fc53001672bfed7ab76ca82c97dfa5947f0add4a2af9ca472c0b7fd9326363ff7cc34a5e5f171d8311504c723c3"
        "1896059a071280e2eb27b27509832c1a1b6e5aa0523bd6b329e32f8453d100ed20fcb15b6acbbe394a4c58cfd0efaafb434d3385"
        "45f9027f503c9fa851a3408f929d38f5bcb6da2110fff3d2cd0c13ec5f974417c4a77e3d645d197360814fdc222a908846eeb814"
        "de5e0bdbe0323a0a4906245cc2d3ac629195e479e7c8376d8dd54ea96c56f4ea657aae08ba78252e1ca6b4c6e8dd741f4bbd8b8a"
        "703eb5664803f60e613557b986c11d9ee1f8981169d98e949b1e87e9ce5528df8ca1890dbfe6426841992d0fb054bb16")
    _RCON = (1, 2, 4, 8, 16, 32, 64, 128, 27, 54)

    def _xt(a):
        return ((a << 1) ^ 0x1B) & 0xFF if a & 0x80 else a << 1

    def _expand(key):
        w = [list(key[i:i + 4]) for i in range(0, 32, 4)]
        for i in range(8, 60):
            t = list(w[i - 1])
            if i % 8 == 0:
                t = [_SBOX[b] for b in t[1:] + t[:1]]
                t[0] ^= _RCON[i // 8 - 1]
            elif i % 8 == 4:
                t = [_SBOX[b] for b in t]
            w.append([a ^ b for a, b in zip(w[i - 8], t)])
        return [sum(w[r * 4:r * 4 + 4], []) for r in range(15)]

    def _block(rk, s):
        s = [a ^ b for a, b in zip(s, rk[0])]
        for r in range(1, 15):
            s = [_SBOX[b] for b in s]
            s = [s[(i + 4 * (i % 4)) % 16] for i in range(16)]  # ShiftRows (column-major state)
            if r < 14:
                m = []
                for c in range(4):
                    a = s[4 * c:4 * c + 4]
                    t = a[0] ^ a[1] ^ a[2] ^ a[3]
                    m += [a[i] ^ t ^ _xt(a[i] ^ a[(i + 1) % 4]) for i in range(4)]
                s = m
            s = [a ^ b for a, b in zip(s, rk[r])]
        return bytes(s)

    _cache = {}

    def _ctr(key, iv, data):
        rk = _cache.get(key)
        if rk is None:
            rk = _cache[key] = _expand(key)
        ctr = int.from_bytes(iv, "big")
        out = bytearray()
        for i in range(0, len(data), 16):
            ks = _block(rk, list((ctr & ((1 << 128) - 1)).to_bytes(16, "big")))
            out += bytes(a ^ b for a, b in zip(data[i:i + 16], ks))
            ctr += 1
        return bytes(out)


class Keys:
    """Keys for a channel name (or "tunnel:" + a DRYAD row)."""

    def __init__(self, name):
        okm = hashlib.pbkdf2_hmac("sha256", name.strip().encode("utf-8"), SALT, ITERATIONS, 64)
        self.enc, self.mac_key = okm[:32], okm[32:]
        self.tag = int.from_bytes(hmac.new(self.mac_key, b"channel-tag", hashlib.sha256).digest()[:4], "big")

    def ctr(self, iv, data):
        return _ctr(self.enc, iv, data)

    def mac(self, data):
        return hmac.new(self.mac_key, data, hashlib.sha256).digest()[:MAC_LEN]

    def verify(self, data, tag):
        return hmac.compare_digest(self.mac(data), tag)


def self_test():
    """Checks against the protocol's test vector (raises AssertionError if anything is off)."""
    k = Keys("publicchannelkey")
    assert k.enc.hex() == "59c8612e1eb6c613ee8113af0d18f81803e8189f3f9804cbc33ca9eace12c962"
    assert k.tag == 0xF9929A84
    pkt = bytes.fromhex("55420103f9929a840102030405060708000000012d3d7d76c1577acddbfc72bd822f291f")
    head, ct, tag = pkt[:20], pkt[20:-MAC_LEN], pkt[-MAC_LEN:]
    assert k.verify(pkt[:-MAC_LEN], tag)
    assert k.ctr(pkt[8:20] + b"\0\0\0\0", ct) == b"\x05ALPHA"
    return True


if __name__ == "__main__":
    print("crypto self test:", self_test())
