package com.subzero.teslawaze

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WebSocket link to the relay, with automatic reconnect and exponential backoff. */
class RelayClient(private val url: String, private val listener: Listener) : WebSocketListener() {

    interface Listener {
        fun onPeer(present: Boolean)
        fun onControl(message: JSONObject)
        fun onState(text: String)
    }

    private val client = OkHttpClient.Builder().pingInterval(5, TimeUnit.SECONDS) // notice a dead mobile link within seconds.build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    private var attempt = 0

    fun connect() {
        if (closed) return
        ws = client.newWebSocket(Request.Builder().url(url).build(), this)
    }

    fun send(bytes: ByteArray): Boolean = ws?.send(bytes.toByteString()) ?: false

    fun queued(): Long = ws?.queueSize() ?: 0L

    fun close() {
        closed = true
        ws?.close(1000, "bye")
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        attempt = 0
        listener.onState("מחובר לשרת. ממתין לטסלה…")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val message = try { JSONObject(text) } catch (e: Exception) { return }
        if (message.optString("type") == "peer") listener.onPeer(message.optBoolean("present"))
        else listener.onControl(message)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "החיבור לשרת נסגר")

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
        lost(webSocket, "אין חיבור לשרת (${t.javaClass.simpleName})")

    private fun lost(webSocket: WebSocket, why: String) {
        if (webSocket !== ws) return
        ws = null
        listener.onPeer(false)
        if (closed) return
        val delay = minOf(30L, 1L shl minOf(attempt++, 5))
        listener.onState("$why. מנסה שוב בעוד $delay שניות…")
        scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS)
    }
}
