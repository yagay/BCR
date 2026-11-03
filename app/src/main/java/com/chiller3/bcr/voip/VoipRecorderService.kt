package com.chiller3.bcr.voip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.chiller3.bcr.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Isolated experimental VoIP capture controller. Never starts automatically at
 * boot and never modifies global audio or SELinux policy. Only users who opted
 * into a known app can request capture. A conspicuous FGS notification remains
 * visible for the whole recording.
 */
class VoipRecorderService : Service() {
    private val tag = "BCR/VoIPController"
    private var worker: Thread? = null
    @Volatile private var stopMarker: File? = null
    @Volatile private var recording = false
    private var source = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "BCR experimental VoIP capture",
                    NotificationManager.IMPORTANCE_LOW)
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return START_NOT_STICKY
                if (pkg !in VoipTargets.enabled(this)) {
                    VoipStatus.update(this, false, "VoIP app not enabled in BCR settings")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!recording) {
                    recording = true
                    source = pkg
                    try {
                        val notif = notification("Recording experimental VoIP audio…")
                        if (Build.VERSION.SDK_INT >= 34) {
                            startForeground(ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                        } else {
                            startForeground(ID, notif)
                        }
                        beginRecording(pkg)
                    } catch (e: Exception) {
                        VoipStatus.update(this, false, "Cannot start VoIP service: ${e.message}")
                        recording = false
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val intent = Intent(this, VoipSettingsActivity::class.java)
        val open = PendingIntent.getActivity(this, 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = Intent(this, VoipRecorderService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, 3, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_quick_settings)
            .setContentTitle("BCR VoIP recorder")
            .setContentText(message)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop recording", stop)
            .build()
    }

    private fun beginRecording(pkg: String) {
        val tag = VoipTargets.tagFor(pkg) ?: error("Unknown app")
        val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val folder = File(filesDir, "voip").apply { mkdirs() }
        val wav = File(folder, "bcr_${now}_${tag}.wav")
        val marker = File(folder, "bcr_${now}_${tag}.stop")
        marker.delete()
        stopMarker = marker
        VoipStatus.update(this, true, "Starting Root AudioPolicy capture for ${pkg} …")
        worker = Thread({
            var result = ""
            var exit = -1
            try {
                val apk = applicationInfo.sourceDir ?: error("Installed APK not found")
                val cmd = "CLASSPATH=" + quote(apk) +
                    " /system/bin/app_process / com.chiller3.bcr.voip.VoipShellCapture " +
                    quote(wav.absolutePath) + " " + quote(marker.absolutePath)
                val process = ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true).start()
                val log = StringBuilder()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Log.i(this.tag, line.take(400))
                        if (log.length < 3000) log.append(line.take(400)).append(';')
                    }
                }
                exit = process.waitFor()
                result = log.toString()
                if (exit == 0 && wav.exists() && wav.length() > 44L) {
                    // app_process runs as Root and the file may be root-owned.
                    // The app-private parent directory is still inaccessible
                    // to other ordinary apps.
                    val saved = publish(wav)
                    VoipStatus.update(this, false,
                        "VoIP playback saved to Recordings/BCR-VoIP. This may contain only the remote party.",
                        saved)
                } else {
                    VoipStatus.update(this, false,
                        "VoIP capture unavailable (exit=$exit): ${result.takeLast(400)}")
                }
            } catch (e: Exception) {
                Log.e(this.tag, "VoIP capture error", e)
                VoipStatus.update(this, false,
                    "VoIP capture failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                wav.delete()
                marker.delete()
                stopMarker = null
                recording = false
                stopSelf()
            }
        }, "BCR-VoIP-RootWorker").apply { start() }
    }

    private fun publish(wav: File): String {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, wav.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH,
                Environment.DIRECTORY_RECORDINGS + "/BCR-VoIP")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused to create recording")
        try {
            contentResolver.openOutputStream(uri)?.use { destination ->
                wav.inputStream().use { input -> input.copyTo(destination) }
            } ?: error("MediaStore output stream unavailable")
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
            return wav.name
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun requestStop() {
        val marker = stopMarker
        if (marker != null) {
            runCatching { marker.writeText("stop") }
            val n = notification("Stopping VoIP capture…")
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, n)
        } else if (!recording) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { stopMarker?.writeText("stop") }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "bcr_voip_capture"
        private const val ID = 22155
        private const val ACTION_START = "com.chiller3.bcr.voip.START"
        private const val ACTION_STOP = "com.chiller3.bcr.voip.STOP"
        private const val EXTRA_PACKAGE = "package"

        private fun quote(s: String): String =
            "'" + s.replace("'", "'\\''") + "'"

        fun start(context: Context, pkg: String): Boolean {
            if (pkg !in VoipTargets.enabled(context)) return false
            return runCatching {
                val intent = Intent(context, VoipRecorderService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_PACKAGE, pkg)
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure { e ->
                VoipStatus.update(context, false,
                    "Android refused background VoIP start: ${e.javaClass.simpleName}")
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            // Existing FGS can receive a stop action; app-service restrictions
            // are reported in diagnostics rather than silently ignored.
            runCatching {
                context.startService(Intent(context, VoipRecorderService::class.java)
                    .setAction(ACTION_STOP))
            }.onFailure {
                context.stopService(Intent(context, VoipRecorderService::class.java))
            }
        }
    }
}
