"""Checks the playout scheduler: python test_audioshare.py"""
from audioshare import FRAME, FRAMES_PER_PACKET, Playout

MS = 1_000_000
T = 10_000 * MS


def pkt(v):
    return bytes([v]) * (FRAMES_PER_PACKET * FRAME)


def fresh():
    p = Playout()
    for i in range(4):
        p.add(T + i * 5 * MS, pkt(i + 1))
    return p


# on time: plays the first packet as is
assert fresh().take(240, T) == pkt(1)

# early by 50 ms: 2400 frames of silence, then the audio
out = fresh().take(4800, T - 50 * MS)
assert out[: 2400 * FRAME] == bytes(2400 * FRAME) and out[2400 * FRAME: 2640 * FRAME] == pkt(1)

# late by 20 ms: skips 4 packets; nothing after them is queued yet -> silence
assert fresh().take(240, T + 20 * MS) == bytes(240 * FRAME)
p = fresh()
p.add(T + 20 * MS, pkt(5))
assert p.take(240, T + 20 * MS) == pkt(5)

# within the 15 ms tolerance (10 ms either way): no correction, plays straight
assert fresh().take(240, T - 10 * MS) == pkt(1)
assert fresh().take(240, T + 10 * MS) == pkt(1)

# lost packet: gap is filled with silence so later audio stays on time
p = Playout()
p.add(T, pkt(1))
p.add(T + 10 * MS, pkt(3))
assert p.take(720, T) == pkt(1) + bytes(240 * FRAME) + pkt(3)

# host restarted (timestamps jump back): starts fresh
p = fresh()
p.add(T - 1000 * MS, pkt(9))
assert p.take(240, T - 1000 * MS) == pkt(9)

print("ok")
