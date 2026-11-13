package dev.obsmobile.api

import dev.obsmobile.capture.ScreenCaptureService
import dev.obsmobile.output.RtspServerService
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * REST control API over Wi-Fi so OTHER apps and devices can drive OBS Mobile:
 *
 *   GET  /api/status                 → capture + stream state, IP, endpoints
 *   POST /api/capture/stop           → stop screen capture
 *   POST /api/switch                 → switch scene (reserved for v0.2 sources)
 *   GET  /api/custom                 → list custom APIs
 *   POST /api/custom                 → add one: {"name","method","url",...}
 *   DELETE /api/custom/<name>        → remove one
 *   POST /api/custom/<name>/call     → execute it (optional ?key=val args)
 *   POST /api/broadcast?msg=...      → send a broadcast intent to device apps
 *
 * Every command reachable via Bluetooth is also available here (and vice versa).
 */
class ControlServer(private val port: Int = 8081) {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                ServerSocket(port).use { server ->
                    while (running) {
                        val sock = server.accept()
                        Thread { handle(sock) }.start()
                    }
                }
            } catch (_: Exception) { /* stopped */ }
        }.apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val (method, rawPath) = parts[0] to parts[1]
            val (path, query) = rawPath.split("?").let { it[0] to (if (it.size > 1) it[1] else "") }
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length", true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val body = if (contentLength > 0) CharArray(contentLength).let { reader.read(it); String(it) } else ""

            val params = parseQuery(query) + (if (body.isNotBlank()) parseJSONBody(body) else emptyMap())
            val (status, json) = route(method, path, params)

            val payload = json.toString().toByteArray()
            val resp = "HTTP/1.1 $status ${if (status == 200) "OK" else "ERROR"}\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = BufferedOutputStream(s.getOutputStream())
            out.write(resp.toByteArray()); out.write(payload); out.flush()
        }
    }

    private fun route(method: String, path: String, params: Map<String, String>): Pair<Int, JSONObject> {
        val ok = { o: JSONObject.() -> Unit -> 200 to JSONObject().apply(o) }
        return when {
            path == "/api/status" -> ok {
                put("running", ScreenCaptureService.instance != null)
                put("rtsp", "rtsp://${localIp()}:${RtspServerService.PORT}/live")
                put("api", "http://${localIp()}:8081")
                put("customApis", ApiStore.list().map { it.name })
            }

            path == "/api/capture/stop" && method == "POST" -> {
                ScreenCaptureService.instance?.let { s -> s.stopSelf() }
                ok { put("result", "stopped") }
            }

            path == "/api/switch" && method == "POST" -> ok {
                // v0.2: scene switching between screen/camera/image sources.
                put("result", "reserved")
                put("note", "scene switching arrives with source manager v0.2")
            }

            path == "/api/custom" && method == "GET" -> ok {
                put("apis", JSONArray().apply {
                    ApiStore.list().forEach {
                        put(JSONObject().put("name", it.name).put("method", it.method).put("url", it.url))
                    }
                })
            }

            path == "/api/custom" && method == "POST" -> {
                val added = ApiStore.add(
                    ApiStore.CustomApi(
                        name = params["name"] ?: "",
                        method = params["method"] ?: "GET",
                        url = params["url"] ?: "",
                        headers = (params["headers_json"] ?: "{}").let {
                            runCatching {
                                val h = JSONObject(it); mutableMapOf<String, String>().also { m -> h.keys().forEach { k -> m[k] = h.getString(k) } }
                            }.getOrDefault(emptyMap())
                        },
                        body = params["body"],
                    )
                )
                if (added) ok { put("result", "added"); put("name", params["name"]) }
                else 400 to JSONObject().put("error", "name/url required")
            }

            path.startsWith("/api/custom/") && method == "DELETE" -> {
                val name = path.removePrefix("/api/custom/").trim('/')
                if (ApiStore.remove(name)) ok { put("result", "removed") }
                else 404 to JSONObject().put("error", "unknown api: $name")
            }

            path.endsWith("/call") && path.startsWith("/api/custom/") && method == "POST" -> {
                val name = path.removePrefix("/api/custom/").removeSuffix("/call").trim('/')
                val api = ApiStore.get(name)
                if (api == null) 404 to JSONObject().put("error", "unknown api: $name")
                else {
                    val (status, text) = ApiStore.call(api, params)
                    ok {
                        put("api", name)
                        put("httpStatus", status)
                        put("response", text.take(4000))
                    }
                }
            }

            path == "/api/broadcast" && method == "POST" -> {
                params["msg"]?.let { msg ->
                    dev.obsmobile.MainActivity.context?.sendBroadcast(
                        android.content.Intent("dev.obsmobile.CUSTOM_EVENT").setPackage("dev.obsmobile").putExtra("msg", msg)
                    )
                }
                ok { put("result", "broadcast-sent") }
            }

            else -> 404 to JSONObject().put("error", "unknown route").put("path", path)
        }
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split("&").filter { it.contains('=') }.associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun parseJSONBody(body: String): Map<String, String> = runCatching {
        val o = JSONObject(body)
        val m = mutableMapOf<String, String>()
        o.keys().forEach { k -> m[k] = o.optString(k) }
        m
    }.getOrDefault(emptyMap())

    private fun localIp(): String =
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "0.0.0.0"
}
