package com.audioshare

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Streams timestamped PCM to every receiver that pings us. Two sources:
 *  - intent.data = a song Uri: decode the file ourselves (host speaker can join in sync via a local receiver)
 *  - otherwise: capture other apps' audio via MediaProjection
 */
class HostService : Service() {
    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private var socket: DatagramSocket? = null
    private val clients = ConcurrentHashMap<SocketAddress, Long>() // address -> last ping time
    private val packet = ByteArray(AUDIO_HEADER + PACKET_BYTES)
    private var frames = 0L // frames sent since start
    private var start = 0L // System.nanoTime() of frame 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running || intent == null) return START_NOT_STICKY
        val uri = intent.data
        val source: () -> Unit
        if (uri != null) {
            startForeground(1, notification(this, "Playing song"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            source = { playFile(uri) }
            Status.text = "Starting song…"
        } else {
            // Android 14+: must be in the foreground with type mediaProjection before getMediaProjection().
            startForeground(1, notification(this, "Sharing audio"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>("data")
            if (data == null) { stopSelf(); return START_NOT_STICKY }
            val p = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra("code", 0), data)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopSelf()
            }, Handler(Looper.getMainLooper()))
            projection = p
            source = { capture(p) }
            Status.text = "Hosting: play music in any app"
        }

        val s = DatagramSocket(CONTROL_PORT).also { socket = it }
        running = true
        start = System.nanoTime()
        thread { source() }
        thread { control(s) }
        thread { beacon() }
        return START_NOT_STICKY
    }

    /** Stamps the PCM already in [packet] with its play time and sends it to every receiver. */
    private fun send(paced: Boolean) {
        var now = System.nanoTime()
        val due = start + framesToNs(frames)
        if (paced && due > now) { LockSupport.parkNanos(due - now); now = System.nanoTime() } // file decodes faster than real time
        // Timeline from frame count (smooth); re-anchor to the clock if it drifts or the source stalled.
        if (abs(due - now) > RESYNC_NS) start = now - framesToNs(frames)
        val t = start + framesToNs(frames)
        frames += FRAMES_PER_PACKET
        ByteBuffer.wrap(packet).put(0, MSG_AUDIO).putLong(1, t + PLAYOUT_DELAY_NS)
        val s = socket ?: return
        for (c in clients.keys) s.send(DatagramPacket(packet, packet.size, c))
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by MainActivity before we start
    private fun capture(p: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(PACKET_BYTES * 8)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        try {
            record.startRecording()
            while (running) {
                if (record.read(packet, AUDIO_HEADER, PACKET_BYTES) < PACKET_BYTES) continue
                send(paced = false)
            }
        } catch (_: IOException) { // socket closed on stop
        } finally {
            record.stop()
            record.release()
        }
    }

    private fun playFile(uri: Uri) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(this, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run { Status.text = "No audio in that file"; return }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val resampler = Resampler()
            val pcm = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN) // AudioTrack wants native (LE) PCM
            var at = AUDIO_HEADER
            val info = MediaCodec.BufferInfo()
            var inputDone = false

            // ponytail: fixed wait so receivers (beacon 1 s + ping 0.5 s) join before the intro plays.
            Thread.sleep(2000)
            start = System.nanoTime()
            frames = 0
            Status.text = "Playing song"

            while (running) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val out = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    out.position(info.offset)
                    val samples = ShortArray(info.size / 2)
                    out.asShortBuffer().get(samples)
                    c.releaseOutputBuffer(o, false)
                    resampler.process(samples, channels, rate) { l, r ->
                        pcm.putShort(at, l)
                        pcm.putShort(at + 2, r)
                        at += BYTES_PER_FRAME
                        if (at == packet.size) { send(paced = true); at = AUDIO_HEADER }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (running) { Status.text = "Song finished"; stopSelf() }
        } catch (e: Exception) {
            if (running) Status.text = "Can't play that file: ${e.message}"
        } finally {
            codec?.release()
            extractor.release()
        }
    }

    /** Answers pings (clock sync) and remembers who pinged (registration). */
    private fun control(s: DatagramSocket) {
        val p = DatagramPacket(ByteArray(64), 64)
        while (running) {
            p.length = 64
            try { s.receive(p) } catch (_: IOException) { return }
            if (p.length != 9 || p.data[0] != MSG_PING) continue
            clients[p.socketAddress] = System.nanoTime()
            val t0 = ByteBuffer.wrap(p.data).getLong(1)
            val reply = ByteBuffer.allocate(17).put(MSG_PONG).putLong(t0).putLong(System.nanoTime())
            try { s.send(DatagramPacket(reply.array(), 17, p.socketAddress)) } catch (_: IOException) { return }
        }
    }

    /** Announces our IP once a second and forgets receivers that went quiet. */
    private fun beacon() = DatagramSocket().use { s ->
        s.broadcast = true
        val payload = byteArrayOf(MSG_BEACON)
        while (running) {
            // 255.255.255.255 only leaves via the default route (mobile data when we're the hotspot),
            // so also hit each interface's own broadcast address: Wi-Fi, hotspot, USB tether.
            val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
            try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses }.mapNotNullTo(targets) { it.broadcast }
            } catch (_: IOException) {}
            for (t in targets) try { s.send(DatagramPacket(payload, 1, t, BEACON_PORT)) } catch (_: IOException) {}
            clients.entries.removeIf { System.nanoTime() - it.value > 5_000_000_000L }
            Status.clients = clients.size
            Thread.sleep(1000)
        }
    }

    override fun onDestroy() {
        running = false
        socket?.close()
        projection?.stop()
        clients.clear()
        Status.clients = 0
        if (Status.mode == Mode.Capture || Status.mode == Mode.Song) {
            if (Status.mode == Mode.Song) stopService(Intent(this, ReceiverService::class.java))
            Status.mode = Mode.Idle
        }
        super.onDestroy()
    }
}
