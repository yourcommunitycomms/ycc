"""Small QR code maker (byte mode, error correction M, versions 1-20) with SVG output. No add-ons.

Follows ISO/IEC 18004; the layout steps mirror the well-known public-domain descriptions of it.
"""

# Per version (index 1-20): error correction codewords per block, and number of blocks, for level M.
ECC_PER_BLOCK = (None, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26)
BLOCKS = (None, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16)
ECL_BITS = 0  # M


def _raw_modules(ver):
    n = (16 * ver + 128) * ver + 64
    if ver >= 2:
        k = ver // 7 + 2
        n -= (25 * k - 10) * k - 55
        if ver >= 7:
            n -= 36
    return n


def _data_codewords(ver):
    return _raw_modules(ver) // 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]


def _gf_mul(x, y):
    z = 0
    for i in range(7, -1, -1):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z & 0xFF


def _rs_divisor(degree):
    res = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            res[j] = _gf_mul(res[j], root)
            if j + 1 < degree:
                res[j] ^= res[j + 1]
        root = _gf_mul(root, 2)
    return res


def _rs_remainder(data, div):
    res = [0] * len(div)
    for b in data:
        f = b ^ res.pop(0)
        res.append(0)
        for i, c in enumerate(div):
            res[i] ^= _gf_mul(c, f)
    return res


def _codewords(data):
    """(version, interleaved codewords) for the given bytes."""
    for ver in range(1, 21):
        cap = _data_codewords(ver)
        count_bits = 8 if ver < 10 else 16
        if 4 + count_bits + 8 * len(data) <= cap * 8:
            break
    else:
        raise ValueError("too much text for a QR code (%d bytes)" % len(data))
    bits = [0, 1, 0, 0] + [(len(data) >> i) & 1 for i in range(count_bits - 1, -1, -1)]
    for b in data:
        bits += [(b >> i) & 1 for i in range(7, -1, -1)]
    bits += [0] * min(4, cap * 8 - len(bits))
    bits += [0] * (-len(bits) % 8)
    words = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = 0xEC
    while len(words) < cap:
        words.append(pad)
        pad ^= 0xEC ^ 0x11

    nblocks, ecc = BLOCKS[ver], ECC_PER_BLOCK[ver]
    raw = _raw_modules(ver) // 8
    nshort = nblocks - raw % nblocks
    short_len = raw // nblocks
    div = _rs_divisor(ecc)
    blocks, k = [], 0
    for i in range(nblocks):
        n = short_len - ecc + (0 if i < nshort else 1)
        dat = words[k:k + n]
        k += n
        e = _rs_remainder(dat, div)
        if i < nshort:
            dat.append(0)
        blocks.append(dat + e)
    out = []
    for i in range(len(blocks[0])):
        for j, blk in enumerate(blocks):
            if i != short_len - ecc or j >= nshort:
                out.append(blk[i])
    return ver, out


