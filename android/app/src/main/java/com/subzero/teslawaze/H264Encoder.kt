package com.subzero.teslawaze

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * Hardware H.264 encoder fed by a Surface (the MediaProjection virtual display).
 * Output is Annex-B; key frames are emitted with SPS/PPS prepended so a decoder
 * can start from any key frame.
 */
class H264Encoder(
    val width: Int,
    val height: Int,
    private val sink: (encoder: H264Encoder, type: Int, ptsUs: Long, data: ByteArray) -> Unit,
) {
    companion object {
        const val CONFIG = 1
        const val KEY = 2
        const val DELTA = 3
        private const val TAG = "H264Encoder"
    }

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    lateinit var surface: Surface
        private set
    @Volatile var config: ByteArray? = null
        private set
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Keep frames flowing when the screen is static, so the car view never freezes.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // The S24 screen runs at up to 120 Hz; navigation needs no more than 30 fps.
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30f)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "configure with low-latency failed, retrying without", e)
            codec.reset()
            format.removeKey(MediaFormat.KEY_LOW_LATENCY)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        surface = codec.createInputSurface()
        codec.start()
        running = true
        thread = Thread({ drain() }, "h264-drain").also { it.start() }
    }

    fun requestKeyFrame() {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "key frame request failed", e)
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                buffer.get(data)
                try {
                    when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                            config = data
                            sink(this, CONFIG, info.presentationTimeUs, data)
                        }
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ->
                            sink(this, KEY, info.presentationTimeUs, (config ?: ByteArray(0)) + data)
                        else -> sink(this, DELTA, info.presentationTimeUs, data)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "sink failed", e)
                }
            }
            try {
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                break
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        surface.release()
    }
}
