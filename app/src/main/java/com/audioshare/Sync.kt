package com.audioshare

import kotlin.math.floor
import kotlin.math.roundToInt

// Wire protocol (UDP, local Wi-Fi):
//   receiver -> host:CONTROL_PORT  PING  [1][t0:8]          (also registers the receiver)
//   host -> receiver               PONG  [2][t0:8][host:8]
//   host -> receiver               AUDIO [3][playAt:8][pcm]  playAt = host System.nanoTime()
//   host -> broadcast:BEACON_PORT  BEACON [4]                (receivers find the host's IP)

const val CONTROL_PORT = 50005
const val BEACON_PORT = 50006

const val MSG_PING: Byte = 1
const val MSG_PONG: Byte = 2
const val MSG_AUDIO: Byte = 3
const val MSG_BEACON: Byte = 4

const val SAMPLE_RATE = 48000
const val BYTES_PER_FRAME = 4 // 16-bit stereo
const val FRAMES_PER_PACKET = 240 // 5 ms -> 960 B, stays under Wi-Fi MTU
const val PACKET_BYTES = FRAMES_PER_PACKET * BYTES_PER_FRAME
const val AUDIO_HEADER = 9

// ponytail: raw PCM (~1.5 Mbit/s per receiver). Add Opus via MediaCodec if Wi-Fi chokes with many receivers.
const val PLAYOUT_DELAY_NS = 250_000_000L // network buffer; raise if audio stutters on busy Wi-Fi
const val TOLERANCE_NS = 15_000_000L // how far off a speaker may drift before we correct
const val RESYNC_NS = 100_000_000L // host re-anchors its timeline if capture drifts this far from the clock

/**
 * Converts decoded PCM (any rate, any channel count) to our 48 kHz stereo, keeping state across
 * chunks so there are no clicks at buffer boundaries. Mono -> both sides; >2 channels -> front L/R.
 * ponytail: linear interpolation, slightly dull highs on 44.1 kHz files. Swap for windowed-sinc if anyone hears it.
 */
class Resampler {
    private var pos = 0.0 // read position in input frames; -1..0 = between previous chunk's last frame and this one's first
    private var prevL: Short = 0
    private var prevR: Short = 0

    fun process(input: ShortArray, channels: Int, rate: Int, out: (Short, Short) -> Unit) {
        val n = input.size / channels
        if (n == 0) return
        val right = if (channels > 1) 1 else 0
        fun l(i: Int) = if (i < 0) prevL else input[i * channels]
        fun r(i: Int) = if (i < 0) prevR else input[i * channels + right]
        val step = rate.toDouble() / SAMPLE_RATE
        while (pos < n - 1) {
            val i = floor(pos).toInt()
            val f = pos - i
            out(lerp(l(i), l(i + 1), f), lerp(r(i), r(i + 1), f))
            pos += step
        }
        pos -= n
        prevL = l(n - 1)
        prevR = r(n - 1)
    }

    private fun lerp(a: Short, b: Short, f: Double) = (a + (b - a) * f).roundToInt().toShort()
}

fun framesToNs(frames: Long) = frames * 1_000_000_000L / SAMPLE_RATE

/** NTP-style: host clock minus local clock, from one ping round trip. */
fun clockOffset(t0: Long, hostTime: Long, t3: Long) = hostTime - (t0 + t3) / 2

/**
 * errorNs = when the next packet would be heard minus when it should be heard.
 * Returns -1 = drop it (we're late), N > 0 = write N frames of silence first (we're early), 0 = play as is.
 */
fun correction(errorNs: Long): Int = when {
    errorNs > TOLERANCE_NS -> -1
    errorNs < -TOLERANCE_NS -> (-errorNs * SAMPLE_RATE / 1_000_000_000L).toInt()
    else -> 0
}
