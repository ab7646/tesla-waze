package com.subzero.teslawaze

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * One-time pairing: the car screen shows a 6-digit code and waits in a pairing room
 * derived from it. The phone joins that room and sends its permanent room id.
 */
object Pairing {
    private val client by lazy { OkHttpClient() }

    fun pair(context: Context, code: String, done: (ok: Boolean, message: String) -> Unit) {
        val room = Prefs.roomId(context)
        val url = BuildConfig.RELAY_URL.trimEnd('/') + "/room/pair-$code-teslawaze?role=phone"
        val main = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(ok: Boolean, message: String) = main.post {
            if (!finished) {
                finished = true
                done(ok, message)
            }
        }

        val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (e: Exception) { return }
                when {
                    m.optString("type") == "peer" && m.optBoolean("present") ->
                        webSocket.send(JSONObject().put("type", "pair").put("room", room).toString())
                    m.optString("type") == "paired" -> {
                        webSocket.close(1000, null)
                        finish(true, "הרכב חובר! מעכשיו מספיק ללחוץ \"התחל הקרנה\".")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish(false, "אין חיבור לשרת. בדוק שיש אינטרנט ונסה שוב.")
            }
        })

        main.postDelayed({
            if (!finished) {
                ws.cancel()
                finish(false, "הקוד לא נמצא. ודא שהקוד זהה לזה שמוצג בטסלה ונסה שוב.")
            }
        }, 10_000)
    }
}
