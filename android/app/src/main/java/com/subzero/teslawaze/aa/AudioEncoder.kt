package com.subzero.teslawaze.aa

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log

/**
 * Opus encoder for Android Auto's audio, so it can play from the Tesla page's speakers.
 * Opus keeps it small (24-64 kbps next to the video) and browsers decode it with WebCodecs.
 */
class AudioEncoder(
    val rate: Int,
    val channels: Int,
    bitRate: Int,
    private val sink: (ptsUs: Long, data: ByteArray) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    @Volatile private var running = true
    private var ptsUs = 0L
    private val drainThread: Thread

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, rate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        drainThread = Thread({ drain() }, "opus-$rate").also { it.start() }
    }

    /** Never blocks the protocol thread for long; audio that does not fit is dropped. */
    fun feed(pcm: ByteArray, offset: Int) {
        if (!running) return
        var pos = offset
        try {
            while (pos < pcm.size) {
                val index = codec.dequeueInputBuffer(5_000)
                if (index < 0) return
                val buf = codec.getInputBuffer(index) ?: return
                buf.clear()
                val n = minOf(pcm.size - pos, buf.remaining())
                buf.put(pcm, pos, n)
                codec.queueInputBuffer(index, 0, n, ptsUs, 0)
                ptsUs += n / (2L * channels) * 1_000_000L / rate
                pos += n
            }
        } catch (e: Exception) {
            Log.w("AudioEncoder", "feed failed", e)
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try { codec.dequeueOutputBuffer(info, 10_000) } catch (e: IllegalStateException) { break }
            if (index < 0) continue
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(data)
                    sink(info.presentationTimeUs, data)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                break
            } catch (e: Exception) {
                Log.w("AudioEncoder", "sink failed", e)
            }
        }
    }

    fun release() {
        running = false
        drainThread.join(300)
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
