"""AudioShare for laptops: same sync protocol as the Android app (app/src/main/java/com/audioshare/Sync.kt).

  python audioshare.py join [--delay MS]   play what an AudioShare host on this network shares
  python audioshare.py host                share everything this PC plays (Windows, WASAPI loopback)

Needs: pip install -r requirements.txt
"""
import argparse
import collections
import queue
import socket
import struct
import sys
import threading
import time
import warnings

CONTROL_PORT, BEACON_PORT = 50005, 50006
PING, PONG, AUDIO, BEACON = 1, 2, 3, 4
RATE = 48000
FRAME = 4  # 16-bit stereo
FRAMES_PER_PACKET = 240  # 5 ms
PACKET_BYTES = FRAMES_PER_PACKET * FRAME
PLAYOUT_DELAY_NS = 250_000_000
TOLERANCE_NS = 15_000_000
RESYNC_NS = 100_000_000

now = time.perf_counter_ns  # high-resolution monotonic clock (time.monotonic is ~15 ms on Windows)


def frames_ns(frames):
    return frames * 1_000_000_000 // RATE


def ns_frames(ns):
    return ns * RATE // 1_000_000_000


class Playout:
    """Host-timestamped packets in; the samples due at a given host time out."""

    def __init__(self):
        self.buf = bytearray()
        self.start = 0  # host time of buf[0]

    def add(self, at, pcm):
        gap = at - (self.start + frames_ns(len(self.buf) // FRAME))
        if not self.buf or gap < -TOLERANCE_NS or gap > 1_000_000_000:
            self.buf[:] = pcm  # first packet, host restarted, or long pause: start fresh
            self.start = at
            return
        if gap > 0:
            self.buf += bytes(ns_frames(gap) * FRAME)  # lost packets: silence keeps the timeline
        self.buf += pcm

    def take(self, frames, target):
        """`frames` of audio whose first sample should be heard at host time `target`."""
        lead = 0
        err = target - self.start
        if self.buf and err > TOLERANCE_NS:  # we're behind: skip ahead
            self._consume(min(ns_frames(err), len(self.buf) // FRAME))
        elif self.buf and err < -TOLERANCE_NS:  # we're early: wait with silence
            lead = min(ns_frames(-err), frames)
        n = min(frames - lead, len(self.buf) // FRAME)
        out = bytes(lead * FRAME) + bytes(self.buf[: n * FRAME]) + bytes((frames - lead - n) * FRAME)
        self._consume(n)
        return out

    def _consume(self, n):
        del self.buf[: n * FRAME]
        self.start += frames_ns(n)


def udp(port=0, reuse=False):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if reuse:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", port))
    return s


def thread(fn):
    threading.Thread(target=fn, daemon=True).start()


def key_loop(on_key):
    """Live keys on Windows; elsewhere just wait for Ctrl+C."""
    try:
        import msvcrt
    except ImportError:
        while True:
            time.sleep(1)
    while True:
        if msvcrt.kbhit():
            on_key(msvcrt.getwch())
        time.sleep(0.05)


# ---------------------------------------------------------------- join (laptop = speaker)

class Join:
    def __init__(self, delay_ms):
        self.delay_ms = delay_ms
        self.host = None
        self.offset = None  # host clock minus ours
        self.packets = queue.Queue()
        self.playout = Playout()
        self.latency = 0.1
        self.sock = udp()

    def find_host(self):
        s = udp(BEACON_PORT, reuse=True)
        while True:
            data, (ip, _) = s.recvfrom(16)
            if data[:1] == bytes([BEACON]) and ip != self.host:
                self.host = ip
                print(f"Connected to {ip}")

    def ping(self):
        while True:
            if self.host:
                self.sock.sendto(struct.pack(">bq", PING, now()), (self.host, CONTROL_PORT))
            time.sleep(0.5)

    def receive(self):
        samples = collections.deque(maxlen=16)  # (round trip, offset)
        while True:
            data, (ip, _) = self.sock.recvfrom(2048)
            if ip != self.host:
                continue
            if data[0] == PONG and len(data) == 17:
                t3 = now()
                _, t0, host_time = struct.unpack(">bqq", data)
                samples.append((t3 - t0, host_time - (t0 + t3) // 2))
                self.offset = min(samples)[1]  # fastest round trip = least skewed estimate
            elif data[0] == AUDIO and len(data) == 9 + PACKET_BYTES and self.offset is not None:
                self.packets.put((struct.unpack_from(">q", data, 1)[0], data[9:]))

    def callback(self, out, frames, t, status):
        while True:
            try:
                self.playout.add(*self.packets.get_nowait())
            except queue.Empty:
                break
        ahead = t.outputBufferDacTime - t.currentTime  # some Windows drivers report 0 here
        if not 0 < ahead < 1:
            ahead = self.latency
        if self.offset is None:
            out[:] = bytes(frames * FRAME)
            return
        target = now() + int(ahead * 1e9) + self.offset - self.delay_ms * 1_000_000
        out[:] = self.playout.take(frames, target)

    def run(self):
        import sounddevice as sd

        print("Looking for a host on this Wi-Fi...  (+/- adjust delay by 10 ms, Ctrl+C to quit)")
        for fn in (self.find_host, self.ping, self.receive):
            thread(fn)
        with sd.RawOutputStream(samplerate=RATE, channels=2, dtype="int16", callback=self.callback) as stream:
            self.latency = stream.latency

            def on_key(k):
                if k in "+=-_":
                    self.delay_ms = max(0, min(500, self.delay_ms + (10 if k in "+=" else -10)))
                    print(f"Delay {self.delay_ms} ms")

            key_loop(on_key)


# ---------------------------------------------------------------- host (laptop = source)

class Host:
    def __init__(self):
        self.sock = udp(CONTROL_PORT)
        self.clients = {}  # (ip, port) -> last ping time

    def control(self):
        """Answers pings (clock sync) and remembers who pinged (registration)."""
        while True:
            data, addr = self.sock.recvfrom(64)
            if len(data) != 9 or data[0] != PING:
                continue
            self.clients[addr] = now()
            self.sock.sendto(struct.pack(">bqq", PONG, struct.unpack_from(">q", data, 1)[0], now()), addr)

    def beacon(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        shown = -1
        while True:
            # ponytail: assumes /24 subnets (true for home Wi-Fi and phone/Windows hotspots); read real masks via psutil if not.
            ips = {a[4][0] for a in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)}
            for target in {"255.255.255.255"} | {ip.rsplit(".", 1)[0] + ".255" for ip in ips if not ip.startswith("127.")}:
                try:
                    s.sendto(bytes([BEACON]), (target, BEACON_PORT))
                except OSError:
                    pass
            for addr, seen in list(self.clients.items()):
                if now() - seen > 5_000_000_000:
                    del self.clients[addr]
            if len(self.clients) != shown:
                shown = len(self.clients)
                print(f"{shown} speaker{'' if shown == 1 else 's'} connected")
            time.sleep(1)

    def run(self):
        import numpy as np
        import soundcard as sc

        warnings.filterwarnings("ignore", "data discontinuity")  # gaps are handled by the re-anchor below

        thread(self.control)
        thread(self.beacon)
        speaker = sc.default_speaker()
        loopback = sc.get_microphone(id=str(speaker.name), include_loopback=True)
        print(f"Sharing everything playing on '{speaker.name}'. Ctrl+C to quit.")
        print("Note: this PC's own speakers play ~250 ms ahead of the phones; mute them or use a phone as speaker.")
        start, frames = now(), 0
        with loopback.recorder(samplerate=RATE, channels=2, blocksize=FRAMES_PER_PACKET) as rec:
            while True:
                pcm = (np.clip(rec.record(numframes=FRAMES_PER_PACKET), -1, 1) * 32767).astype("<i2").tobytes()
                t = now()
                if abs(start + frames_ns(frames) - t) > RESYNC_NS:  # capture stalled (silence) or drifted: re-anchor
                    start = t - frames_ns(frames)
                packet = struct.pack(">bq", AUDIO, start + frames_ns(frames) + PLAYOUT_DELAY_NS) + pcm
                frames += FRAMES_PER_PACKET
                for addr in list(self.clients):
                    self.sock.sendto(packet, addr)


def main():
    p = argparse.ArgumentParser(description="AudioShare for laptops")
    sub = p.add_subparsers(dest="mode", required=True)
    j = sub.add_parser("join", help="play a host's audio on this PC")
    j.add_argument("--delay", type=int, default=0, help="speaker delay in ms (0-500)")
    sub.add_parser("host", help="share this PC's audio with phones")
    a = p.parse_args()
    try:
        Join(a.delay).run() if a.mode == "join" else Host().run()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
