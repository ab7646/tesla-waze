package com.subzero.teslawaze

import android.content.Context
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Collections

/**
 * A tiny on-device HTTP + WebSocket server for "local hotspot mode". When the phone is a Wi-Fi
 * hotspot and the Tesla joins it, the Tesla browser opens this server directly (no internet relay),
 * so the stream keeps working through cellular dead zones.
 *
 * It serves the local-mode page (assets/local.html, which decodes video with MSE over plain http)
 * and a /ws channel carrying the same binary video/PCM-audio frames out and JSON touch/voice in.
 */
class LocalServer(
    private val context: Context,
    port: Int,
    private val onScreens: (count: Int) -> Unit,
    private val onControl: (JSONObject) -> Unit,
) : NanoWSD(port) {

    private val clients = Collections.synchronizedSet(LinkedHashSet<Peer>())

    fun clientCount(): Int = clients.size

    /**
     * Sends one binary frame to every connected Tesla screen; drops any that error out.
     * Synchronized so concurrent video (encoder thread) and audio frames never interleave on a
     * single WebSocket.
     */
    @Synchronized
    fun broadcast(bytes: ByteArray) {
        val snapshot = synchronized(clients) { clients.toList() }
        for (c in snapshot) {
            try {
                c.send(bytes)
            } catch (_: Exception) {
                clients.remove(c)
            }
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val name = when (val uri = session.uri) {
            "/", "", "/index.html" -> "local.html"
            else -> uri.trimStart('/')
        }
        if (name.contains("..")) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "no")
        return try {
            val bytes = context.assets.open(name).use { it.readBytes() }
            val mime = when {
                name.endsWith(".html") -> "text/html"
                name.endsWith(".js") -> "text/javascript"
                name.endsWith(".css") -> "text/css"
                else -> "application/octet-stream"
            }
            newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Peer(handshake)

    private inner class Peer(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            clients.add(this)
            onScreens(clients.size)
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            clients.remove(this)
            onScreens(clients.size)
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = try { message.textPayload } catch (_: Exception) { null } ?: return
            val json = try { JSONObject(text) } catch (_: Exception) { return }
            try { onControl(json) } catch (_: Exception) {}
        }

        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException?) {}
    }
}
