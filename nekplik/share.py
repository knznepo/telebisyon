#!/usr/bin/env python3
"""Shares a folder of movies and series with NEKPLIK on the TVs on the same Wi-Fi.

    python3 share.py ~/Movies              the folder with Movies and Series in it (or one of them)
    python3 share.py ~/Movies --name Den   the name the TV shows (default: this computer's name)

NEKPLIK finds it by itself and shows what's in it next to USB drives, in the same layout (see
docs/library.md). Keep this running while you watch; Ctrl+C stops sharing. Only the folder given is
shared, read-only. On a Mac it keeps the computer awake while a video is playing, and lets it sleep
otherwise.

It also serves NEKPLIK to any browser on the same Wi-Fi, at the address it prints: the same page as
NEKPLIK Share for Android. Pictures are made small for it (with macOS's sips, or Pillow if it's
installed), and with ffprobe (from ffmpeg) it finds which videos each browser can play.

Standard Python 3 only, nothing to install.
"""
import argparse
from html import escape as html_escape
import hashlib
import base64
import json
import mimetypes
import os
import platform
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HTTP_PORT, DISCOVER_PORT = 8765, 8766
VIDEO = {"mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "3gp"}
TEXT = {"json", "srt"}  # sent with the folder list, so NEKPLIK reads the library in one request
MOVIES, SERIES = ("movies", "films"), ("series", "tv shows", "shows")


def computer_name():
    if platform.system() == "Darwin":
        try:
            return subprocess.run(["scutil", "--get", "ComputerName"], capture_output=True, text=True, timeout=3).stdout.strip()
        except Exception:
            pass
    return socket.gethostname().split(".")[0]


def saved_code():
    """The code NEKPLIK must send, kept in ~/.nekplik-share so it stays the same between runs."""
    path = os.path.expanduser("~/.nekplik-share")
    try:
        with open(path) as f:
            code = f.read().strip()
        if len(code) >= 16:
            return code
    except OSError:
        pass
    code = secrets.token_urlsafe(18)
    with open(path, "w") as f:
        f.write(code)
    os.chmod(path, 0o600)
    return code


def tops(folder):
    """What the TV sees at the top, Movies and Series, and where each really is. The folder given can
    hold them (looked for first, so a drive itself named "Movies" works), or be one of them."""
    out = {}
    for entry in sorted(os.listdir(folder)):
        full = os.path.join(folder, entry)
        if os.path.isdir(full) and entry.lower() in MOVIES + SERIES:
            out[entry] = full
    if out:
        return out
    name = os.path.basename(os.path.normpath(folder)).lower()
    if name in MOVIES:
        return {"Movies": folder}
    if name in SERIES:
        return {"Series": folder}
    return out


HERE = os.path.dirname(os.path.abspath(__file__))


def web_file(name):
    """The browser version's page and pictures: next to share.py in the Mac app (web/), else where they
    are in the NEKPLIK folder."""
    where = {
        "web.html": ["web/web.html", "share-android/res/raw/web.html"],
        "logo.png": ["web/logo.png", "res/drawable-xhdpi/logo.png"],
        "icon.png": ["web/icon.png", "res/drawable-xxxhdpi/icon.png"],
    }[name]
    for rel in where:
        full = os.path.join(HERE, rel)
        if os.path.isfile(full):
            return full
    return None


def lan_address():
    """This computer's address on the Wi-Fi: the one it would use to reach the internet (nothing is sent)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "127.0.0.1"


def find_tool(name):
    """A program, also where Homebrew puts it: the Mac app doesn't get the terminal's PATH."""
    return shutil.which(name) or next((p for p in ("/opt/homebrew/bin/" + name, "/usr/local/bin/" + name) if os.path.exists(p)), None)


class Codecs:
    """Each video's first video and audio track, as Android names them ("video/avc", "audio/mp4a-latm"),
    for the browser version to mark what a browser can't play. Read once per file with ffprobe, in
    the background, and kept in ~/.nekplik-share-codecs.json. Without ffprobe, nothing: the page then
    goes by the file's type."""
    VIDEO = {"h264": "video/avc", "hevc": "video/hevc", "mpeg4": "video/mp4v-es", "vp8": "video/x-vnd.on2.vp8",
             "vp9": "video/x-vnd.on2.vp9", "av1": "video/av01", "mpeg2video": "video/mpeg2", "msmpeg4v3": "video/divx"}
    AUDIO = {"aac": "audio/mp4a-latm", "mp3": "audio/mpeg", "ac3": "audio/ac3", "eac3": "audio/eac3", "opus": "audio/opus",
             "vorbis": "audio/vorbis", "flac": "audio/flac", "dts": "audio/dts", "truehd": "audio/true-hd"}

    def __init__(self):
        self.path = os.path.expanduser("~/.nekplik-share-codecs.json")
        self.ffprobe = find_tool("ffprobe")
        self.lock = threading.Lock()
        try:
            with open(self.path) as f:
                self.known = json.load(f)
        except (OSError, ValueError):
            self.known = {}

    @staticmethod
    def key(full, st):
        return "%s|%d|%d" % (full, st.st_size, int(st.st_mtime))

    def get(self, full, st):
        return self.known.get(self.key(full, st))

    def read_all(self, tops):
        """Every video not read yet, then saved."""
        if not self.ffprobe:
            return
        todo = []
        for root in tops.values():
            for here, dirs, files in os.walk(root):
                dirs[:] = [d for d in dirs if not d.startswith(".")]
                for f in files:
                    full = os.path.join(here, f)
                    if f.startswith(".") or f.rsplit(".", 1)[-1].lower() not in VIDEO:
                        continue
                    try:
                        st = os.stat(full)
                    except OSError:
                        continue
                    if self.key(full, st) not in self.known:
                        todo.append((full, st))
        for n, (full, st) in enumerate(todo):
            self.known[self.key(full, st)] = self.probe(full)
            if n % 10 == 9:
                self.save()
        if todo:
            self.save()

    def probe(self, full):
        try:
            out = subprocess.run([self.ffprobe, "-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "json", full],
                                 capture_output=True, text=True, timeout=30).stdout
            v = a = None
            for st in json.loads(out).get("streams", []):
                if st.get("codec_type") == "video" and v is None:
                    v = self.VIDEO.get(st.get("codec_name"), "video/" + str(st.get("codec_name")))
                elif st.get("codec_type") == "audio" and a is None:
                    a = self.AUDIO.get(st.get("codec_name"), "audio/" + str(st.get("codec_name")))
            return [v or "?", a or ""]
        except Exception:
            return ["?", "?"]

    def save(self):
        with self.lock:
            try:
                with open(self.path, "w") as f:
                    json.dump(self.known, f)
            except OSError:
                pass


class Pictures:
    """Posters and backgrounds made small for the browser version and the TV, kept in a cache folder:
    with sips on a Mac, Pillow elsewhere if it's there, else the picture as it is. Three at a time."""

    def __init__(self):
        self.dir = os.path.expanduser("~/Library/Caches/nekplik-share" if platform.system() == "Darwin" else "~/.cache/nekplik-share")
        os.makedirs(self.dir, exist_ok=True)
        self.sips = find_tool("sips")
        self.slots = threading.Semaphore(3)
        try:
            from PIL import Image  # noqa: F401
            self.pillow = True
        except ImportError:
            self.pillow = False

    def small(self, full, width):
        """The path of the small picture, or the original if it can't be made."""
        w = max(160, min(1920, (width + 159) // 160 * 160))
        st = os.stat(full)
        out = os.path.join(self.dir, hashlib.sha1(("%s|%d|%d|%d" % (full, st.st_size, int(st.st_mtime), w)).encode()).hexdigest() + ".jpg")
        if os.path.isfile(out):
            return out
        with self.slots:
            if os.path.isfile(out):
                return out
            part = out + ".part.jpg"
            try:
                if self.sips:
                    got = subprocess.run([self.sips, "-g", "pixelWidth", full], capture_output=True, text=True, timeout=30).stdout
                    have = int(got.rsplit(":", 1)[-1]) if ":" in got else 0
                    if have and have <= w:  # already small enough: as it is
                        return full
                    subprocess.run([self.sips, "--resampleWidth", str(w), "-s", "format", "jpeg", "-s", "formatOptions", "82", full, "--out", part],
                                   capture_output=True, timeout=30, check=True)
                elif self.pillow:
                    from PIL import Image
                    im = Image.open(full).convert("RGB")
                    if im.width > w:
                        im = im.resize((w, max(1, im.height * w // im.width)))
                    im.save(part, "JPEG", quality=82)
                else:
                    return full
                os.replace(part, out)
                return out
            except Exception:
                try:
                    os.remove(part)
                except OSError:
                    pass
                return full


class QR:
    """A QR code for a short link, to scan with a phone's camera: the same as NEKPLIK Share for
    Android's QrCode.java (byte mode, medium error correction, versions 1 to 6, one fixed mask)."""
    ECC = [0, 10, 16, 26, 18, 24, 16]
    BLOCKS = [0, 1, 1, 1, 2, 2, 4]

    @staticmethod
    def raw(ver):
        r = (16 * ver + 128) * ver + 64
        if ver >= 2:
            a = ver // 7 + 2
            r -= (25 * a - 10) * a - 55
        return r

    @classmethod
    def encode(cls, text):
        data = text.encode("utf-8")
        for ver in range(1, 7):
            cap = cls.raw(ver) // 8 - cls.ECC[ver] * cls.BLOCKS[ver]
            if 4 + 8 + len(data) * 8 <= cap * 8:
                return cls.draw(ver, cls.codewords(data, ver, cap))
        raise ValueError("too long for a QR code here")

    @classmethod
    def codewords(cls, text, ver, cap):
        bits = []
        add = lambda v, n: bits.extend((v >> i) & 1 for i in range(n - 1, -1, -1))
        add(4, 4)
        add(len(text), 8)
        for b in text:
            add(b, 8)
        add(0, min(4, cap * 8 - len(bits)))
        add(0, (8 - len(bits) % 8) % 8)
        pad = 0xEC
        while len(bits) < cap * 8:
            add(pad, 8)
            pad ^= 0xEC ^ 0x11
        data = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
        blocks, ecc_len, raw = cls.BLOCKS[ver], cls.ECC[ver], cls.raw(ver) // 8
        short_blocks, short_len = blocks - raw % blocks, raw // blocks
        div = cls.divisor(ecc_len)
        allb, k = [], 0
        for i in range(blocks):
            n = short_len - ecc_len + (0 if i < short_blocks else 1)
            dat = data[k:k + n]
            k += n
            block = dat + [0] * (short_len + 1 - len(dat))
            ecc = cls.remainder(dat, div)
            block[len(block) - ecc_len:] = ecc
            allb.append(block)
        out = []
        for i in range(len(allb[0])):
            for j in range(len(allb)):
                if i != short_len - ecc_len or j >= short_blocks:
                    out.append(allb[j][i])
        return out

    @staticmethod
    def mul(x, y):
        z = 0
        for i in range(7, -1, -1):
            z = ((z << 1) ^ ((z >> 7) * 0x11D)) & 0x1FF
            z ^= ((y >> i) & 1) * x
        return z & 0xFF

    @classmethod
    def divisor(cls, degree):
        r = [0] * degree
        r[-1] = 1
        root = 1
        for _ in range(degree):
            for j in range(degree):
                r[j] = cls.mul(r[j], root)
                if j + 1 < degree:
                    r[j] ^= r[j + 1]
            root = cls.mul(root, 2)
        return r

    @classmethod
    def remainder(cls, data, div):
        r = [0] * len(div)
        for b in data:
            f = b ^ r[0]
            r = r[1:] + [0]
            for i in range(len(r)):
                r[i] ^= cls.mul(div[i], f)
        return r

    @staticmethod
    def draw(ver, data):
        size = ver * 4 + 17
        dark = [[False] * size for _ in range(size)]
        fixed = [[False] * size for _ in range(size)]

        def put(x, y, on):
            dark[y][x] = on
            fixed[y][x] = True
        for i in range(size):
            put(6, i, i % 2 == 0)
            put(i, 6, i % 2 == 0)
        for cx, cy in ((3, 3), (size - 4, 3), (3, size - 4)):
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    x, y, d = cx + dx, cy + dy, max(abs(dx), abs(dy))
                    if 0 <= x < size and 0 <= y < size:
                        put(x, y, d != 2 and d != 4)
        if ver >= 2:
            p = size - 7
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    put(p + dx, p + dy, max(abs(dx), abs(dy)) != 1)
        fmt = 0  # medium error correction (00), mask 0
        rem = fmt
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = (fmt << 10 | rem) ^ 0x5412
        bit = lambda i: (bits >> i) & 1 == 1
        for i in range(6):
            put(8, i, bit(i))
        put(8, 7, bit(6))
        put(8, 8, bit(7))
        put(7, 8, bit(8))
        for i in range(9, 15):
            put(14 - i, 8, bit(i))
        for i in range(8):
            put(size - 1 - i, 8, bit(i))
        for i in range(8, 15):
            put(8, size - 15 + i, bit(i))
        put(8, size - 8, True)
        i, right = 0, size - 1
        while right >= 1:
            if right == 6:
                right = 5
            for vert in range(size):
                for j in range(2):
                    x = right - j
                    up = ((right + 1) & 2) == 0
                    y = size - 1 - vert if up else vert
                    if not fixed[y][x] and i < len(data) * 8:
                        dark[y][x] = (data[i >> 3] >> (7 - (i & 7))) & 1 == 1
                        i += 1
            right -= 2
        for y in range(size):
            for x in range(size):
                if not fixed[y][x] and (x + y) % 2 == 0:
                    dark[y][x] = not dark[y][x]
        return dark

    @classmethod
    def svg(cls, text):
        m = cls.encode(text)
        n, q = len(m), 4
        cells = "".join("M%d %dh1v1h-1z" % (x + q, y + q) for y in range(n) for x in range(n) if m[y][x])
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges">'
                '<rect width="100%%" height="100%%" fill="#fff"/><path d="%s" fill="#000"/></svg>' % (n + 2 * q, n + 2 * q, cells))


class Awake:
    """Keeps a Mac from sleeping while a video is being sent, and for two minutes after (caffeinate)."""

    def __init__(self):
        self.until = 0
        self.proc = None
        self.lock = threading.Lock()
        self.ok = platform.system() == "Darwin" and shutil.which("caffeinate") is not None

    def poke(self):
        if not self.ok:
            return
        with self.lock:
            now = time.time()
            if self.proc is None or self.proc.poll() is not None or now > self.until - 60:
                self.until = now + 120
                self.proc = subprocess.Popen(["caffeinate", "-i", "-t", "120"])


class Share(BaseHTTPRequestHandler):
    server_version = "NEKPLIK-share/1"
    tops, code, name, awake, codecs, pictures, web = {}, "", "", None, None, None, ""

    def log_message(self, fmt, *args):  # quiet: only what was played (below)
        pass

    def real(self, path):
        """The real file for a path the TV asked for, or None if it's outside the share or hidden."""
        parts = [p for p in path.split("/") if p]
        if not parts or parts[0] not in self.tops or any(p.startswith(".") or p == ".." for p in parts):
            return None
        root = os.path.realpath(self.tops[parts[0]])
        full = os.path.realpath(os.path.join(root, *parts[1:]))
        return full if full == root or full.startswith(root + os.sep) else None

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(url.path)
        query = urllib.parse.parse_qs(url.query)
        # The browser version: the page, its pictures, and the share's name and code. Open to the Wi-Fi,
        # as with NEKPLIK Share for Android: read-only, and only Movies and Series.
        if path in ("/", "/index.html"):
            return self.static("web.html", "text/html; charset=utf-8")
        if path in ("/logo.png", "/icon.png", "/favicon.ico"):
            return self.static("logo.png" if path == "/logo.png" else "icon.png", "image/png", cache=True)
        if path == "/qr":
            return self.qr_page()
        if path == "/api/drives":
            return self.json({"name": self.name, "drives": [{"name": self.name, "code": self.code}]})
        given = query.get("code", [""])[0]
        if not secrets.compare_digest(given, self.code):
            return self.send_error(403, "Wrong code")
        if path == "/tree":
            return self.tree()
        if path.startswith("/file/"):
            return self.file(path[len("/file/"):])
        if path.startswith("/img/"):
            try:
                width = int(query.get("w", ["400"])[0])
            except ValueError:
                width = 400
            return self.image(path[len("/img/"):], width)
        self.send_error(404)

    def qr_page(self):
        """The browser version's link, big, and as a QR code to scan with a phone: for showing on this
        computer's screen (the Mac app's Show QR code)."""
        link = self.web or "http://%s:%d" % (lan_address(), self.server.server_address[1])
        page = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>NEKPLIK · Scan to watch</title>
<link rel="icon" href="/icon.png"><style>
  html, body { margin: 0; min-height: 100%%; background: #0b0809; color: #fff; font: 18px/1.4 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif; }
  main { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 22px; padding: 32px; text-align: center; }
  img.logo { height: 40px; }
  h1 { margin: 0; font-size: 26px; font-weight: 650; }
  p { margin: 0; color: rgba(255,255,255,.7); }
  .code { width: min(340px, 78vw); padding: 18px; background: #fff; border-radius: 18px; box-shadow: 0 20px 60px rgba(0,0,0,.6); }
  .code svg { display: block; width: 100%%; height: auto; }
  .link { font-size: 26px; font-weight: 700; letter-spacing: .01em; user-select: all; }
  a.btn { display: inline-block; margin-top: 4px; padding: 12px 26px; border-radius: 6px; background: #fff; color: #000; font-weight: 700; text-decoration: none; }
</style></head><body><main>
  <img class="logo" src="/logo.png" alt="NEKPLIK">
  <h1>Scan to watch on your phone</h1>
  <div class="code">%s</div>
  <div class="link">%s</div>
  <p>Any phone, tablet or computer on the same Wi-Fi as %s</p>
  <a class="btn" href="/">Open NEKPLIK here</a>
</main></body></html>""" % (QR.svg(link), html_escape(link), html_escape(self.name))
        body = page.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def static(self, name, kind, cache=False):
        full = web_file(name)
        if not full:
            return self.send_error(404, "The browser version's files aren't next to share.py")
        with open(full, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        # The logo and icons: checked again each time (no-cache), so a new logo shows straight away.
        self.send_header("Cache-Control", "no-cache" if cache else "no-store")
        self.end_headers()
        self.wfile.write(body)

    def json(self, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def image(self, path, width):
        """A poster or background made small (see Pictures)."""
        full = self.real(path)
        if not full or not os.path.isfile(full) or full.rsplit(".", 1)[-1].lower() not in ("jpg", "jpeg", "png", "webp"):
            return self.send_error(404)
        small = self.pictures.small(full, width)
        with open(small, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg" if small != full else mimetypes.guess_type(full)[0] or "image/jpeg")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "max-age=604800")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def tree(self):
        """Every folder and file under Movies and Series (four levels down at most), with the small
        text files' contents."""
        entries = []
        for top, root in self.tops.items():
            entries.append({"path": top, "dir": True})
            base = len(os.path.normpath(root).split(os.sep))
            for here, dirs, files in os.walk(root):
                depth = len(os.path.normpath(here).split(os.sep)) - base
                dirs[:] = sorted(d for d in dirs if not d.startswith(".")) if depth < 3 else []
                rel = os.path.relpath(here, root)
                prefix = top if rel == "." else top + "/" + rel.replace(os.sep, "/")
                for d in dirs:
                    entries.append({"path": prefix + "/" + d, "dir": True, "mtime": int(os.path.getmtime(os.path.join(here, d)))})
                for f in sorted(files):
                    if f.startswith("."):
                        continue  # hidden, and the ._ files a Mac leaves on exFAT drives
                    full = os.path.join(here, f)
                    try:
                        st = os.stat(full)
                    except OSError:
                        continue
                    e = {"path": prefix + "/" + f, "size": st.st_size, "mtime": int(st.st_mtime)}
                    known = self.codecs.get(full, st) if self.codecs else None
                    if known:
                        e["v"], e["a"] = known
                    if f.rsplit(".", 1)[-1].lower() in TEXT and st.st_size <= 1024 * 1024:
                        with open(full, "rb") as fh:
                            e["b64"] = base64.b64encode(fh.read()).decode("ascii")
                    entries.append(e)
        body = json.dumps({"name": self.name, "entries": entries}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def file(self, path):
        """A file, or the part of it asked for (Range), so the TV's player can skip around."""
        full = self.real(path)
        if not full or not os.path.isfile(full):
            return self.send_error(404)
        size = os.path.getsize(full)
        start, end = 0, size - 1
        rng = self.headers.get("Range", "")
        if rng.startswith("bytes="):
            a, _, b = rng[6:].split(",")[0].partition("-")
            try:
                if a:
                    start, end = int(a), int(b) if b else size - 1
                else:
                    start = max(0, size - int(b))
            except ValueError:
                return self.send_error(416)
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                return self.end_headers()
            end = min(end, size - 1)
        video = full.rsplit(".", 1)[-1].lower() in VIDEO
        if video:
            self.awake.poke()
            if start == 0:
                print("Playing on %s: %s" % (self.client_address[0], path))
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", mimetypes.guess_type(full)[0] or "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if rng:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        try:
            with open(full, "rb") as fh:
                fh.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = fh.read(min(1024 * 1024, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
                    if video:
                        self.awake.poke()
        except (BrokenPipeError, ConnectionResetError):
            pass  # the player skipped elsewhere, or stopped


def answer_discovery(name, port, code):
    """Answers NEKPLIK's "nekplik-discover" broadcasts with where to find this share."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", DISCOVER_PORT))
    reply = json.dumps({"name": name, "port": port, "code": code, "version": 1}).encode("utf-8")
    while True:
        data, addr = s.recvfrom(256)
        if data.strip() == b"nekplik-discover":
            s.sendto(reply, addr)


def main():
    ap = argparse.ArgumentParser(description="Share a folder of movies and series with NEKPLIK on the TV.")
    ap.add_argument("folder", help="the folder with Movies and Series in it, or a Movies or Series folder")
    ap.add_argument("--name", default=None, help="the name the TV shows (default: this computer's name)")
    ap.add_argument("--port", type=int, default=HTTP_PORT, help="the port to share on (default %d)" % HTTP_PORT)
    args = ap.parse_args()
    sys.stdout.reconfigure(line_buffering=True)  # each line straight away, even into a log

    folder = os.path.abspath(os.path.expanduser(args.folder))
    if not os.path.isdir(folder):
        raise SystemExit("No folder %s" % folder)
    shared = tops(folder)
    if not shared:
        raise SystemExit("No Movies or Series folder in %s. Put your movies in Movies/<name>/ and series in Series/<name>/." % folder)

    Share.tops, Share.code, Share.name, Share.awake = shared, saved_code(), args.name or computer_name(), Awake()
    Share.codecs, Share.pictures = Codecs(), Pictures()
    server = ThreadingHTTPServer(("", args.port), Share)
    threading.Thread(target=answer_discovery, args=(Share.name, args.port, Share.code), daemon=True).start()
    threading.Thread(target=Share.codecs.read_all, args=(shared,), daemon=True).start()

    print("Sharing with NEKPLIK as \"%s\":" % Share.name)
    for top, path in shared.items():
        print("  %-7s %s" % (top, path))
    print("NEKPLIK on the TV finds it by itself (it looks every 30 seconds while it's open).")
    web = "http://%s:%d" % (lan_address(), args.port)
    Share.web = web
    print("Watch in a browser on the same Wi-Fi: " + web)
    print("  A QR code for it, to scan with a phone: " + web + "/qr")
    if not web_file("web.html"):
        print("  (the browser version's files aren't next to share.py: only the TV can use this share)")
    if not Share.codecs.ffprobe:
        print("  (without ffprobe, from ffmpeg, browsers see MP4 files, and MKV in Chrome and Edge)")
    try:
        with open(os.path.expanduser("~/.nekplik-share.web"), "w") as f:  # for the Mac app to show
            f.write(web)
    except OSError:
        pass
    if platform.system() == "Darwin":
        print("If macOS asks to allow incoming connections for Python, click Allow.")
    print("Keep this running while you watch. Ctrl+C stops sharing.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped sharing.")


if __name__ == "__main__":
    main()
