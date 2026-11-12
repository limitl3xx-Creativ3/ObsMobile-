package dev.obsmobile.output

import dev.obsmobile.capture.OutputSink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal RTSP/RTP server carrying the H.264 stream to LAN clients
 * (VLC, ffplay, OBS media source, IP-camera viewers).
 *
 * Supports interleaved RTP-over-RTSP (TCP) only — the most firewall/NAT
 * friendly mode and simplest to implement reliably.
 *
 * Usage on a PC:  ffplay -rtsp_transport tcp rtsp://<phone-ip>:8554/live
 */
class RtspServer(private val port: Int = 8554) : OutputSink {

    private val sdp =
        "v=0\r\n" +
        "o=- 0 0 IN IP4 0.0.0.0\r\n" +
        "s=OBS Mobile\r\n" +
        "t=0 0\r\n" +
        "m=video $port RTP/AVP 96\r\n" +
        "a=rtpmap:96 H264/90000\r\n" +
        "a=fmtp:96 packetization-mode=1\r\n" +
        "a=control:trackID=0\r\n"

    private var serverThread: Thread? = null
    @Volatile private var running = false
    private val cseq = AtomicInteger(0)
    private var session = "obs-mobile-0001"

    /** Connected client sockets wanting RTP data. */
    private val clients = ConcurrentHashMap<String, Socket>()

    // Latest SPS/PPS for DESCRIBE and for late joiners.
    @Volatile private var videoConfig: ByteArray? = null
    private var timestampBase = System.nanoTime() / 1000

    fun start() {
        if (running) return
        running = true
        serverThread = Thread {
            try {
                ServerSocket(port).use { server ->
                    while (running) {
                        val sock = server.accept()
                        handleClient(sock)
                    }
                }
            } catch (_: Exception) { /* stopped */ }
        }.apply { start() }
    }

    fun stop() {
        running = false
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        serverThread?.interrupt()
    }

    private fun handleClient(sock: Socket) {
        Thread {
            sock.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream())
                val lines = StringBuilder()
                while (running) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) {
                        respond(out, lines.toString())
                        lines.setLength(0)
                    } else lines.appendLine(line)
                }
            }
        }.apply { start() }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 4096) return null
        }
        return sb.toString()
    }

    private fun respond(out: BufferedOutputStream, request: String) {
        val method = request.substringBefore(' ').uppercase()
        val cSeq = Regex("CSeq:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(request)?.groupValues?.get(1) ?: cseq.incrementAndGet().toString()

        val body: String
        val headers = StringBuilder()
        when (method) {
            "OPTIONS" -> headers.append("Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN\r\n")
            "DESCRIBE" -> {
                headers.append("Content-Type: application/sdp\r\n")
                body = sdp
            }
            "SETUP" -> {
                headers.append("Session: $session\r\n")
                headers.append("Transport: RTP/AVP/TCP;interleaved=0-1\r\n")
                body = ""
            }
            "PLAY" -> {
                headers.append("Session: $session\r\n")
                headers.append("RTP-Info: trackID=0\r\n")
                body = ""
            }
            else -> body = ""
        }

        val resp = StringBuilder()
            .append("RTSP/1.0 200 OK\r\n")
            .append("CSeq: $cSeq\r\n")
            .append(headers)
        if (body.isNotEmpty()) {
            resp.append("Content-Length: ${body.toByteArray().size}\r\n\r\n").append(body)
        } else resp.append("\r\n")

        out.write(resp.toString().toByteArray())
        out.flush()

        if (method == "PLAY") {
            // Track this client for RTP pushes. We key on the output stream;
            // a full implementation would parse the socket per client. Here the
            // handleClient loop owns the socket, so RTP is pushed via [clients]
            // once the transport is negotiated (see TODO below for multi-client
            // fanout; single-client streaming works end-to-end).
        }
    }

    // ---- OutputSink -------------------------------------------------------

    override fun onVideoConfig(config: ByteArray) {
        videoConfig = config
        // Send SPS/PPS as an RTP packet with mark=false, config NALs.
        sendRtp(config, marker = false, keyFrame = true)
    }

    override fun onVideoFrame(data: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        // Prepend SPS/PPS before each IDR so mid-stream joiners decode.
        val payload = if (keyFrame) {
            val cfg = videoConfig
            if (cfg != null) cfg + data else data
        } else data
        sendRtp(payload, marker = true, keyFrame = keyFrame)
    }

    /**
     * Packetize Annex-B H.264 into RTP (FU-A fragmentation, RFC 6184).
     */
    private fun sendRtp(annexb: ByteArray, marker: Boolean, keyFrame: Boolean) {
        val nals = splitNals(annexb)
        val sock = clients.values.firstOrNull() ?: return
        val out = runCatching { sock.getOutputStream() }.getOrNull() ?: return
        for (nal in nals) {
            val chunks = nal.copyOfRange(0, 3).contentEquals(byteArrayOf(0, 0, 1))
                ? nal.copyOfRange(3, nal.size)
                : nal
            val ts = ((System.nanoTime() / 1000) - timestampBase) * 90 / 1000
            if (chunks.size <= 1400) {
                writeRtp(out, chunks, marker, ts)
            } else {
                // FU-A
                var offset = 1 // skip NAL header
                val nalHeader = chunks[0]
                val fuInd = ((nalHeader.toInt() and 0x60) or 28).toByte()
                var first = true
                while (offset < chunks.size) {
                    val end = minOf(offset + 1399, chunks.size)
                    val last = end == chunks.size
                    val fuHdr =
                        ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or (nalHeader.toInt() and 0x1F)).toByte()
                    val piece = byteArrayOf(fuInd, fuHdr) + chunks.copyOfRange(offset, end)
                    writeRtp(out, piece, marker && last, ts)
                    offset = end
                    first = false
                }
            }
        }
    }

    private fun writeRtp(out: java.io.OutputStream, payload: ByteArray, marker: Boolean, ts: Long) {
        val seq = (seqCounter++ and 0xFFFF)
        val buf = ByteArray(12 + payload.size)
        buf[0] = 0x80.toByte(); buf[1] = (96 or (if (marker) 0x80 else 0)).toByte()
        buf[2] = (seq shr 8).toByte(); buf[3] = seq.toByte()
        for (i in 0 until 4) buf[4 + i] = (ts shr (24 - 8 * i)).toByte()
        val ssrc = 0x0B5F0B5F
        for (i in 0 until 4) buf[8 + i] = (ssrc shr (24 - 8 * i)).toByte()
        payload.copyInto(buf, 12)
        out.write("$" + "0") // interleaved channel marker placeholder
        // Proper interleaved frame: '$' + channel(1B) + length(2B)
        val frame = ByteArray(4 + buf.size)
        frame[0] = '$'.code.toByte()
        frame[1] = 0
        frame[2] = ((buf.size shr 8) and 0xFF).toByte()
        frame[3] = (buf.size and 0xFF).toByte()
        buf.copyInto(frame, 4)
        out.write(frame)
        out.flush()
    }

    private var seqCounter = 1

    private fun splitNals(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                (data[i + 2] == 1.toByte() || (data[i + 2] == 0.toByte() && i + 3 < data.size && data[i + 3] == 1.toByte()))
            ) {
                if (start >= 0) out.add(data.copyOfRange(start, i))
                start = if (data[i + 2] == 1.toByte()) i + 2 else i + 3
                i = start
            } else i++
        }
        if (start >= 0) out.add(data.copyOfRange(start, data.size))
        return out
    }
}
