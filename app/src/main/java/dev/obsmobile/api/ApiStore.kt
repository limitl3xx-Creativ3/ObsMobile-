package dev.obsmobile.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * User-defined custom APIs.
 *
 * Each entry maps a short name to an external HTTP call so it can be
 * triggered from the control API, Bluetooth commands, or scenes:
 *
 *   { "name": "lights-on", "method": "POST",
 *     "url": "http://192.168.1.50/api/light/on",
 *     "headers": {"Authorization": "Bearer xyz"},
 *     "body": "{}" }
 */
object ApiStore {

    data class CustomApi(
        val name: String,
        val method: String = "GET",
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
    )

    private lateinit var file: File
    private val apis = LinkedHashMap<String, CustomApi>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "custom_apis.json")
        if (file.exists()) {
            runCatching {
                JSONArray(file.readText()).forEach { a ->
                    val o = a as JSONObject
                    val headers = mutableMapOf<String, String>()
                    o.optJSONObject("headers")?.let { h ->
                        h.keys().forEach { k -> headers[k] = h.getString(k) }
                    }
                    val api = CustomApi(
                        name = o.getString("name"),
                        method = o.optString("method", "GET").uppercase(),
                        url = o.getString("url"),
                        headers = headers,
                        body = o.optString("body", null),
                    )
                    apis[api.name] = api
                }
            }
        }
    }

    fun list(): List<CustomApi> = apis.values.toList()

    fun get(name: String): CustomApi? = apis[name]

    fun add(api: CustomApi): Boolean {
        if (api.name.isBlank() || api.url.isBlank()) return false
        apis[api.name] = api
        persist()
        return true
    }

    fun remove(name: String): Boolean {
        val ok = apis.remove(name) != null
        if (ok) persist()
        return ok
    }

    private fun persist() {
        val arr = JSONArray()
        apis.values.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("method", it.method)
                    .put("url", it.url)
                    .put("headers", JSONObject(it.headers))
                    .put("body", it.body ?: JSONObject.NULL)
            )
        }
        file.writeText(arr.toString())
    }

    /** Executes a custom API call. Returns (httpStatus, body). */
    fun call(api: CustomApi, args: Map<String, String> = emptyMap()): Pair<Int, String> {
        // Simple {{var}} substitution from args.
        var url = api.url
        var body = api.body ?: ""
        args.forEach { (k, v) ->
            url = url.replace("{{$k}}", v)
            body = body.replace("{{$k}}", v)
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = api.method
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        api.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body.isNotBlank() && api.method != "GET") {
            conn.doOutput = true
            conn.outputStream.write(body.toByteArray())
        }
        val status = conn.responseCode
        val text = runCatching {
            (if (status < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText()
        }.getOrNull() ?: ""
        conn.disconnect()
        return status to text
    }
}
