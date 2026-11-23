package com.subzero.teslawaze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import com.subzero.teslawaze.aa.HeadUnit
import com.subzero.teslawaze.aa.HuCert
import org.json.JSONObject
import java.net.ConnectException
import java.nio.ByteBuffer

/**
 * "Android Auto mode": acts as a car head unit for the phone's own Android Auto app, and bridges
 * its video to the Tesla browser through the relay. The phone screen stays free.
 */
class AaService : Service(), RelayClient.Listener, HeadUnit.Listener {

    companion object {
        const val ACTION_STOP = "com.subzero.teslawaze.AA_STOP"
        private const val CHANNEL = "android_auto"
        private const val MAX_QUEUE_BYTES = 768L * 1024
        @Volatile var running = false
            private set
    }

    private var relay: RelayClient? = null
    @Volatile private var headUnit: HeadUnit? = null
    private var worker: Thread? = null
    @Volatile private var screenPresent = false
    @Volatile private var codecConfig: ByteArray? = null
    @Volatile private var needKey = true
    @Volatile private var sentFrames = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startForeground(2, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        if (!HuCert.isInstalled(this)) return fail("לא נטענה תעודת יחידת ראש")
        if (BuildConfig.RELAY_URL.isEmpty()) return fail("שרת הממסר לא הוגדר בבנייה הזו")

        running = true
        val url = BuildConfig.RELAY_URL.trimEnd('/') + "/room/" + Prefs.roomId(this) + "?role=phone"
        relay = RelayClient(url, this).also { it.connect() }
        worker = Thread({ loop() }, "android-auto").also { it.start() }
        AppLog.status = "מצב אנדרואיד אוטו פעיל"
        return START_NOT_STICKY
    }

    private fun fail(message: String): Int {
        AppLog.status = message
        AppLog.add(message)
        stopSelf()
        return START_NOT_STICKY
    }

    /** Keeps (re)connecting to Android Auto's head unit server while the service runs. */
    private fun loop() {
        var lastError = ""
        while (running) {
            try {
                val hu = HeadUnit(HuCert.keyManagers(this), this)
                headUnit = hu
                codecConfig = null
                needKey = true
                hu.run()
                AppLog.add("החיבור ל-Android Auto הסתיים")
            } catch (e: ConnectException) {
                val msg = "Android Auto לא מאזין. בתפריט של Android Auto בחר \"Start head unit server\""
                if (msg != lastError) AppLog.add(msg)
                lastError = msg
                AppLog.status = msg
            } catch (e: Exception) {
                if (!running) break
                val msg = "שגיאה: ${e.javaClass.simpleName}: ${e.message}"
                if (msg != lastError) AppLog.add(msg)
                lastError = msg
            } finally {
                headUnit?.close()
                headUnit = null
            }
            if (running) try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
        }
    }

    // ---- HeadUnit.Listener (Android Auto worker thread) ----

    override fun onVideoConfig(data: ByteArray) {
        codecConfig = data
        needKey = true
        AppLog.status = if (screenPresent) "משדר לטסלה" else "Android Auto מחובר, ממתין לטסלה"
        if (screenPresent) sendFrame(1, 0, data)
    }

    override fun onVideoFrame(ptsUs: Long, data: ByteArray) {
        if (!screenPresent) return
        val r = relay ?: return
        val key = containsIdr(data)
        if (key) {
            needKey = false
            val config = codecConfig
            sendFrame(2, ptsUs, if (config != null && !startsWithSps(data)) config + data else data)
            return
        }
        if (needKey) return
        if (r.queued() > MAX_QUEUE_BYTES) {
            needKey = true
            headUnit?.requestKeyFrame()
            return
        }
        sendFrame(3, ptsUs, data)
    }

    private fun sendFrame(type: Int, ptsUs: Long, data: ByteArray) {
        val packet = ByteBuffer.allocate(13 + data.size)
            .put(type.toByte())
            .putLong(ptsUs)
            .putShort(HeadUnit.VIDEO_W.toShort())
            .putShort(HeadUnit.VIDEO_H.toShort())
            .put(data)
            .array()
        if (relay?.send(packet) == true) sentFrames++
    }

    private fun nalTypes(data: ByteArray): Sequence<Int> = sequence {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val start = when {
                    data[i + 2].toInt() == 1 -> i + 3
                    data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> i + 4
                    else -> -1
                }
                if (start in 0 until data.size) {
                    yield(data[start].toInt() and 0x1F)
                    i = start
                    continue
                }
            }
            i++
        }
    }

    private fun containsIdr(data: ByteArray) = nalTypes(data).any { it == 5 }
    private fun startsWithSps(data: ByteArray) = nalTypes(data).firstOrNull() == 7

    // ---- RelayClient.Listener (OkHttp threads) ----

    override fun onPeer(present: Boolean) {
        screenPresent = present
        needKey = true
        AppLog.add(if (present) "הטסלה התחברה" else "הטסלה התנתקה")
        AppLog.status = if (present) "הטסלה מחוברת" else "ממתין לטסלה"
        if (present) {
            codecConfig?.let { sendFrame(1, 0, it) }
            headUnit?.requestKeyFrame()
        }
    }

    override fun onControl(message: JSONObject) {
        when (message.optString("type")) {
            "keyframe" -> headUnit?.requestKeyFrame()
            "touch" -> {
                val hu = headUnit ?: return
                val arr = message.optJSONArray("p") ?: return
                val pointers = (0 until arr.length()).mapNotNull { i ->
                    val p = arr.optJSONArray(i) ?: return@mapNotNull null
                    Triple(p.optInt(0), p.optInt(1), p.optInt(2))
                }
                try {
                    hu.sendTouch(message.optInt("a"), message.optInt("i"), pointers)
                } catch (e: Exception) {
                    AppLog.add("שליחת מגע נכשלה: ${e.message}")
                }
            }
        }
    }

    override fun onState(text: String) {
        if (!screenPresent) AppLog.add(text)
    }

    // ---- lifecycle ----

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "אנדרואיד אוטו לטסלה", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, AaService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Waze לטסלה")
            .setContentText("מצב אנדרואיד אוטו פעיל")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "עצור", stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        headUnit?.close()
        worker?.interrupt()
        relay?.close()
        relay = null
        screenPresent = false
        AppLog.status = "מצב אנדרואיד אוטו הופסק"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
