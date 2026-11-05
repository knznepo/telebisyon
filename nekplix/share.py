#!/usr/bin/env python3
"""Shares a folder of movies and series with NEKPLIX on the TVs on the same Wi-Fi.

    python3 share.py ~/Movies              the folder with Movies and Series in it (or one of them)
    python3 share.py ~/Movies --name Den   the name the TV shows (default: this computer's name)

NEKPLIX finds it by itself and shows what's in it next to USB drives, in the same layout (see
docs/library.md). Keep this running while you watch; Ctrl+C stops sharing. Only the folder given is
shared, read-only, and every request needs a code that only NEKPLIX is told. On a Mac it keeps the
computer awake while a video is playing, and lets it sleep otherwise.

Standard Python 3 only, nothing to install.
"""
import argparse
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
TEXT = {"json", "srt"}  # sent with the folder list, so NEKPLIX reads the library in one request
MOVIES, SERIES = ("movies", "films"), ("series", "tv shows", "shows")


def computer_name():
    if platform.system() == "Darwin":
        try:
            return subprocess.run(["scutil", "--get", "ComputerName"], capture_output=True, text=True, timeout=3).stdout.strip()
        except Exception:
            pass
    return socket.gethostname().split(".")[0]


def saved_code():
    """The code NEKPLIX must send, kept in ~/.nekplix-share so it stays the same between runs."""
    path = os.path.expanduser("~/.nekplix-share")
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
    server_version = "NEKPLIX-share/1"
    tops, code, name, awake = {}, "", "", None

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
        given = urllib.parse.parse_qs(url.query).get("code", [""])[0]
        if not secrets.compare_digest(given, self.code):
            return self.send_error(403, "Wrong code")
        path = urllib.parse.unquote(url.path)
        if path == "/tree":
            return self.tree()
        if path.startswith("/file/"):
            return self.file(path[len("/file/"):])
        self.send_error(404)

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
    """Answers NEKPLIX's "nekplix-discover" broadcasts with where to find this share."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", DISCOVER_PORT))
    reply = json.dumps({"name": name, "port": port, "code": code, "version": 1}).encode("utf-8")
    while True:
        data, addr = s.recvfrom(256)
        if data.strip() == b"nekplix-discover":
            s.sendto(reply, addr)


def main():
    ap = argparse.ArgumentParser(description="Share a folder of movies and series with NEKPLIX on the TV.")
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
    server = ThreadingHTTPServer(("", args.port), Share)
    threading.Thread(target=answer_discovery, args=(Share.name, args.port, Share.code), daemon=True).start()

    print("Sharing with NEKPLIX as \"%s\":" % Share.name)
    for top, path in shared.items():
        print("  %-7s %s" % (top, path))
    print("NEKPLIX on the TV finds it by itself (it looks every 30 seconds while it's open).")
    if platform.system() == "Darwin":
        print("If macOS asks to allow incoming connections for Python, click Allow.")
    print("Keep this running while you watch. Ctrl+C stops sharing.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped sharing.")


if __name__ == "__main__":
    main()