def _mask(m, x, y):
    return (((x + y) % 2 == 0), (y % 2 == 0), (x % 3 == 0), ((x + y) % 3 == 0),
            ((x // 3 + y // 2) % 2 == 0), (x * y % 2 + x * y % 3 == 0),
            ((x * y % 2 + x * y % 3) % 2 == 0), (((x + y) % 2 + x * y % 3) % 2 == 0))[m]


class _Grid:
    def __init__(self, ver):
        self.ver = ver
        self.size = n = ver * 4 + 17
        self.dark = [[False] * n for _ in range(n)]
        self.func = [[False] * n for _ in range(n)]

    def set(self, x, y, v):
        self.dark[y][x] = bool(v)
        self.func[y][x] = True

    def functions(self):
        n = self.size
        for i in range(n):
            self.set(6, i, i % 2 == 0)
            self.set(i, 6, i % 2 == 0)
        for cx, cy in ((3, 3), (n - 4, 3), (3, n - 4)):
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    x, y = cx + dx, cy + dy
                    if 0 <= x < n and 0 <= y < n:
                        self.set(x, y, max(abs(dx), abs(dy)) not in (2, 4))
        if self.ver >= 2:
            k = self.ver // 7 + 2
            step = (self.ver * 8 + k * 3 + 5) // (k * 4 - 4) * 2
            pos = [6] + [n - 7 - i * step for i in range(k - 1)][::-1]
            for i, a in enumerate(pos):
                for j, b in enumerate(pos):
                    if (i, j) in ((0, 0), (0, k - 1), (k - 1, 0)):
                        continue
                    for dy in range(-2, 3):
                        for dx in range(-2, 3):
                            self.set(a + dx, b + dy, max(abs(dx), abs(dy)) != 1)
        self.format_bits(0)
        if self.ver >= 7:
            rem = self.ver
            for _ in range(12):
                rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
            bits = self.ver << 12 | rem
            for i in range(18):
                v = (bits >> i) & 1
                a, b = n - 11 + i % 3, i // 3
                self.set(a, b, v)
                self.set(b, a, v)

    def format_bits(self, mask):
        n = self.size
        data = ECL_BITS << 3 | mask
        rem = data
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = (data << 10 | rem) ^ 0x5412
        bit = lambda i: (bits >> i) & 1
        for i in range(6):
            self.set(8, i, bit(i))
        self.set(8, 7, bit(6))
        self.set(8, 8, bit(7))
        self.set(7, 8, bit(8))
        for i in range(9, 15):
            self.set(14 - i, 8, bit(i))
        for i in range(8):
            self.set(n - 1 - i, 8, bit(i))
        for i in range(8, 15):
            self.set(8, n - 15 + i, bit(i))
        self.set(8, n - 8, 1)

    def codewords(self, words):
        n, i, total = self.size, 0, len(words) * 8
        right = n - 1
        while right >= 1:
            if right == 6:
                right = 5
            for vert in range(n):
                for j in range(2):
                    x = right - j
                    y = n - 1 - vert if ((right + 1) & 2) == 0 else vert
                    if not self.func[y][x] and i < total:
                        self.dark[y][x] = bool((words[i >> 3] >> (7 - (i & 7))) & 1)
                        i += 1
            right -= 2

    def apply_mask(self, m):
        for y in range(self.size):
            for x in range(self.size):
                if not self.func[y][x] and _mask(m, x, y):
                    self.dark[y][x] = not self.dark[y][x]

    def penalty(self):
        n, d, p = self.size, self.dark, 0
        for lines in (d, [list(c) for c in zip(*d)]):
            for row in lines:
                run = 1
                for i in range(1, n + 1):
                    if i < n and row[i] == row[i - 1]:
                        run += 1
                    else:
                        if run >= 5:
                            p += run - 2
                        run = 1
        for y in range(n - 1):
            for x in range(n - 1):
                if d[y][x] == d[y][x + 1] == d[y + 1][x] == d[y + 1][x + 1]:
                    p += 3
        darks = sum(map(sum, d))
        p += abs(darks * 20 - n * n * 10) // (n * n) * 10
        return p


def make(text):
    """Rows of booleans (True = dark) for the QR code of text."""
    ver, words = _codewords(text.encode("utf-8"))
    best = None
    for m in range(8):
        g = _Grid(ver)
        g.functions()
        g.codewords(words)
        g.apply_mask(m)
        g.format_bits(m)
        score = g.penalty()
        if best is None or score < best[0]:
            best = (score, g)
    return best[1].dark


def svg(text, scale=6, border=4):
    rows = make(text)
    n = len(rows) + border * 2
    path = "".join("M%d,%dh1v1h-1z" % (x + border, y + border)
                   for y, row in enumerate(rows) for x, v in enumerate(row) if v)
    return ("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 %d %d' width='%d' height='%d' "
            "shape-rendering='crispEdges'><rect width='100%%' height='100%%' fill='#fff'/>"
            "<path d='%s' fill='#000'/></svg>" % (n, n, n * scale, n * scale, path))
