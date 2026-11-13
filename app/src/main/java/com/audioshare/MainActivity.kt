package com.audioshare

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Mode { Idle, Capture, Song, Receiver }

/** Shared between services and UI. Compose state is safe to write from background threads. */
object Status {
    var mode by mutableStateOf(Mode.Idle)
    var text by mutableStateOf("Choose how this phone joins in")
    var clients by mutableIntStateOf(0)
    var delayMs by mutableIntStateOf(0) // per-speaker calibration knob: Bluetooth latency differs per speaker
}

fun notification(ctx: Context, text: String): Notification {
    ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel("audio", "Audio sharing", NotificationManager.IMPORTANCE_LOW)
    )
    return Notification.Builder(ctx, "audio")
        .setContentTitle("AudioShare")
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_stat)
        .build()
}

class MainActivity : ComponentActivity() {

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            Status.mode = Mode.Capture
            startForegroundService(
                Intent(this, HostService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data)
            )
        } else Status.text = "Capture permission denied"
    }

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) { Status.text = "Audio permission is needed to capture other apps' audio"; return@registerForActivityResult }
        projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    private val pickSong = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Status.mode = Mode.Song
        startForegroundService(Intent(this, HostService::class.java).setData(uri))
        startForegroundService(Intent(this, ReceiverService::class.java).putExtra("local", true))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // status-bar icons follow light/dark mode
        setContent {
            AppTheme {
                HomeScreen(
                    onShare = { mic.launch(Manifest.permission.RECORD_AUDIO) },
                    onSong = { pickSong.launch(arrayOf("audio/*")) },
                    onJoin = {
                        Status.mode = Mode.Receiver
                        startForegroundService(Intent(this, ReceiverService::class.java))
                    },
                    onStop = {
                        Status.mode = Mode.Idle
                        stopService(Intent(this, HostService::class.java))
                        stopService(Intent(this, ReceiverService::class.java))
                        Status.text = "Stopped"
                    },
                )
            }
        }
    }
}
