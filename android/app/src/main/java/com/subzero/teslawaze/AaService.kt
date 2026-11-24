package com.subzero.teslawaze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.IBinder
import com.subzero.teslawaze.aa.AudioEncoder
import com.subzero.teslawaze.aa.HeadUnit
import com.subzero.teslawaze.aa.HuCert
import com.subzero.teslawaze.aa.Transcoder
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
        const val ACTION_LOCAL_ON = "com.subzero.teslawaze.LOCAL_ON"
        const val ACTION_LOCAL_OFF = "com.subzero.teslawaze.LOCAL_OFF"
        const val LOCAL_PORT = 8080
        private const val CHANNEL = "android_auto"
        private const val MAX_QUEUE_BYTES = 512L * 1024
        private const val RECOVER_QUEUE_BYTES = 64L * 1024
        private const val START_BITRATE = 1_500_000
        private const val MIN_BITRATE = 500_000
        private const val MAX_BITRATE = 3_000_000
        @Volatile var running = false
            private set
        /** True after a connection attempt found Android Auto's head unit server not listening. */
        @Volatile var serverMissing = false
            private set
        /** Local hotspot server state, read by the UI. */
        @Volatile var localActive = false
            private set
        @Volatile var localUrl: String? = null
            private set

        /**
         * The phone's own address on its Wi-Fi hotspot (the Tesla's gateway), as a URL to open in
         * the car — or null when no hotspot/AP interface is up. Used both to bind-time report and
         * for the UI to tell whether a hotspot is on.
         */
        fun hotspotUrl(): String? {
            return try {
                var best: String? = null
                for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    val name = nif.name.lowercase()
                    for (addr in nif.inetAddresses) {
                        if (addr is java.net.Inet4Address && addr.isSiteLocalAddress) {
                            val ip = addr.hostAddress ?: continue
                            val apLike = name.contains("ap") || name.contains("wlan") ||
                                name.contains("swlan") || name.contains("softap")
                            if (apLike) return "http://$ip:$LOCAL_PORT"
                            if (ip.startsWith("192.168.")) best = ip
                        }
                    }
                }
                best?.let { "http://$it:$LOCAL_PORT" }
            } catch (e: Exception) {
                null
            }
        }
    }

    private var relay: RelayClient? = null
    @Volatile private var headUnit: HeadUnit? = null
    private var worker: Thread? = null
    @Volatile private var screenPresent = false
    @Volatile private var codecConfig: ByteArray? = null
    @Volatile private var needKey = true
    @Volatile private var congested = false
    @Volatile private var sentFrames = 0
    @Volatile private var transcoder: Transcoder? = null
    @Volatile private var bitRate = START_BITRATE
    private var bitrateThread: Thread? = null
    @Volatile private var localServer: LocalServer? = null
    @Volatile private var relayPresent = false
    @Volatile private var localPresent = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_LOCAL_OFF -> { stopLocal(); return START_NOT_STICKY }
        }
        val wantLocal = intent?.action == ACTION_LOCAL_ON
        if (running) {
            if (wantLocal) startLocal()
            return START_NOT_STICKY
        }
        // Claim the microphone foreground-service type only when the user granted RECORD_AUDIO,
        // otherwise startForeground throws and the whole service dies. Without it, voice is just off.
        var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(2, buildNotification(), fgsType)

        if (!HuCert.isInstalled(this)) return fail("לא נטענה תעודת יחידת ראש")
        if (BuildConfig.RELAY_URL.isEmpty()) return fail("שרת הממסר לא הוגדר בבנייה הזו")

        running = true
        val url = BuildConfig.RELAY_URL.trimEnd('/') + "/room/" + Prefs.roomId(this) + "?role=phone"
        relay = RelayClient(url, this).also { it.connect() }
        worker = Thread({ loop() }, "android-auto").also { it.start() }
        AppLog.status = "מצב אנדרואיד אוטו פעיל"
        if (wantLocal) startLocal()
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
        // Android Auto dropped the minimal car description (video + touch + sensors only), so
        // start with the full one that real head units send; if that is rejected too, alternate.
        var full = true
        while (running) {
            var hu: HeadUnit? = null
            try {
                hu = HeadUnit(HuCert.keyManagers(this), this, full)
                headUnit = hu
                serverMissing = false
                needKey = true
                hu.run()
                AppLog.add("החיבור ל-Android Auto הסתיים")
            } catch (e: ConnectException) {
                serverMissing = true
                val msg = "Android Auto לא מאזין. בתפריט של Android Auto בחר \"Start head unit server\""
                if (msg != lastError) AppLog.add(msg)
                lastError = msg
                AppLog.status = msg
            } catch (e: Exception) {
                if (!running) break
                val cause = e.cause?.let { " ← ${it.javaClass.simpleName}: ${it.message}" } ?: ""
                AppLog.add("שגיאה: ${e.javaClass.simpleName}: ${e.message}$cause")
                lastError = ""
            } finally {
                headUnit?.close()
                headUnit = null
            }
            if (hu != null && hu.sdSent && hu.channelsOpened == 0) full = !full
            if (running) try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
        }
    }

    // ---- HeadUnit.Listener (Android Auto worker thread) ----
    // Android Auto's own stream runs at 4-5 Mbps; it is decoded and re-encoded here at a bitrate
    // that follows what the mobile link actually carries.

    private fun transcoder(): Transcoder = synchronized(this) {
        transcoder ?: Transcoder(HeadUnit.VIDEO_W, HeadUnit.VIDEO_H, bitRate, ::onEncoded).also {
            transcoder = it
            bitrateThread = Thread({ adaptBitrate() }, "aa-bitrate").also { t -> t.start() }
        }
    }

    override fun onVideoConfig(data: ByteArray) {
        AppLog.status = if (screenPresent) "משדר לטסלה" else "Android Auto מחובר, ממתין לטסלה"
        transcoder().config(data)
    }

    override fun onVideoFrame(ptsUs: Long, data: ByteArray) {
        transcoder().frame(data)
    }

    /** Output of our own encoder (its drain thread). Key frames already carry SPS/PPS. */
    private fun onEncoded(@Suppress("UNUSED_PARAMETER") enc: H264Encoder, type: Int, ptsUs: Long, data: ByteArray) {
        when (type) {
            H264Encoder.CONFIG -> {
                codecConfig = data
                if (screenPresent) sendFrame(1, 0, data)
            }
            else -> {
                if (!screenPresent) return
                val r = relay ?: return
                val q = r.queued()
                if (congested) {
                    // Wait until what is already queued has gone out, then restart from one key frame.
                    // (Asking for key frames while the queue is still full only piles on more data.)
                    if (q > RECOVER_QUEUE_BYTES) return
                    congested = false
                    needKey = true
                    transcoder?.requestKeyFrame()
                    AppLog.add("החיבור לרכב התאושש")
                }
                if (q > MAX_QUEUE_BYTES) {
                    congested = true
                    needKey = true
                    setBitrate(maxOf(MIN_BITRATE, bitRate / 2))
                    AppLog.add("החיבור לרכב איטי, ממתין שיתפנה (${q / 1024} KB בתור)")
                    return
                }
                if (type == H264Encoder.KEY) {
                    needKey = false
                    sendFrame(2, ptsUs, data)
                } else if (!needKey) {
                    sendFrame(3, ptsUs, data)
                }
            }
        }
    }

    private fun setBitrate(next: Int) {
        if (next == bitRate) return
        if (next < bitRate) AppLog.add("מוריד איכות ל-${next / 1000} kbps")
        bitRate = next
        transcoder?.setBitrate(next)
    }

    // ---- audio to the car ----
    // Android Auto's audio goes to the Tesla page (Opus over the relay) while the page is open;
    // otherwise it plays on the phone as before.

    private val audioEncoders = HashMap<Int, AudioEncoder>()
    @Volatile private var audioFailed = false

    override fun onAudio(channel: Int, rate: Int, channels: Int, pcm: ByteArray, offset: Int): Boolean {
        var taken = false
        // Local hotspot: send raw PCM — Wi-Fi has the bandwidth, and the car plays it with the Web
        // Audio API, so no Opus and no WebCodecs are needed over plain http.
        if (localPresent) {
            sendPcmLocal(channel, rate, channels, pcm, offset)
            taken = true
        }
        // Relay (internet): Opus-encode to fit the mobile uplink.
        val r = relay
        if (relayPresent && r != null && !audioFailed) {
            val enc = synchronized(audioEncoders) {
                audioEncoders[channel] ?: try {
                    AudioEncoder(rate, channels, if (channels == 2) 64_000 else 24_000) { pts, data ->
                        sendAudio(channel, rate, channels, pts, data)
                    }.also { audioEncoders[channel] = it }
                } catch (e: Exception) {
                    audioFailed = true
                    AppLog.add("אין מקודד שמע (${e.javaClass.simpleName}), השמע יישאר בטלפון")
                    null
                }
            }
            if (enc != null) {
                if (r.queued() <= MAX_QUEUE_BYTES) enc.feed(pcm, offset) // a clogged link drops audio, not video
                taken = true
            }
        }
        return taken
    }

    /**
     * Night mode follows the phone's current dark-theme state. Set the phone's dark theme to run
     * from sunset to sunrise and the map darkens with it; refreshNight() (polled below) pushes the
     * change to Android Auto when it flips during a drive.
     */
    override fun isNight(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun sendAudio(channel: Int, rate: Int, channels: Int, ptsUs: Long, data: ByteArray) {
        val packet = ByteBuffer.allocate(13 + data.size)
            .put(4.toByte())
            .putLong(ptsUs)
            .putShort(rate.toShort())
            .put(channels.toByte())
            .put(channel.toByte())
            .put(data)
            .array()
        relay?.send(packet)
    }

    /** Lowers the bitrate quickly when data piles up on the phone, raises it slowly when the link is clear. */
    private fun adaptBitrate() {
        var calm = 0
        while (running) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
            headUnit?.refreshNight() // follow day/night even while parked / between screens
            if (localActive && localUrl == null) localUrl = hotspotUrl() // hotspot may come up late
            if (!screenPresent) continue
            val q = relay?.queued() ?: continue
            var next = bitRate
            if (q > 150_000) {
                next = maxOf(MIN_BITRATE, (bitRate * 0.7).toInt())
                calm = 0
            } else if (q < 20_000 && ++calm >= 5) {
                next = minOf(MAX_BITRATE, (bitRate * 1.15).toInt())
                calm = 0
            }
            setBitrate(next)
            AppLog.status = "משדר לטסלה · ${bitRate / 1000} kbps"
        }
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
        localServer?.broadcast(packet)
    }

    // ---- local hotspot server (direct Tesla <-> phone over Wi-Fi, no internet) ----

    private fun startLocal() {
        if (localServer != null) { if (localUrl == null) localUrl = hotspotUrl(); return }
        try {
            val srv = LocalServer(applicationContext, LOCAL_PORT, ::onLocalScreens, ::onControl)
            srv.start(0, false) // 0 = no socket read timeout, so a quiet WebSocket stays open
            localServer = srv
            localActive = true
            localUrl = hotspotUrl()
            AppLog.add("שרת מקומי פעיל" + (localUrl?.let { " · $it" } ?: " (הפעל נקודה חמה כדי לקבל כתובת)"))
        } catch (e: Exception) {
            AppLog.add("הפעלת שרת מקומי נכשלה: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun stopLocal() {
        localServer?.let { try { it.stop() } catch (_: Exception) {} }
        localServer = null
        localActive = false
        localUrl = null
        localPresent = false
        updateScreen()
        AppLog.add("שרת מקומי כבוי")
    }

    private fun sendPcmLocal(channel: Int, rate: Int, channels: Int, pcm: ByteArray, offset: Int) {
        val len = pcm.size - offset
        if (len <= 0) return
        val packet = ByteBuffer.allocate(13 + len)
            .put(4.toByte())
            .putLong(System.nanoTime() / 1000)
            .putShort(rate.toShort())
            .put(channels.toByte())
            .put(channel.toByte())
            .put(pcm, offset, len)
            .array()
        localServer?.broadcast(packet)
    }

    // ---- RelayClient.Listener (OkHttp threads) ----

    override fun onPeer(present: Boolean) {
        if (present != relayPresent) AppLog.add(if (present) "הטסלה התחברה (אינטרנט)" else "הטסלה התנתקה (אינטרנט)")
        relayPresent = present
        updateScreen()
    }

    /** A Tesla screen connected or left over the local hotspot server (its own thread). */
    private fun onLocalScreens(count: Int) {
        val present = count > 0
        if (present != localPresent) AppLog.add(if (present) "הטסלה התחברה (מקומי)" else "הטסלה התנתקה (מקומי)")
        localPresent = present
        updateScreen()
    }

    /** Folds the relay and local screens into one "is anyone watching" state. */
    private fun updateScreen() {
        val now = relayPresent || localPresent
        val was = screenPresent
        screenPresent = now
        AppLog.status = if (now) "הטסלה מחוברת" else "ממתין לטסלה"
        if (now && !was) {
            needKey = true
            congested = false
            codecConfig?.let { sendFrame(1, 0, it) }
            transcoder?.requestKeyFrame()
        }
    }

    override fun onControl(message: JSONObject) {
        when (message.optString("type")) {
            "keyframe" -> {
                AppLog.add("המסך ביקש תמונה מלאה")
                transcoder?.requestKeyFrame()
            }
            "voice" -> {
                AppLog.add("הרכב ביקש עוזר קולי")
                try { headUnit?.sendVoiceAssistant() } catch (e: Exception) {
                    AppLog.add("הפעלת עוזר קולי נכשלה: ${e.message}")
                }
            }
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
        bitrateThread?.interrupt()
        transcoder?.release()
        transcoder = null
        synchronized(audioEncoders) {
            audioEncoders.values.forEach { it.release() }
            audioEncoders.clear()
        }
        relay?.close()
        relay = null
        stopLocal()
        screenPresent = false
        AppLog.status = "מצב אנדרואיד אוטו הופסק"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
