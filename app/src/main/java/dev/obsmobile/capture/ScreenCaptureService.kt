package dev.obsmobile.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures the screen via MediaProjection, hardware-encodes it to H.264 and
 * pushes Annex-B access units to every registered [OutputSink].
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encodeThread: Thread? = null
    @Volatile private var running = false

    private val sinks = CopyOnWriteArrayList<OutputSink>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotification()
    }

    override fun onDestroy() {
        stopCapture()
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startCapture(intent)
        }
        return START_STICKY
    }

    private fun startCapture(intent: Intent?) {
        if (running) return
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: return
        val data: Intent = intent.getParcelableExtra(EXTRA_RESULT_DATA) ?: return

        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection = mgr.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture(); stopSelf()
                }
            }, null)
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels / 2 * 2   // even
        val height = metrics.heightPixels / 2 * 2

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = enc.createInputSurface()
        enc.start()
        encoder = enc

        virtualDisplay = projection!!.createVirtualDisplay(
            "ObsMobileCapture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderSurface, null, null
        )

        running = true
        encodeThread = Thread { drainEncoder() }.apply { start() }
        broadcastState(STATE_RUNNING)
    }

    private fun drainEncoder() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        val bufferInfo = ByteArray(0)
        while (running) {
            try {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val out = enc.getOutputBuffer(idx) ?: continue
                    val chunk = ByteArray(info.size)
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    out.get(chunk)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    for (sink in sinks) {
                        if (isConfig) sink.onVideoConfig(chunk)
                        else sink.onVideoFrame(chunk, isKey, info.presentationTimeUs)
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
            } catch (e: IllegalStateException) {
                break
            }
        }
        @Suppress("UNUSED_EXPRESSION")
        bufferInfo
    }

    private fun stopCapture() {
        if (!running) return
        running = false
        encodeThread?.join(500)
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { projection?.stop() }
        encoder = null; virtualDisplay = null; projection = null
        sinks.forEach { it.onEnded() }
        broadcastState(STATE_STOPPED)
    }

    private fun broadcastState(state: String) {
        val i = Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, state)
        sendBroadcast(i)
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW)
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBS Mobile is capturing")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_STOP = "dev.obsmobile.STOP"
        const val ACTION_STATE = "dev.obsmobile.STATE"
        const val EXTRA_STATE = "state"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"
        private const val EXTRA_RESULT_CODE = "code"
        private const val EXTRA_RESULT_DATA = "data"
        private const val CHANNEL_ID = "capture"

        @Volatile var instance: ScreenCaptureService? = null
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
