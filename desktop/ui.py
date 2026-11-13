"""App window: a local page (ui.html) talking to a tiny JSON API on 127.0.0.1."""
import json
import os
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from audioshare import Host, Join

PAGE = Path(__file__).with_name("ui.html").read_bytes()
session = None
lock = threading.Lock()
delay_ms = 0
quit_at = None  # window closed: quit soon unless it comes back (page reload)


def state():
    s = session
    return {
        "mode": None if s is None else ("join" if isinstance(s, Join) else "host"),
        "status": "Choose how this PC joins in" if s is None else s.status,
        "clients": 0 if s is None else s.clients,
        "delay": delay_ms,
    }


def switch(new):
    global session
    with lock:
        if session:
            session.stop()
        session = None
        if new:
            session = new.start()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply(self, code, body=b"", kind="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def local(self):
        # Only our own page may drive the API: blocks DNS rebinding (Host) and cross-site posts (Origin).
        host = self.headers.get("Host", "")
        origin = self.headers.get("Origin")
        return host == self.server.origin_host and origin in (None, "http://" + host)

    def do_GET(self):
        global quit_at
        if not self.local():
            return self.reply(403)
        path = urlparse(self.path).path
        if path == "/":
            return self.reply(200, PAGE, "text/html; charset=utf-8")
        if path == "/state":
            quit_at = None
            return self.reply(200, json.dumps(state()).encode())
        self.reply(404)

    def do_POST(self):
        global delay_ms, quit_at
        if not self.local() or self.headers.get("Origin") is None:
            return self.reply(403)
        u = urlparse(self.path)
        try:
            if u.path == "/join":
                switch(Join(delay_ms))
            elif u.path == "/host":
                switch(Host())
            elif u.path == "/stop":
                switch(None)
            elif u.path == "/delay":
                try:
                    delay_ms = max(0, min(500, int(parse_qs(u.query)["ms"][0])))
                except (KeyError, ValueError):
                    return self.reply(400)
                if isinstance(session, Join):
                    session.delay_ms = delay_ms
            elif u.path == "/quit":
                quit_at = time.monotonic() + 3
            else:
                return self.reply(404)
        except Exception as e:  # e.g. no audio device, port busy: show it instead of dying
            switch(None)
            return self.reply(500, json.dumps({"error": str(e)}).encode())
        self.reply(200, json.dumps(state()).encode())


def open_window(url):
    """Edge/Chrome app mode = a plain window with no tabs or address bar; else the default browser."""
    for exe in (
        os.path.expandvars(r"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"),
        os.path.expandvars(r"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"),
        os.path.expandvars(r"%ProgramFiles%\Google\Chrome\Application\chrome.exe"),
    ):
        if os.path.exists(exe):
            subprocess.Popen([exe, f"--app={url}", "--window-size=440,860"])
            return
    webbrowser.open(url)


def serve():
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.origin_host = f"127.0.0.1:{server.server_port}"
    url = f"http://{server.origin_host}/"
    threading.Thread(target=server.serve_forever, daemon=True).start()
    if sys.stdout:  # None in the windowed .exe
        print(f"AudioShare window: {url}  (Ctrl+C or close the window to quit)")
    open_window(url)
    while True:
        time.sleep(0.5)
        if quit_at and time.monotonic() > quit_at:
            break
    switch(None)
