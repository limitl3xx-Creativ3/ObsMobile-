package dev.obsmobile.capture

/**
 * Sink for encoded output produced by sources.
 * Implementations: RTSP server (H.264 over RTP), RTMP publisher, file recorder.
 */
interface OutputSink {
    /** SPS/PPS (or other config) bytes, sent before key frames. */
    fun onVideoConfig(config: ByteArray)

    /**
     * One access unit (single NAL or annexb-concatenated NALs).
     * @param data encoded frame bytes (Annex-B start codes included)
     * @param keyFrame true for IDR frames
     * @param ptsUs presentation timestamp in microseconds
     */
    fun onVideoFrame(data: ByteArray, keyFrame: Boolean, ptsUs: Long)

    fun onAudioFrame(data: ByteArray, ptsUs: Long) {}

    /** Called when the source stops. Sinks should reset state. */
    fun onEnded()
}
