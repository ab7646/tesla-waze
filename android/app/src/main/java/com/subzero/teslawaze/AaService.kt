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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
        const val ACTION_LOHS_ON = "com.subzero.teslawaze.LOHS_ON"
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
        /** Live telemetry for the UI: frames per second sent, and the current encode bitrate (kbps). */
        @Volatile var fps = 0
            private set
        @Volatile var kbps = 0
            private set
        /** Day/night mode pushed to Android Auto: 0 = follow phone theme, 1 = force day, 2 = force night. */
        @Volatile var nightMode = 0
        /** Local-Only Hotspot the app itself opens (private AP the Tesla can actually reach). */
        @Volatile var lohsSsid: String? = null
        @Volatile var lohsPass: String? = null
        @Volatile var lohsError: String? = null
        /** Diagnostics: the last inbound TCP connection we saw (who reached us, and on which of our
         *  own addresses), so we can tell whether the Tesla's packets arrive at the phone at all. */
        @Volatile var lastInbound: String? = null

        /**
         * The phone's own address on its Wi-Fi hotspot (the Tesla's gateway), as a URL to open in
         * the car — or null when no hotspot/AP interface is up. Used both to bind-time report and
         * for the UI to tell whether a hotspot is on.
         */
        /** One IPv4 address the phone holds, with its interface name and a score (higher = more
         *  likely the hotspot gateway the Tesla should open). */
        private data class Cand(val iface: String, val ip: String, val score: Int)

        private fun candidates(): List<Cand> {
            val out = ArrayList<Cand>()
            try {
                for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    val name = nif.name.lowercase()
                    // Hotspot/AP interfaces are named ap0 / swlan0 / softap… / wlan1; tethering also
                    // uses rndis/usb. The normal client Wi-Fi is wlan0; cellular is rmnet*/ccmni*.
                    val apName = name.startsWith("ap") || name.contains("swlan") || name.contains("softap") ||
                        name == "wlan1" || name.startsWith("rndis") || name.startsWith("usb")
                    val cell = name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
                    for (addr in nif.inetAddresses) {
                        if (addr !is java.net.Inet4Address || !addr.isSiteLocalAddress) continue
                        val ip = addr.hostAddress ?: continue
                        // The phone-as-AP is the gateway: its address ends in .1 and sits in a private
                        // LAN range. A 10.x that is NOT .1 is almost always the carrier (cellular) IP.
                        val isDotOne = Regex("""\.1$""").containsMatchIn(ip)
                        val lan192 = ip.startsWith("192.168.")
                        val lan172 = Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(ip)
                        val lan10 = ip.startsWith("10.")
                        var score = 0
                        if (apName) score += 100
                        if (cell) score -= 100
                        if (isDotOne && (lan192 || lan172)) score += 60
                        if (isDotOne && lan10) score += 25
                        if (lan192 || lan172) score += 20
                        if (!isDotOne && lan10) score -= 30 // looks like a CGNAT cellular client address
                        out.add(Cand(nif.name, ip, score))
                    }
                }
            } catch (_: Exception) {}
            // Samsung (and some others) hide the soft-AP interface from NetworkInterface, so the
            // address loop above finds nothing useful. Recover the hotspot subnet from the kernel
            // routing table and the ARP table, which stay readable, and offer its .1 gateway.
            out.addAll(routeGateways())
            out.addAll(arpGateways())
            // De-duplicate by IP, keeping the highest score for each.
            val best = LinkedHashMap<String, Cand>()
            for (c in out) {
                val ex = best[c.ip]
                if (ex == null || c.score > ex.score) best[c.ip] = c
            }
            return best.values.sortedByDescending { it.score }
        }

        /** 32-bit little-endian hex (the form the proc-net tables store addresses in) to dotted IPv4. */
        private fun leHexToIp(hex: String): String? = try {
            val v = hex.toLong(16)
            "${v and 0xFF}.${(v shr 8) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 24) and 0xFF}"
        } catch (_: Exception) { null }

        private fun isLan(ip: String): Int = when {
            ip.startsWith("192.168.") -> 192
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(ip) -> 172
            ip.startsWith("10.") -> 10
            else -> 0
        }

        private fun isCell(name: String): Boolean =
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")

        private fun isApName(name: String): Boolean =
            name.startsWith("ap") || name.contains("swlan") || name.contains("softap") ||
                name == "wlan1" || name.startsWith("rndis") || name.startsWith("usb")

        /** Gateway candidates from /proc/net/route — reveals the hotspot subnet even when its
         *  interface is invisible to NetworkInterface. Each non-default LAN route yields its .1. */
        private fun routeGateways(): List<Cand> {
            val out = ArrayList<Cand>()
            try {
                java.io.File("/proc/net/route").forEachLine { line ->
                    val f = line.trim().split(Regex("\\s+"))
                    if (f.size < 8 || f[0].equals("Iface", true)) return@forEachLine
                    val name = f[0].lowercase()
                    if (f[1].equals("00000000", true)) return@forEachLine // default route
                    if (isCell(name)) return@forEachLine
                    val net = leHexToIp(f[1]) ?: return@forEachLine
                    val lan = isLan(net)
                    if (lan == 0) return@forEachLine
                    val p = net.split(".")
                    if (p.size != 4) return@forEachLine
                    var score = 40
                    if (isApName(name)) score += 80
                    if (lan == 192 || lan == 172) score += 20
                    out.add(Cand(f[0], "${p[0]}.${p[1]}.${p[2]}.1", score))
                }
            } catch (_: Exception) {}
            return out
        }

        /** Gateway candidates inferred from /proc/net/arp — once the Tesla has associated, its
         *  address appears here and tells us the hotspot subnet. */
        private fun arpGateways(): List<Cand> {
            val out = ArrayList<Cand>()
            try {
                java.io.File("/proc/net/arp").forEachLine { line ->
                    val f = line.trim().split(Regex("\\s+"))
                    if (f.size < 6 || f[0].equals("IP", true)) return@forEachLine
                    val name = f[5].lowercase()
                    if (isCell(name)) return@forEachLine
                    val lan = isLan(f[0])
                    if (lan == 0) return@forEachLine
                    val p = f[0].split(".")
                    if (p.size != 4) return@forEachLine
                    out.add(Cand("${f[5]} (arp)", "${p[0]}.${p[1]}.${p[2]}.1", 55))
                }
            } catch (_: Exception) {}
            return out
        }

        /** The phone's own hotspot address, as a URL for the Tesla to open — best guess, or null. */
        fun hotspotUrl(): String? {
            val best = candidates().firstOrNull() ?: return null
            // Don't hand back an address that scores as a cellular IP; there's no real hotspot then.
            if (best.score < 0) return null
            return "http://${best.ip}:$LOCAL_PORT"
        }

        /** Every candidate as "iface → http://ip:port", best first — shown so the user can try each. */
        fun localCandidates(): List<String> =
            candidates().map { "${it.iface}  →  http://${it.ip}:$LOCAL_PORT" }
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
    private var lohsReservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var probe: java.net.ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_LOCAL_OFF -> { stopLocal(); return START_NOT_STICKY }
        }
        val wantLocal = intent?.action == ACTION_LOCAL_ON || intent?.action == ACTION_LOHS_ON
        val wantLohs = intent?.action == ACTION_LOHS_ON
        if (running) {
            if (wantLocal) startLocal(wantLohs)
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
        if (wantLocal) startLocal(wantLohs)
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
     * Night mode: a manual override (nightMode 1/2 = force day/night) wins, otherwise it follows the
     * phone's current dark-theme state. refreshNight() (polled every second) pushes a change to
     * Android Auto within ~1s, so flipping the in-app toggle re-colours Waze's map on the next tick.
     */
    override fun isNight(): Boolean {
        when (nightMode) {
            1 -> return false
            2 -> return true
        }
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
            val sampled = sentFrames; sentFrames = 0; fps = sampled // frames sent in the last second
            kbps = if (screenPresent) bitRate / 1000 else 0
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

    private fun startLocal(withLohs: Boolean) {
        if (localServer == null) {
            try {
                val srv = LocalServer(applicationContext, LOCAL_PORT, ::onLocalScreens, ::onControl)
                srv.start(0, false) // 0 = no socket read timeout, so a quiet WebSocket stays open
                localServer = srv
                localActive = true
                AppLog.add("שרת מקומי פעיל")
                startProbe()
            } catch (e: Exception) {
                AppLog.add("הפעלת שרת מקומי נכשלה: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }
        // withLohs: open the app's own private AP. Otherwise just run the server and rely on whatever
        // Wi-Fi the phone is already joined to (e.g. a personal phone's hotspot) to carry the traffic.
        if (withLohs) startLohs()
        localUrl = hotspotUrl()
    }

    /**
     * Opens the app's own Local-Only Hotspot: a private, system-managed Wi-Fi AP (192.168.49.x, the
     * phone is the gateway) with no carrier involvement and no client isolation, so the Tesla can
     * actually reach the on-device server — unlike the managed Settings hotspot. Android returns a
     * random SSID + passphrase, which the UI shows for the user to enter in the car.
     */
    private fun startLohs() {
        if (lohsReservation != null) return
        // API 33+ gates Local-Only Hotspot on NEARBY_WIFI_DEVICES; older versions on fine location.
        val needed = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(needed) != PackageManager.PERMISSION_GRANTED) {
            lohsError = "חסרה הרשאה (${if (Build.VERSION.SDK_INT >= 33) "מכשירים סמוכים" else "מיקום"}) — אשר אותה לאפליקציה"
            AppLog.add(lohsError!!)
            return
        }
        val wifi = getSystemService(WifiManager::class.java) ?: run {
            lohsError = "Wi-Fi לא זמין"; return
        }
        lohsError = null
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    lohsReservation = reservation
                    val cfg = reservation.softApConfiguration
                    // getSsid() is deprecated (and may return null) on API 33+, so prefer getWifiSsid().
                    val ssid = if (Build.VERSION.SDK_INT >= 33) {
                        cfg.wifiSsid?.toString()?.trim('"')
                    } else {
                        @Suppress("DEPRECATION") cfg.ssid
                    }
                    lohsSsid = ssid
                    lohsPass = cfg.passphrase
                    lohsError = null
                    localUrl = hotspotUrl()
                    AppLog.add("נקודה חמה מקומית פעילה · רשת: $lohsSsid")
                }

                override fun onStopped() {
                    lohsReservation = null
                    lohsSsid = null
                    lohsPass = null
                    AppLog.add("נקודה חמה מקומית נעצרה")
                }

                override fun onFailed(reason: Int) {
                    lohsReservation = null
                    val hint = when (reason) {
                        3 -> " — כבה קודם את ההוטספוט הרגיל"            // ERROR_INCOMPATIBLE_MODE
                        4 -> " — חסום ע\"י הטלפון (מדיניות מעסיק)"      // ERROR_TETHERING_DISALLOWED
                        1 -> " — אין ערוץ פנוי, ודא ש-Wi-Fi דלוק"      // ERROR_NO_CHANNEL
                        else -> ""
                    }
                    lohsError = "פתיחת נקודה חמה מקומית נכשלה (קוד $reason)$hint"
                    AppLog.add(lohsError!!)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            lohsError = "שגיאה בפתיחת נקודה חמה מקומית: ${e.message}"
            AppLog.add(lohsError!!)
        }
    }

    /**
     * A bare TCP listener on LOCAL_PORT+1 that answers any connection with a plain-text page and
     * records who reached it. It shares nothing with NanoHTTPD, so if the Tesla can open :8081 but
     * not :8080 the fault is ours; if it cannot open :8081 either, the hotspot itself is blocking
     * the phone (carrier passthrough / client isolation).
     */
    private fun startProbe() {
        if (probe != null) return
        try {
            val sock = java.net.ServerSocket()
            sock.reuseAddress = true
            sock.bind(java.net.InetSocketAddress(LOCAL_PORT + 1))
            probe = sock
            Thread({
                while (true) {
                    val c = try { sock.accept() } catch (_: Exception) { break }
                    try {
                        val who = (c.inetAddress?.hostAddress ?: "?") + " → " + (c.localAddress?.hostAddress ?: "?")
                        lastInbound = "$who  ${timeHm()}"
                        AppLog.add("גשש: חיבור נכנס $who")
                        val body = "PROBE OK\nfrom ${c.inetAddress?.hostAddress}\nto ${c.localAddress?.hostAddress}\n"
                        c.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray()
                        )
                        c.getOutputStream().flush()
                    } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} }
                }
            }, "diag-probe").start()
        } catch (e: Exception) {
            AppLog.add("גשש לא נפתח: ${e.message}")
        }
    }

    private fun timeHm(): String {
        val c = java.util.Calendar.getInstance()
        return String.format("%02d:%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND))
    }

    private fun stopLocal() {
        probe?.let { try { it.close() } catch (_: Exception) {} }
        probe = null
        lastInbound = null
        localServer?.let { try { it.stop() } catch (_: Exception) {} }
        localServer = null
        lohsReservation?.let { try { it.close() } catch (_: Exception) {} }
        lohsReservation = null
        lohsSsid = null
        lohsPass = null
        lohsError = null
        localActive = false
        localUrl = null
        localPresent = false
        updateScreen()
        AppLog.add("חיבור מקומי כבוי")
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
            .setContentTitle("AA 2 TESLA")
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
