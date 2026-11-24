package com.subzero.teslawaze

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.subzero.teslawaze.aa.HuCert

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var certInfo: TextView
    private lateinit var log: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            status.text = AppLog.status.ifEmpty { "מוכן" }
            (statusDot.background as GradientDrawable).setColor(if (AaService.running) GREEN else 0x66FFFFFF)
            if (log.visibility == View.VISIBLE) log.text = AppLog.text()
            certInfo.text = HuCert.describe(this@MainActivity)?.let { "✓ תעודה טעונה: $it" } ?: "לא נטענה תעודת יחידת ראש"
            ui.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun label(text: String, size: Float, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    /** Rounded, colorful button with a ripple. Filled buttons have white text; soft ones a light tint of their color. */
    private fun button(text: String, color: Int, filled: Boolean = true, big: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = if (big) 19f else 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (filled) Color.WHITE else color)
            stateListAnimator = null
            val bg = rounded(if (filled) color else (color and 0x00FFFFFF) or 0x1F000000, 18)
            background = RippleDrawable(ColorStateList.valueOf(if (filled) 0x40FFFFFF else (color and 0x00FFFFFF) or 0x33000000), bg, null)
            setPadding(dp(16), dp(if (big) 18 else 13), dp(16), dp(if (big) 18 else 13))
            elevation = if (filled) dp(3).toFloat() else 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }

    private fun card(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 24)
        elevation = dp(2).toFloat()
        setPadding(dp(18), dp(16), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(14) }
        children.forEach { addView(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shortHost = Uri.parse(BuildConfig.SCREEN_URL).host ?: BuildConfig.SCREEN_URL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // ---- header: gradient with the title and a live status pill ----
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66FFFFFF) }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) }
        }
        status = label("", 15f, Color.WHITE, bold = true)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x33FFFFFF, 50)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addView(statusDot)
            addView(status)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TR_BL,
                intArrayOf(0xFF33CCFF.toInt(), 0xFF7B61FF.toInt(), 0xFFFF5FA2.toInt()),
            ).apply { cornerRadius = dp(28).toFloat() }
            elevation = dp(4).toFloat()
            setPadding(dp(20), dp(22), dp(20), dp(20))
            addView(label("🚗  Waze לטסלה", 28f, Color.WHITE, bold = true))
            addView(label("Waze על המסך של הרכב, והטלפון נשאר חופשי", 15f, 0xE6FFFFFF.toInt()))
            addView(pill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) })
        })

        // ---- main action ----
        root.addView(card(
            label("אנדרואיד אוטו", 20f, bold = true),
            label("Waze רץ ברקע בטלפון ומוצג על מסך הטסלה.", 14f, MUTED),
            button("▶  התחל", GREEN, big = true) { startAndroidAuto() },
            button("■  עצור", CORAL, filled = false) {
                startService(Intent(this, AaService::class.java).setAction(AaService.ACTION_STOP))
            },
        ))

        // ---- pairing ----
        root.addView(card(
            label("חיבור לרכב", 20f, bold = true),
            label("פעם אחת: בדפדפן של הטסלה הקלד את הכתובת, ואז לחץ על הכפתור והקלד את הקוד שמופיע ברכב.", 14f, MUTED),
            label(shortHost, 20f, BLUE, bold = true).apply {
                setTextIsSelectable(true)
                textDirection = View.TEXT_DIRECTION_LTR
                setPadding(0, dp(8), 0, 0)
            },
            button("🔗  חבר לרכב", BLUE) { askPairingCode() },
        ))

        // ---- setup ----
        certInfo = label("", 13f, MUTED)
        root.addView(card(
            label("הגדרות", 20f, bold = true),
            certInfo,
            button("⚙️  הגדרות Android Auto", PURPLE, filled = false) { openAndroidAutoSettings() },
            button("📄  טען תעודת יחידת ראש", ORANGE, filled = false) { pickCertificate() },
        ))

        // ---- log (collapsed by default) ----
        log = label("", 11f, 0xFFCFE3FF.toInt()).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            background = rounded(0xFF1E2235.toInt(), 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        lateinit var toggle: Button
        toggle = button("📋  הצג יומן", SLATE, filled = false) {
            val show = log.visibility != View.VISIBLE
            log.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text =if (show) "📋  הסתר יומן" else "📋  הצג יומן"
            if (show) log.text = AppLog.text()
        }
        root.addView(card(
            label("יומן", 20f, bold = true),
            toggle,
            button("📤  שתף את היומן", SLATE, filled = false) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLog.text())
                startActivity(Intent.createChooser(send, null))
            },
            log,
        ))

        if (BuildConfig.RELAY_URL.isEmpty()) root.addView(label("שים לב: בבנייה הזו לא הוגדר שרת ממסר (RELAY_URL).", 14f, CORAL))
        root.addView(label("גרסה ${BuildConfig.VERSION_NAME}", 12f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
            // Android 15 draws apps edge to edge; keep the content clear of the status and navigation bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        })

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

    private fun startAndroidAuto() {
        // Stop the old mirror mode in case an earlier version left it running.
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        startForegroundService(Intent(this, AaService::class.java))
        // Android Auto offers no way for other apps to start its head unit server, so if it is
        // not listening, take the user straight to the screen whose menu starts it.
        // (The service's first connection attempt tells us; probing the port ourselves could
        // collide with it.)
        ui.postDelayed({
            if (AaService.serverMissing) {
                Toast.makeText(this, "בתפריט שלוש הנקודות בחר Start head unit server, ואז חזור לכאן", Toast.LENGTH_LONG).show()
                openAndroidAutoSettings()
            }
        }, 1500)
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

    @Deprecated("Activity result API is not used to keep the app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CERT) return
        val uri = data?.data ?: return
        try {
            val subject = HuCert.import(this, uri)
            AppLog.add("התעודה נטענה: $subject")
            Toast.makeText(this, "התעודה נטענה", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLog.add("טעינת התעודה נכשלה: ${e.message}")
            Toast.makeText(this, "טעינת התעודה נכשלה: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQ_CERT = 43
        private const val BG = 0xFFF4F6FB.toInt()
        private const val INK = 0xFF1E2235.toInt()
        private const val MUTED = 0xFF6B7280.toInt()
        private const val GREEN = 0xFF22C55E.toInt()
        private const val CORAL = 0xFFFF6B6B.toInt()
        private const val BLUE = 0xFF2F80ED.toInt()
        private const val PURPLE = 0xFF8B5CF6.toInt()
        private const val ORANGE = 0xFFF59E0B.toInt()
        private const val SLATE = 0xFF475569.toInt()
    }
}
