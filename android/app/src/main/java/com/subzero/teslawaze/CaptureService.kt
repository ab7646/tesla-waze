package com.subzero.teslawaze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Foreground service that mirrors the phone screen: MediaProjection -> virtual display
 * -> hardware H.264 encoder -> relay WebSocket -> car browser.
 */
class CaptureService : Service(), RelayClient.Listener {

    companion object {
        const val ACTION_STOP = "com.subzero.teslawaze.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
        private const val MAX_SIDE = 1280
        private const val MAX_QUEUE_BYTES = 768L * 1024

        var status: String
            get() = AppLog.status
            set(value) { AppLog.status = value }
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var encoder: H264Encoder? = null
    private var relay: RelayClient? = null
    @Volatile private var screenPresent = false
    @Volatile private var dropping = false
    @Volatile private var sentFrames = 0
    @Volatile private var sentBytes = 0L

    private val statsTick = object : Runnable {
        override fun run() {
            if (screenPresent) {
                // ⁦…⁩ isolates the left-to-right numbers inside the Hebrew line.
                status = "משדר לטסלה: ⁦$sentFrames fps · ${sentBytes * 8 / 1000} kbps⁩"
            }
            sentFrames = 0
            sentBytes = 0
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        // Android 14+: must be in the foreground with the mediaProjection type before getMediaProjection().
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) return fail("חסרה הרשאת צילום מסך")
        if (BuildConfig.RELAY_URL.isEmpty()) return fail("שרת הממסר לא הוגדר בבנייה הזו")

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: return fail("לא ניתן להתחיל צילום מסך")
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        val size = targetSize()
        val enc = H264Encoder(size.width, size.height, ::onEncoded).also { it.start() }
        encoder = enc
        display = p.createVirtualDisplay(
            "tesla-waze", size.width, size.height, size.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, enc.surface, null, null,
        )

        val url = BuildConfig.RELAY_URL.trimEnd('/') + "/room/" + Prefs.roomId(this) + "?role=phone"
        relay = RelayClient(url, this).also { it.connect() }
        status = "מתחבר לשרת…"
        main.postDelayed(statsTick, 1000)
        return START_NOT_STICKY
    }

    private fun fail(message: String): Int {
        status = message
        stopSelf()
        return START_NOT_STICKY
    }

    private data class Size(val width: Int, val height: Int, val dpi: Int)

    private fun targetSize(): Size {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(m.widthPixels, m.heightPixels))
        fun align16(v: Float) = maxOf(16, (v / 16f).roundToInt() * 16)
        return Size(
            align16(m.widthPixels * scale),
            align16(m.heightPixels * scale),
            maxOf(120, (m.densityDpi * scale).roundToInt()),
        )
    }

    /** On rotation, swap in a new encoder sized for the new orientation (the projection's display is reused). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val vd = display ?: return
        val old = encoder ?: return
        val size = targetSize()
        if (size.width == old.width && size.height == old.height) return
        val enc = H264Encoder(size.width, size.height, ::onEncoded).also { it.start() }
        encoder = enc
        vd.resize(size.width, size.height, size.dpi)
        vd.surface = enc.surface
        old.stop()
    }

    private fun onEncoded(enc: H264Encoder, type: Int, ptsUs: Long, data: ByteArray) {
        if (enc !== encoder || !screenPresent) return
        val r = relay ?: return
        when (type) {
            H264Encoder.DELTA -> {
                if (dropping) return
                if (r.queued() > MAX_QUEUE_BYTES) {
                    // Network can't keep up: skip frames until the next key frame.
                    dropping = true
                    enc.requestKeyFrame()
                    return
                }
            }
            H264Encoder.KEY -> dropping = false
        }
        sendFrame(r, enc, type, ptsUs, data)
    }

    private fun sendFrame(r: RelayClient, enc: H264Encoder, type: Int, ptsUs: Long, data: ByteArray) {
        val packet = ByteBuffer.allocate(13 + data.size)
            .put(type.toByte())
            .putLong(ptsUs)
            .putShort(enc.width.toShort())
            .putShort(enc.height.toShort())
            .put(data)
            .array()
        if (r.send(packet)) {
            sentFrames++
            sentBytes += packet.size
        }
    }

    // ---- RelayClient.Listener (called on OkHttp threads) ----

    override fun onPeer(present: Boolean) {
        screenPresent = present
        status = if (present) "הטסלה מחוברת. משדר…" else "מחובר לשרת. ממתין לטסלה…"
        if (present) {
            val enc = encoder ?: return
            val r = relay ?: return
            enc.config?.let { sendFrame(r, enc, H264Encoder.CONFIG, 0, it) }
            enc.requestKeyFrame()
        }
    }

    override fun onControl(message: JSONObject) {
        when (message.optString("type")) {
            "keyframe" -> encoder?.requestKeyFrame()
        }
    }

    override fun onState(text: String) {
        if (!screenPresent) status = text
    }

    // ---- lifecycle ----

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "הקרנה לטסלה", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Waze לטסלה")
            .setContentText("ההקרנה פעילה")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "עצור", stopIntent,
                ).build(),
            )
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        screenPresent = false
        relay?.close()
        relay = null
        display?.release()
        display = null
        encoder?.stop()
        encoder = null
        val p = projection
        projection = null
        p?.stop()
        status = "ההקרנה הופסקה"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
