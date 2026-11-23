package com.subzero.teslawaze

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status line plus a short rolling log shown in the app, so problems can be read off a screenshot. */
object AppLog {
    @Volatile var status: String = "מוכן"

    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(message: String) {
        Log.i("TeslaWaze", message)
        synchronized(lines) {
            lines.addLast("${time.format(Date())}  $message")
            while (lines.size > 40) lines.removeFirst()
        }
    }

    fun text(): String = synchronized(lines) { lines.reversed().joinToString("\n") }
}
