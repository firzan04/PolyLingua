package com.polylingua.edge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single foreground service that keeps long-running work alive on MIUI: mock-test recording
 * (microphone), shadowing/TTS playback and calls (mediaPlayback), and model downloads / workbook
 * indexing (dataSync). Features call [acquire]/[release] with a [Reason]; the service merges the
 * active reasons into one ongoing notification, picks the matching foreground-service types, and
 * holds a partial wake lock until the last reason is released.
 */
class PolyLinguaForegroundService : Service() {

    enum class Reason(val label: String) {
        RECORDING("Recording"),
        PLAYBACK("Playback"),
        CALL("Live conversation"),
        DOWNLOAD("Downloading models"),
        INDEXING("Indexing workbook"),
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            val reasons = active.value.keys.toList()
            reasons.forEach { stopRequestsInternal.tryEmit(it) }
            active.value = emptyMap()
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Re-evaluates the active reasons: updates the notification or stops the service. */
    internal fun refresh() {
        val reasons = active.value
        if (reasons.isEmpty()) {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val types = foregroundTypes(reasons.keys)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(reasons), types)
        } catch (t: Throwable) {
            // Android 14 refuses the microphone type when the app is not in an eligible state;
            // keep the remaining types so playback/downloads survive.
            Log.w(TAG, "startForeground(types=$types) failed: ${t.message}")
            val fallback = types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(reasons),
                    if (fallback == 0) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else fallback)
            }.onFailure { Log.e(TAG, "Foreground start failed", it) }
        }
        acquireWakeLock()
    }

    private fun foregroundTypes(reasons: Set<Reason>): Int {
        var types = 0
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        for (r in reasons) {
            types = types or when (r) {
                Reason.RECORDING -> if (micGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                Reason.CALL -> (if (micGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                Reason.PLAYBACK -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                Reason.DOWNLOAD, Reason.INDEXING -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        }
        return if (types == 0) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else types
    }

    private fun buildNotification(reasons: Map<Reason, String>): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PolyLinguaForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = reasons.keys.joinToString(" · ") { it.label }
        val text = reasons.values.filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "PolyLingua Edge is working offline" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_polylingua)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PolyLingua:session").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        private const val TAG = "PolyLinguaFgs"
        const val CHANNEL_ID = "polylingua_session"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_REFRESH = "com.polylingua.edge.action.REFRESH"
        private const val ACTION_STOP_ALL = "com.polylingua.edge.action.STOP_ALL"
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000

        @Volatile
        private var instance: PolyLinguaForegroundService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        private val active = MutableStateFlow<Map<Reason, String>>(emptyMap())
        val activeReasons: StateFlow<Map<Reason, String>> = active

        private val stopRequestsInternal = MutableSharedFlow<Reason>(extraBufferCapacity = 8)

        /** Emitted when the user taps "Stop" in the notification; owners should end their work. */
        val stopRequests: SharedFlow<Reason> = stopRequestsInternal

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.fgs_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.fgs_channel_desc)
                        setShowBadge(false)
                    },
                )
            }
        }

        fun acquire(context: Context, reason: Reason, detail: String = "") {
            active.value = active.value + (reason to detail)
            kick(context.applicationContext)
        }

        fun update(context: Context, reason: Reason, detail: String) {
            if (reason !in active.value) return
            active.value = active.value + (reason to detail)
            kick(context.applicationContext)
        }

        fun release(context: Context, reason: Reason) {
            if (reason !in active.value) return
            active.value = active.value - reason
            kick(context.applicationContext)
        }

        private fun kick(context: Context) {
            mainHandler.post {
                val svc = instance
                if (svc != null) {
                    svc.refresh()
                } else if (active.value.isNotEmpty()) {
                    val intent = Intent(context, PolyLinguaForegroundService::class.java).setAction(ACTION_REFRESH)
                    try {
                        ContextCompat.startForegroundService(context, intent)
                    } catch (t: Throwable) {
                        // ForegroundServiceStartNotAllowedException (API 31+) when backgrounded.
                        Log.w(TAG, "Cannot start foreground service now: ${t.message}")
                    }
                }
            }
        }

        val isRunning: Boolean get() = instance != null
    }
}
