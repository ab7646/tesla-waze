package com.subzero.teslawaze.aa

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer

/**
 * Plays PCM that Android Auto sends to the "car" on the phone itself, so it reaches the Tesla
 * over the phone's normal Bluetooth audio connection.
 */
class AudioOut(sampleRate: Int, channels: Int, usage: Int) {
    private val track: AudioTrack

    init {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(
                        if (usage == AudioAttributes.USAGE_MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC
                        else AudioAttributes.CONTENT_TYPE_SPEECH,
                    )
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min * 4, sampleRate * channels)) // ~0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun start() {
        try { track.play() } catch (_: Exception) {}
    }

    fun stop() {
        try { track.pause(); track.flush() } catch (_: Exception) {}
    }

    /** Never blocks the protocol thread; excess audio is dropped. */
    fun write(pcm: ByteArray, offset: Int) {
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            track.write(ByteBuffer.wrap(pcm, offset, pcm.size - offset), pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: Exception) {}
    }

    fun release() {
        try { track.release() } catch (_: Exception) {}
    }
}
