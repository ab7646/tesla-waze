package com.subzero.teslawaze.aa

import android.annotation.SuppressLint
import com.subzero.teslawaze.AppLog
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509TrustManager

/**
 * An Android Auto head unit, talking the AAP protocol to the phone's own Android Auto app,
 * which listens on localhost:5277 once "Start head unit server" is selected in its developer menu.
 *
 * Android Auto renders its car UI (Waze, etc.) on a virtual display and streams it to us as
 * H.264; we hand the video to [Listener] and inject touches coming back from the car screen.
 *
 * Wire format: frames of [channel][flags][size:2]([total:4] on FIRST frames)[payload]; a message
 * payload is [message id:2][protobuf or raw body]. After the TLS handshake (carried inside
 * control messages) every frame payload is TLS-encrypted on its own.
 */
class HeadUnit(private val keyManagers: Array<KeyManager>, private val listener: Listener) {

    interface Listener {
        fun onVideoConfig(data: ByteArray)
        fun onVideoFrame(ptsUs: Long, data: ByteArray)
    }

    companion object {
        const val PORT = 5277
        const val VIDEO_W = 1280
        const val VIDEO_H = 720

        private const val CH_CONTROL = 0
        private const val CH_INPUT = 1
        private const val CH_SENSOR = 2
        private const val CH_VIDEO = 3

        // Frame flags
        private const val FRAME_MIDDLE = 0
        private const val FRAME_FIRST = 1
        private const val FRAME_LAST = 2
        private const val FRAME_BULK = 3
        private const val FLAG_CONTROL = 4
        private const val FLAG_ENCRYPTED = 8

        // Control channel messages
        private const val VERSION_REQUEST = 0x0001
        private const val VERSION_RESPONSE = 0x0002
        private const val SSL_HANDSHAKE = 0x0003
        private const val AUTH_COMPLETE = 0x0004
        private const val SERVICE_DISCOVERY_REQUEST = 0x0005
        private const val SERVICE_DISCOVERY_RESPONSE = 0x0006
        private const val CHANNEL_OPEN_REQUEST = 0x0007
        private const val CHANNEL_OPEN_RESPONSE = 0x0008
        private const val PING_REQUEST = 0x000b
        private const val PING_RESPONSE = 0x000c
        private const val NAV_FOCUS_REQUEST = 0x000d
        private const val NAV_FOCUS_RESPONSE = 0x000e
        private const val SHUTDOWN_REQUEST = 0x000f
        private const val SHUTDOWN_RESPONSE = 0x0010
        private const val VOICE_SESSION_REQUEST = 0x0011
        private const val AUDIO_FOCUS_REQUEST = 0x0012
        private const val AUDIO_FOCUS_RESPONSE = 0x0013

        // Media (video) channel messages
        private const val MEDIA_WITH_TIMESTAMP = 0x0000
        private const val MEDIA_CODEC_CONFIG = 0x0001
        private const val AV_SETUP_REQUEST = 0x8000
        private const val AV_START = 0x8001
        private const val AV_STOP = 0x8002
        private const val AV_SETUP_RESPONSE = 0x8003
        private const val AV_MEDIA_ACK = 0x8004
        private const val VIDEO_FOCUS_REQUEST = 0x8007
        private const val VIDEO_FOCUS_INDICATION = 0x8008

        // Sensor channel
        private const val SENSOR_START_REQUEST = 0x8001
        private const val SENSOR_START_RESPONSE = 0x8002
        private const val SENSOR_EVENT = 0x8003
        private const val SENSOR_NIGHT = 10
        private const val SENSOR_DRIVING_STATUS = 13

        // Input channel
        private const val INPUT_EVENT = 0x8001
        private const val INPUT_BINDING_REQUEST = 0x8002
        private const val INPUT_BINDING_RESPONSE = 0x8003

        // Touch actions
        const val TOUCH_PRESS = 0
        const val TOUCH_RELEASE = 1
        const val TOUCH_DRAG = 2
        const val TOUCH_POINTER_DOWN = 5
        const val TOUCH_POINTER_UP = 6
    }

    private class Message(val channel: Int, val control: Boolean, val id: Int, val body: ByteArray)

    private val socket = Socket()
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private lateinit var engine: SSLEngine
    private val sendLock = Any()
    private val partial = HashMap<Int, ByteArrayOutputStream>()
    @Volatile private var running = true
    @Volatile private var encrypted = false
    @Volatile private var videoSession = 0
    @Volatile private var videoStarted = false
    @Volatile private var inputReady = false
    @Volatile private var lastFocusToggle = 0L
    private var traced = 0

