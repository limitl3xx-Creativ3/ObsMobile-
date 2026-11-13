package dev.obsmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.obsmobile.capture.ProjectionPermissionActivity
import dev.obsmobile.capture.ScreenCaptureService
import dev.obsmobile.output.RtspServerService
import java.net.NetworkInterface

/**
 * OBS-Mobile control room:
 *  1. Request screen-capture permission and start capture (any app's screen).
 *  2. Start the on-device RTSP server so a PC (VLC / OBS media source) —
 *     or any LAN app — can consume the stream.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var apiText: TextView
    private var streaming = false

    companion object {
        @Volatile var context: android.content.Context? = null
            private set
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(ScreenCaptureService.EXTRA_STATE)
            statusText.text = when (state) {
                ScreenCaptureService.STATE_RUNNING -> "● Capturing — choose the app to share via Recents"
                else -> "○ Idle"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status)
        urlText = findViewById(R.id.url)
        apiText = findViewById(R.id.api)
        context = applicationContext

        // Bluetooth control needs BLUETOOTH_CONNECT at runtime (Android 12+)
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 1)
        }

        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            if (streaming) {
                ScreenCaptureService.stop(this)
                RtspServerService.stop(this)
                streaming = false
            } else {
                RtspServerService.start(this)
                ProjectionPermissionActivity.request(this)
                streaming = true
            }
            updateUi()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            ScreenCaptureService.stop(this)
            RtspServerService.stop(this)
            streaming = false
            updateUi()
        }

        registerReceiver(stateReceiver, IntentFilter(ScreenCaptureService.ACTION_STATE))
        updateUi()
    }

    override fun onDestroy() {
        unregisterReceiver(stateReceiver)
        super.onDestroy()
    }

    private fun updateUi() {
        findViewById<Button>(R.id.btnCapture).text =
            if (streaming) "Switch source (Recents)" else "Start capture"
        findViewById<Button>(R.id.btnStop).visibility =
            if (streaming) View.VISIBLE else View.GONE
        urlText.text = "rtsp://${localIp()}:${RtspServerService.PORT}/live"
        apiText.text = "api: http://${localIp()}:${RtspServerService.CONTROL_PORT}/api/status\nbt: pair & open SPP \"ObsMobile\""
    }

    private fun localIp(): String =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "0.0.0.0"
}
