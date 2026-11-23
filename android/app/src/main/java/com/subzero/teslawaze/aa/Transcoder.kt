package com.subzero.teslawaze.aa

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.subzero.teslawaze.H264Encoder

/**
 * Re-encodes Android Auto's video on the phone. Android Auto encodes at 4-5 Mbps, more than a
 * phone's mobile uplink sustains while driving; re-encoding lets us pick (and adapt) the bitrate,
 * and gives key frames on demand without restarting Android Auto's video stream.
 *
 * Android Auto H.264 -> hardware decoder -> encoder input surface -> [H264Encoder] -> sink.
 */
class Transcoder(
    width: Int,
    height: Int,
    bitRate: Int,
    sink: (encoder: H264Encoder, type: Int, ptsUs: Long, data: ByteArray) -> Unit,
) {
    private val encoder = H264Encoder(width, height, sink, bitRate)
    private val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    @Volatile private var running = true
    private val drainThread: Thread

    init {
        encoder.start()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        try {
            decoder.configure(format, encoder.surface, null, 0)
        } catch (e: Exception) {
            decoder.reset()
            format.removeKey(MediaFormat.KEY_LOW_LATENCY)
            decoder.configure(format, encoder.surface, null, 0)
        }
        decoder.start()
        drainThread = Thread({ drain() }, "aa-decode").also { it.start() }
    }

    /** SPS/PPS from Android Auto (sent at every video start). */
    fun config(data: ByteArray) = feed(data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)

    fun frame(data: ByteArray) = feed(data, 0)

    fun requestKeyFrame() = encoder.requestKeyFrame()

    fun setBitrate(bps: Int) = encoder.setBitrate(bps)

    private fun feed(data: ByteArray, flags: Int) {
        if (!running) return
        try {
            // Never drop input: every H.264 frame is a reference for the next ones.
            val index = decoder.dequeueInputBuffer(100_000)
            if (index < 0) return
            val buf = decoder.getInputBuffer(index) ?: return
            buf.clear()
            buf.put(data, 0, minOf(data.size, buf.capacity()))
            decoder.queueInputBuffer(index, 0, minOf(data.size, buf.capacity()), System.nanoTime() / 1000, flags)
        } catch (e: Exception) {
            Log.w("Transcoder", "feed failed", e)
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                decoder.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index >= 0) {
                try {
                    decoder.releaseOutputBuffer(index, true) // render into the encoder
                } catch (e: IllegalStateException) {
                    break
                }
            }
        }
    }

    fun release() {
        running = false
        drainThread.join(500)
        try { decoder.stop() } catch (_: Exception) {}
        try { decoder.release() } catch (_: Exception) {}
        encoder.stop()
    }
}