    /** Connects and runs the protocol until the connection ends. Blocking; call from a worker thread. */
    fun run() {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", PORT), 3000)
        input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
        output = socket.getOutputStream()
        AppLog.add("מחובר ל-Android Auto, מתחיל לחיצת יד")

        send(CH_CONTROL, false, VERSION_REQUEST, byteArrayOf(0, 1, 0, 1))
        val version = readMessage()
        if (version.id != VERSION_RESPONSE) error("ציפיתי לתשובת גרסה, התקבל ${hex(version.id)}")
        val b = version.body
        val status = if (b.size >= 6) ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF) else -1
        AppLog.add("גרסת פרוטוקול ${b.getOrNull(1)}.${b.getOrNull(3)}, סטטוס $status")
        if (status != 0) error("Android Auto דחה את גרסת הפרוטוקול")

        handshake()
        send(CH_CONTROL, false, AUTH_COMPLETE, Pb().varint(1, 0).toByteArray())
        encrypted = true
        AppLog.add("לחיצת יד TLS הושלמה, Android Auto קיבל את התעודה")

        while (running) {
            val m = readMessage()
            if (traced < 40) {
                traced++
                AppLog.add("← ערוץ ${m.channel} ${hex(m.id)} (${m.body.size}B)")
            }
            handle(m)
        }
    }

    fun close() {
        running = false
        try { socket.close() } catch (_: Exception) {}
    }

    /** Android Auto only sends an IDR at stream start, so re-taking video focus forces a fresh one. */
    fun requestKeyFrame() {
        if (!videoStarted) return
        val now = System.currentTimeMillis()
        if (now - lastFocusToggle < 1500) return
        lastFocusToggle = now
        sendVideoFocus(focused = false)
        sendVideoFocus(focused = true)
    }

    /** pointers: (pointerId, x, y) in video coordinates; index: which pointer the action refers to. */
    fun sendTouch(action: Int, index: Int, pointers: List<Triple<Int, Int, Int>>) {
        if (!inputReady || pointers.isEmpty()) return
        val touch = Pb()
        for ((id, x, y) in pointers) touch.msg(1, Pb().varint(1, x).varint(2, y).varint(3, id))
        touch.varint(2, index).varint(3, action)
        val event = Pb().varint(1, System.nanoTime() / 1000).msg(3, touch)
        send(CH_INPUT, false, INPUT_EVENT, event.toByteArray())
    }

    // ---------------- message handling ----------------

    private fun handle(m: Message) {
        if (m.channel != CH_CONTROL && m.control && m.id == CHANNEL_OPEN_REQUEST) {
            send(m.channel, true, CHANNEL_OPEN_RESPONSE, Pb().varint(1, 0).toByteArray())
            AppLog.add("ערוץ ${m.channel} נפתח")
            return
        }
        when (m.channel) {
            CH_CONTROL -> handleControl(m)
            CH_VIDEO -> handleVideo(m)
            CH_INPUT -> if (m.id == INPUT_BINDING_REQUEST) {
                send(CH_INPUT, false, INPUT_BINDING_RESPONSE, Pb().varint(1, 0).toByteArray())
                inputReady = true
                AppLog.add("ערוץ המגע מוכן")
            }
            CH_SENSOR -> if (m.id == SENSOR_START_REQUEST) {
                val type = Pb.parse(m.body).int(1) ?: 0
                send(CH_SENSOR, false, SENSOR_START_RESPONSE, Pb().varint(1, 0).toByteArray())
                sendSensor(type)
            }
            else -> AppLog.add("הודעה לא מוכרת בערוץ ${m.channel}: ${hex(m.id)}")
        }
    }

    private fun handleControl(m: Message) {
        when (m.id) {
            SERVICE_DISCOVERY_REQUEST -> {
                val req = Pb.parse(m.body)
                AppLog.add("Android Auto מבקש פרטי רכב: $req")
                val resp = serviceDiscoveryResponse()
                send(CH_CONTROL, false, SERVICE_DISCOVERY_RESPONSE, resp)
                AppLog.add("נשלחו פרטי הרכב (${resp.size}B), ממתין לפתיחת ערוצים")
            }
            PING_REQUEST -> {
                val ts = Pb.parse(m.body).long(1) ?: 0L
                send(CH_CONTROL, false, PING_RESPONSE, Pb().varint(1, ts).toByteArray())
            }
            NAV_FOCUS_REQUEST -> send(CH_CONTROL, false, NAV_FOCUS_RESPONSE, Pb().varint(1, 2).toByteArray())
            AUDIO_FOCUS_REQUEST -> {
                // 1 gain, 2 gain transient, 3 gain navigation, 4 release -> 1 gain, 2 transient, 3 loss
                val state = when (Pb.parse(m.body).int(1)) {
                    1 -> 1
                    2, 3 -> 2
                    else -> 3
                }
                send(CH_CONTROL, false, AUDIO_FOCUS_RESPONSE, Pb().varint(1, state).toByteArray())
            }
            SHUTDOWN_REQUEST -> {
                AppLog.add("Android Auto ביקש להתנתק")
                send(CH_CONTROL, false, SHUTDOWN_RESPONSE, ByteArray(0))
                running = false
            }
            VOICE_SESSION_REQUEST -> Unit
            else -> AppLog.add("הודעת בקרה לא מוכרת: ${hex(m.id)}")
        }
    }

    private fun handleVideo(m: Message) {
        when (m.id) {
            MEDIA_WITH_TIMESTAMP -> {
                val b = m.body
                var ts = 0L
                for (i in 0 until 8) ts = (ts shl 8) or (b[i].toLong() and 0xFF)
                listener.onVideoFrame(ts, b.copyOfRange(8, b.size))
                ackVideo()
            }
            MEDIA_CODEC_CONFIG -> {
                listener.onVideoConfig(m.body)
                ackVideo()
            }
            AV_SETUP_REQUEST -> {
                val setup = Pb().varint(1, 2).varint(2, 1).varint(3, 0)
                send(CH_VIDEO, false, AV_SETUP_RESPONSE, setup.toByteArray())
                sendVideoFocus(focused = true)
            }
            AV_START -> {
                videoSession = Pb.parse(m.body).int(1) ?: 0
                videoStarted = true
                AppLog.add("הווידאו התחיל (session $videoSession)")
            }
            AV_STOP -> {
                videoStarted = false
                AppLog.add("הווידאו נעצר")
            }
            VIDEO_FOCUS_REQUEST -> sendVideoFocus(focused = true)
            else -> AppLog.add("הודעת וידאו לא מוכרת: ${hex(m.id)}")
        }
    }

    private fun ackVideo() =
        send(CH_VIDEO, false, AV_MEDIA_ACK, Pb().varint(1, videoSession).varint(2, 1).toByteArray())

    private fun sendVideoFocus(focused: Boolean) =
        send(CH_VIDEO, false, VIDEO_FOCUS_INDICATION, Pb().varint(1, if (focused) 1 else 2).bool(2, false).toByteArray())

    private fun sendSensor(type: Int) {
        val event = when (type) {
            SENSOR_DRIVING_STATUS -> Pb().msg(13, Pb().varint(1, 0)) // unrestricted
            SENSOR_NIGHT -> Pb().msg(10, Pb().bool(1, false))
            else -> return
        }
        send(CH_SENSOR, false, SENSOR_EVENT, event.toByteArray())
    }

    private fun serviceDiscoveryResponse(): ByteArray {
        val video = Pb()
            .varint(1, CH_VIDEO)
            .msg(3, Pb()
                .varint(1, 3) // stream type: video
                .msg(4, Pb()
                    .varint(1, 2) // 1280x720
                    .varint(2, 1) // 30 fps
                    .varint(3, 0).varint(4, 0) // margins
                    .varint(5, 160)) // dpi
                .bool(5, true)) // available while in call
        val touch = Pb()
            .varint(1, CH_INPUT)
            .msg(4, Pb().msg(2, Pb().varint(1, VIDEO_W).varint(2, VIDEO_H)))
        val sensors = Pb()
            .varint(1, CH_SENSOR)
            .msg(2, Pb()
                .msg(1, Pb().varint(1, SENSOR_DRIVING_STATUS))
                .msg(1, Pb().varint(1, SENSOR_NIGHT)))
        return Pb()
            .msg(1, video).msg(1, touch).msg(1, sensors)
            .string(2, "Tesla Bridge")
            .string(3, "Model Y")
            .string(4, "2024")
            .string(5, "0001")
            .bool(6, true) // left-hand drive
            .string(7, "SubZero")
            .string(8, "Browser Head Unit")
            .string(9, "1")
            .string(10, "1.0")
            .bool(11, false)
            .bool(12, false)
            .toByteArray()
    }

    // ---------------- TLS ----------------

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustPhone : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun handshake() {
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(keyManagers, arrayOf(TrustPhone), SecureRandom())
        engine = ctx.createSSLEngine()
        engine.useClientMode = true
        engine.enabledProtocols = arrayOf("TLSv1.2")
        engine.beginHandshake()

        val session = engine.session
        val empty = ByteBuffer.allocate(0)
        val app = ByteBuffer.allocate(session.applicationBufferSize)
        var netIn = ByteBuffer.allocate(0)
        val pending = ByteArrayOutputStream()

        fun flushPending() {
            if (pending.size() > 0) {
                send(CH_CONTROL, false, SSL_HANDSHAKE, pending.toByteArray())
                pending.reset()
            }
        }

        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val out = ByteBuffer.allocate(session.packetBufferSize)
                    val r = engine.wrap(empty, out)
                    if (r.status != SSLEngineResult.Status.OK) error("TLS wrap: ${r.status}")
                    out.flip()
                    pending.write(out.array(), 0, out.limit())
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    flushPending()
                    if (!netIn.hasRemaining()) netIn = ByteBuffer.wrap(readHandshake())
                    app.clear()
                    val r = engine.unwrap(netIn, app)
                    if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        val more = readHandshake()
                        val merged = ByteBuffer.allocate(netIn.remaining() + more.size)
                        merged.put(netIn).put(more).flip()
                        netIn = merged
                    } else if (r.status != SSLEngineResult.Status.OK) {
                        error("TLS unwrap: ${r.status}")
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) engine.delegatedTask?.run() ?: break
                }
                else -> {
                    flushPending()
                    return
                }
            }
        }
    }

    private fun readHandshake(): ByteArray {
        val m = readMessage()
        if (m.id != SSL_HANDSHAKE) error("ציפיתי להודעת TLS, התקבל ${hex(m.id)} בערוץ ${m.channel}")
        return m.body
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(plain)
        val out = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(engine.session.packetBufferSize)
        while (src.hasRemaining()) {
            buf.clear()
            val r = engine.wrap(src, buf)
            if (r.status != SSLEngineResult.Status.OK) error("TLS encrypt: ${r.status}")
            buf.flip()
            out.write(buf.array(), 0, buf.limit())
        }
        return out.toByteArray()
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(data)
        val out = ByteArrayOutputStream()
        var app = ByteBuffer.allocate(maxOf(engine.session.applicationBufferSize, data.size))
        while (src.hasRemaining()) {
            app.clear()
            val r = engine.unwrap(src, app)
            when (r.status) {
                SSLEngineResult.Status.OK -> {
                    app.flip()
                    out.write(app.array(), 0, app.limit())
                    if (r.bytesConsumed() == 0) break
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> app = ByteBuffer.allocate(app.capacity() * 2)
                SSLEngineResult.Status.CLOSED -> error("Android Auto סגר את ערוץ ה-TLS (close_notify)")
                else -> error("TLS decrypt: ${r.status}")
            }
        }
        return out.toByteArray()
    }

    // ---------------- framing ----------------

    private fun send(channel: Int, control: Boolean, id: Int, body: ByteArray) {
        val plain = ByteArray(2 + body.size)
        plain[0] = (id shr 8).toByte()
        plain[1] = id.toByte()
        System.arraycopy(body, 0, plain, 2, body.size)
        synchronized(sendLock) {
            // Encryption and write happen under one lock: TLS record order must match send order.
            val enc = encrypted && id != SSL_HANDSHAKE
            val payload = if (enc) encrypt(plain) else plain
            var flags = FRAME_BULK
            if (control) flags = flags or FLAG_CONTROL
            if (enc) flags = flags or FLAG_ENCRYPTED
            val frame = ByteArray(4 + payload.size)
            frame[0] = channel.toByte()
            frame[1] = flags.toByte()
            frame[2] = (payload.size shr 8).toByte()
            frame[3] = payload.size.toByte()
            System.arraycopy(payload, 0, frame, 4, payload.size)
            output.write(frame)
            output.flush()
        }
    }

    private fun readMessage(): Message {
        while (true) {
            val channel = try {
                input.readUnsignedByte()
            } catch (e: java.io.EOFException) {
                error("Android Auto סגר את החיבור (בלי הודעת סיום)")
            }
            val flags = input.readUnsignedByte()
            val type = flags and 3
            val size = input.readUnsignedShort()
            if (type == FRAME_FIRST) input.readInt() // total message size; not needed
            val raw = ByteArray(size)
            input.readFully(raw)
            val data = if (flags and FLAG_ENCRYPTED != 0) decrypt(raw) else raw
            val complete: ByteArray = when (type) {
                FRAME_BULK -> data
                FRAME_FIRST -> {
                    partial[channel] = ByteArrayOutputStream(maxOf(data.size * 4, 1 shl 16)).apply { write(data) }
                    continue
                }
                FRAME_MIDDLE -> {
                    partial[channel]?.write(data)
                    continue
                }
                else -> { // FRAME_LAST
                    val buf = partial.remove(channel) ?: continue
                    buf.write(data)
                    buf.toByteArray()
                }
            }
            if (complete.size < 2) continue
            val id = ((complete[0].toInt() and 0xFF) shl 8) or (complete[1].toInt() and 0xFF)
            return Message(channel, flags and FLAG_CONTROL != 0, id, complete.copyOfRange(2, complete.size))
        }
    }

    private fun hex(id: Int) = "0x" + id.toString(16).padStart(4, '0')
}
