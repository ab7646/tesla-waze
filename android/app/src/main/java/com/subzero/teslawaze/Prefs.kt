package com.subzero.teslawaze

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

object Prefs {
    /** Stable 128-bit random room id shared by this phone and the car screen URL. */
    fun roomId(context: Context): String {
        val prefs = context.getSharedPreferences("tesla_waze", Context.MODE_PRIVATE)
        prefs.getString("room", null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        prefs.edit().putString("room", id).apply()
        return id
    }
}
