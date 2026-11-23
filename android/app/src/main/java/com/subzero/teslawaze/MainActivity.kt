package com.subzero.teslawaze

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            status.text = CaptureService.status
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screenUrl = BuildConfig.SCREEN_URL + "#r=" + Prefs.roomId(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 2)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(label("Waze לטסלה", 26f, bold = true))
        root.addView(label("פתח את הכתובת הזו בדפדפן של הטסלה פעם אחת, ושמור אותה במועדפים:", 16f))
        root.addView(label(screenUrl, 14f).apply { setTextIsSelectable(true) })
        root.addView(Button(this).apply {
            text = "שתף את הכתובת"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, screenUrl)
                startActivity(Intent.createChooser(send, null))
            }
        })
        root.addView(Button(this).apply {
            text = "התחל הקרנה"
            setOnClickListener { requestCapture() }
        })
        root.addView(Button(this).apply {
            text = "עצור"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            }
        })
        status = label("", 16f)
        root.addView(status)
        if (BuildConfig.RELAY_URL.isEmpty()) root.addView(label("שים לב: בבנייה הזו לא הוגדר שרת ממסר (RELAY_URL).", 14f))
        root.addView(label("גרסה ${BuildConfig.VERSION_NAME}", 12f))

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Activity result API is not used to keep the app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
            )
        } else {
            CaptureService.status = "ההרשאה לצילום המסך לא אושרה"
        }
    }

    companion object {
        private const val REQ_CAPTURE = 42
    }
}
