package dev.obsmobile.output

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.obsmobile.capture.ScreenCaptureService

/** Hosts the [RtspServer] while screen capture is active. */
class RtspServerService : Service() {

    private var server: RtspServer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startNotification()
        server = RtspServer(PORT).also { it.start() }
        ScreenCaptureService.instance?.let {
            // Sinks are registered from MainActivity when capture starts.
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        instance = null
        super.onDestroy()
    }

    fun sink(): RtspServer? = server

    private fun startNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW)
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBS Mobile RTSP server running")
            .setContentText("rtsp://<device-ip>:$PORT/live")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(2, notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 8554
        private const val CHANNEL_ID = "stream"

        @Volatile var instance: RtspServerService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RtspServerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RtspServerService::class.java))
        }
    }
}
