package com.subzero.teslawaze

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.Toast
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import com.subzero.teslawaze.aa.HuCert
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var certInfo: TextView
    private lateinit var log: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            status.text = AppLog.status
            log.text = AppLog.text()
            certInfo.text = HuCert.describe(this@MainActivity)?.let { "תעודה טעונה: $it" } ?: "לא נטענה תעודת יחידת ראש"
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
        val shortHost = Uri.parse(BuildConfig.SCREEN_URL).host ?: BuildConfig.SCREEN_URL
        root.addView(label("Waze לטסלה", 26f, bold = true))
        root.addView(label("חיבור ראשון לרכב: בדפדפן של הטסלה הקלד את הכתובת הקצרה, ואז לחץ כאן \"חבר לרכב\" והקלד את הקוד שמופיע שם.", 16f))
        root.addView(label(shortHost, 22f, bold = true).apply {
            setTextIsSelectable(true)
            textDirection = View.TEXT_DIRECTION_LTR
        })
        root.addView(Button(this).apply {
            text = "חבר לרכב (קוד מהטסלה)"
            setOnClickListener { askPairingCode() }
        })
        status = label("", 16f, bold = true)
        root.addView(status)

        // ---- Android Auto mode: Waze runs in the background, the phone stays free ----
        root.addView(label("מצב אנדרואיד אוטו (Waze ברקע)", 20f, bold = true))
        certInfo = label("", 14f)
        root.addView(certInfo)
        root.addView(Button(this).apply {
            text = "טען תעודת יחידת ראש (קובץ PEM)"
            setOnClickListener { pickCertificate() }
        })
        root.addView(Button(this).apply {
            text = "פתח הגדרות Android Auto"
            setOnClickListener { openAndroidAutoSettings() }
        })
        root.addView(label("לפני ההפעלה: ב-Android Auto, בתפריט שלוש הנקודות, בחר \"Start head unit server\".", 14f))
        root.addView(Button(this).apply {
            text = "התחל מצב אנדרואיד אוטו"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                startForegroundService(Intent(this@MainActivity, AaService::class.java))
                // Android Auto offers no way for other apps to start its head unit server, so if it is
                // not listening, take the user straight to the screen whose menu starts it.
                // (The service's first connection attempt tells us; probing the port ourselves could
                // collide with it.)
                ui.postDelayed({
                    if (AaService.serverMissing) {
                        Toast.makeText(this@MainActivity, "בתפריט שלוש הנקודות בחר Start head unit server, ואז חזור לכאן", Toast.LENGTH_LONG).show()
                        openAndroidAutoSettings()
                    }
                }, 1500)
            }
        })
        root.addView(Button(this).apply {
            text = "עצור מצב אנדרואיד אוטו"
            setOnClickListener {
                startService(Intent(this@MainActivity, AaService::class.java).setAction(AaService.ACTION_STOP))
            }
        })

        // ---- Mirror mode (fallback) ----
        root.addView(label("שיקוף מסך (גיבוי)", 20f, bold = true))
        root.addView(Button(this).apply {
            text = "התחל שיקוף מסך"
            setOnClickListener {
                startService(Intent(this@MainActivity, AaService::class.java).setAction(AaService.ACTION_STOP))
                requestCapture()
            }
        })
        root.addView(Button(this).apply {
            text = "עצור שיקוף"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            }
        })
        root.addView(Button(this).apply {
            text = "שתף כתובת מלאה"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, screenUrl)
                startActivity(Intent.createChooser(send, null))
            }
        })

        root.addView(label("יומן", 16f, bold = true))
        root.addView(Button(this).apply {
            text = "שתף את היומן"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLog.text())
                startActivity(Intent.createChooser(send, null))
            }
        })
        log = label("", 12f).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        root.addView(log)
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

    private fun askPairingCode() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = "123456"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(this)
            .setTitle("חיבור לרכב")
            .setMessage("הקלד את 6 הספרות שמוצגות במסך הטסלה")
            .setView(input)
            .setPositiveButton("חבר") { _, _ ->
                val code = input.text.toString().trim()
                if (!code.matches(Regex("\\d{6}"))) {
                    Toast.makeText(this, "הקוד צריך להיות 6 ספרות", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                CaptureService.status = "מתחבר לרכב…"
                Pairing.pair(this, code) { _, message ->
                    CaptureService.status = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun pickCertificate() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(pick, REQ_CERT)
    }

    private fun openAndroidAutoSettings() {
        // Android Auto has no launcher icon on Android 10+; its own settings screen (with the
        // three-dot menu holding "Start head unit server") is found by its intent or activity name.
        val pkg = "com.google.android.projection.gearhead"
        val candidates = mutableListOf<Intent>()
        candidates += Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(pkg)
        try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            info.activities.orEmpty()
                .filter { it.exported && it.name.contains("settings", ignoreCase = true) }
                .sortedBy { a -> if (a.name.endsWith(".SettingsActivity")) 0 else if (a.name.endsWith("SettingsActivity")) 1 else 2 }
                .forEach { candidates += Intent().setClassName(pkg, it.name) }
        } catch (_: Exception) {}
        packageManager.getLaunchIntentForPackage(pkg)?.let { candidates += it }
        candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                AppLog.add("נפתח: ${intent.component?.className ?: intent.action}")
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "לא נמצאה אפליקציית Android Auto", Toast.LENGTH_LONG).show()
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
        if (requestCode == REQ_CERT) {
            val uri = data?.data ?: return
            try {
                val subject = HuCert.import(this, uri)
                AppLog.add("התעודה נטענה: $subject")
                Toast.makeText(this, "התעודה נטענה", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                AppLog.add("טעינת התעודה נכשלה: ${e.message}")
                Toast.makeText(this, "טעינת התעודה נכשלה: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }
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
        private const val REQ_CERT = 43
    }
}
