package com.audioshare

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.IBinder
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Finds the host, syncs clocks with it, and plays each packet at its scheduled time on the current output (BT speaker). */
class ReceiverService : Service() {
    @Volatile private var running = false
    @Volatile private var host: InetAddress? = null
    @Volatile private var offset = 0L // host clock - local clock, ns
    private var socket: DatagramSocket? = null
    private var beaconSocket: DatagramSocket? = null
    private val queue = LinkedBlockingQueue<ByteArray>(200) // ~1 s of audio
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        startForeground(2, notification(this, "Playing shared audio"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        val wifi = getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "audioshare").apply { acquire() }
        multicastLock = wifi.createMulticastLock("audioshare").apply { acquire() } // some phones drop broadcasts without it

        running = true
        if (intent?.getBooleanExtra("local", false) == true) {
            host = InetAddress.getLoopbackAddress() // host phone playing its own song: same schedule as everyone
        } else {
            Status.text = "Looking for a host on this Wi-Fi…"
            thread { findHost() }
        }
        thread { receive() }
        thread { ping() }
        thread { play() }
        return START_NOT_STICKY
    }

    private fun findHost() {
        val s = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(BEACON_PORT)) }
        beaconSocket = s
        val p = DatagramPacket(ByteArray(16), 16)
        while (running) {
            p.length = 16
            try { s.receive(p) } catch (_: IOException) { return }
            if (p.data[0] != MSG_BEACON) continue
            if (host != p.address) Status.text = "Connected to ${p.address.hostAddress}"
            host = p.address
        }
    }

    private fun ping() {
        while (running) {
            val h = host
            val s = socket
            if (h != null && s != null) {
                val b = ByteBuffer.allocate(9).put(MSG_PING).putLong(System.nanoTime())
                try { s.send(DatagramPacket(b.array(), 9, h, CONTROL_PORT)) } catch (_: IOException) {}
            }
            Thread.sleep(500)
        }
    }

    private fun receive() {
        val s = DatagramSocket().also { socket = it }
        val samples = ArrayDeque<Pair<Long, Long>>() // (round trip, offset)
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        while (running) {
            p.length = buf.size
            try { s.receive(p) } catch (_: IOException) { return }
            if (p.address != host) continue
            when (buf[0]) {
                MSG_PONG -> if (p.length == 17) {
                    val t3 = System.nanoTime()
                    val b = ByteBuffer.wrap(buf)
                    val t0 = b.getLong(1)
                    samples.addLast(t3 - t0 to clockOffset(t0, b.getLong(9), t3))
                    if (samples.size > 16) samples.removeFirst()
                    offset = samples.minBy { it.first }.second // fastest round trip = least skewed estimate
                }
                MSG_AUDIO -> if (p.length == AUDIO_HEADER + PACKET_BYTES) queue.offer(buf.copyOf(p.length))
            }
        }
    }

    private fun play() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(
                max(
                    AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT),
                    PACKET_BYTES * 8
                )
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val silence = ByteArray(SAMPLE_RATE * BYTES_PER_FRAME) // up to 1 s
        var written = 0L
        track.play()
        while (running) {
            val pkt = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val due = ByteBuffer.wrap(pkt).getLong(1) - offset + Status.delayMs * 1_000_000L
            // ponytail: head position is coarse (~10 ms) and ignores BT latency; the delay slider covers the rest.
            // Switch to AudioTrack.getTimestamp() if speakers audibly drift.
            val pending = written - (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
            val heardAt = System.nanoTime() + framesToNs(pending)
            val c = correction(heardAt - due)
            if (c < 0) continue
            if (c > 0) {
                val f = min(c, SAMPLE_RATE)
                track.write(silence, 0, f * BYTES_PER_FRAME)
                written += f
            }
            track.write(pkt, AUDIO_HEADER, PACKET_BYTES)
            written += FRAMES_PER_PACKET
        }
        track.stop()
        track.release()
    }

    override fun onDestroy() {
        running = false
        socket?.close()
        beaconSocket?.close()
        wifiLock?.release()
        multicastLock?.release()
        queue.clear()
        super.onDestroy()
    }
}
