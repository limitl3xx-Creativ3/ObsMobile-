package dev.obsmobile.api

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/**
 * Bluetooth control channel (SPP / RFCOMM). Any paired device — another phone,
 * a PC, a macro pad, an Arduino — can open a serial connection and send
 * simple line commands:
 *
 *   STATUS            → JSON state
 *   CALL <name>       → execute a custom API (same names as HTTP /api/custom)
 *   LIST              → list available custom APIs
 *   STOP              → stop capture
 *   PING              → "PONG"
 */
class BluetoothCommandServer {

    companion object {
        private const val TAG = "ObsMobileBT"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SERVICE_NAME = "ObsMobile"
    }

    @Volatile private var running = false
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        val adapter = (appContext?.getSystemService(BluetoothManager::class.java))?.adapter ?: return
        if (Build.VERSION.SDK_INT >= 31 &&
            appContext?.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted; BT control disabled")
            return
        }
        running = true
        acceptThread = Thread {
            try {
                val ss = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                serverSocket = ss
                while (running) {
                    val sock = ss.accept() ?: break
                    Thread { serve(sock) }.start()
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "BT server: ${e.message}")
            }
        }.apply { start() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        acceptThread?.interrupt()
    }

    private fun serve(sock: BluetoothSocket) {
        sock.use { s ->
            val reader = BufferedReader(InputStreamReader(s.inputStream))
            while (running) {
                val line = reader.readLine()?.trim() ?: break
                val reply = handle(line)
                runCatching { s.outputStream.write((reply + "\n").toByteArray()) }
            }
        }
    }

    private fun handle(line: String): String {
        val cmd = line.substringBefore(' ').uppercase()
        val arg = line.substringAfter(' ', "").trim()
        return when (cmd) {
            "PING" -> "PONG"
            "STATUS" -> JSONObject()
                .put("running", dev.obsmobile.capture.ScreenCaptureService.instance != null)
                .put("apis", ApiStore.list().map { it.name })
                .toString()
            "LIST" -> ApiStore.list().joinToString(",") { it.name }.ifEmpty { "(none)" }
            "STOP" -> {
                dev.obsmobile.capture.ScreenCaptureService.instance?.stopSelf()
                "OK stopped"
            }
            "CALL" -> {
                val api = ApiStore.get(arg)
                if (api == null) "ERR unknown api: $arg"
                else {
                    val (status, body) = ApiStore.call(api)
                    "OK $status ${body.take(500)}"
                }
            }
            else -> "ERR unknown command"
        }
    }

    private var appContext: android.content.Context? = null

    fun withContext(ctx: android.content.Context): BluetoothCommandServer {
        appContext = ctx.applicationContext
        return this
    }
}
