package com.subzero.teslawaze

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
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

/**
 * The app's one screen, in a dark "neon premium" style: a deep charcoal base, cards a step lighter
 * for depth, softened white text, and electric-mint accents used sparingly on the live data and the
 * primary action.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var statusLine: TextView
    private lateinit var certInfo: TextView
    private lateinit var log: TextView
    private lateinit var localInfo: TextView
    private lateinit var localBtn: Button
    private lateinit var dayNightBtn: Button
    private lateinit var fpsNum: TextView
    private lateinit var kbpsNum: TextView
    private lateinit var spark: SparklineView
    private val ui = Handler(Looper.getMainLooper())
    private var autoStarted = false

    private val tick = object : Runnable {
        override fun run() {
            val running = AaService.running
            status.text = when {
                !running -> "מוכן"
                AaService.serverMissing -> "ממתין להפעלת שרת"
                AaService.fps > 0 -> if (AaService.localActive) "משדר · מקומי" else "משדר"
                AaService.localActive -> "מקומי פעיל"
                else -> "ממתין לרכב"
            }
            statusLine.text = AppLog.status.ifEmpty { "מוכן" }
            (statusDot.background as GradientDrawable).setColor(if (running) MINT else DIM)
            fpsNum.text = if (running) AaService.fps.toString() else "—"
            kbpsNum.text = if (running && AaService.kbps > 0) "%.1f".format(AaService.kbps / 1000f) else "—"
            spark.push(if (running) AaService.fps.toFloat() else 0f)
            if (log.visibility == View.VISIBLE) log.text = AppLog.text()
            certInfo.text = HuCert.describe(this@MainActivity)?.let { "תעודה טעונה ✓" } ?: "לא נטענה תעודה"
            if (AaService.localActive) {
                localBtn.text = "כבה חיבור מקומי"
                val ssid = AaService.lohsSsid
                localInfo.text = when {
                    ssid != null -> "רשת:  $ssid\nסיסמה:  ${AaService.lohsPass}\n${AaService.hotspotUrl() ?: "http://192.168.49.1:8080"}"
                    AaService.lohsError != null -> AaService.lohsError
                    else -> "פותח נקודה חמה מקומית…"
                }
            } else {
                localBtn.text = "הפעל חיבור מקומי"
                localInfo.text = "פותח נקודה חמה פרטית שהטסלה תוכל להגיע אליה"
            }
            dayNightBtn.text = when (AaService.nightMode) {
                1 -> "תצוגה: יום ☀"
                2 -> "תצוגה: לילה ☾"
                else -> "תצוגה: אוטומטי"
            }
            ui.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun label(text: String, size: Float, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
        setLineSpacing(0f, 1.2f)
    }

    /** Small uppercase, letter-spaced section label. */
    private fun eyebrow(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(DIM)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.12f
        isAllCaps = true
    }

    /**
     * filled → solid accent fill with dark text (the one primary action); otherwise a quiet elevated
     * surface with the accent as the text color.
     */
    private fun button(text: String, accent: Int, filled: Boolean = false, big: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = if (big) 16f else 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (filled) 0xFF04221C.toInt() else accent)
            stateListAnimator = null
            minHeight = 0; minimumHeight = 0
            val bg = if (filled) rounded(accent, 12)
            else rounded(FLOAT, 12, 1, LINE)
            val ripple = if (filled) 0x40FFFFFF else (accent and 0x00FFFFFF) or 0x33000000
            background = RippleDrawable(ColorStateList.valueOf(ripple), bg, null)
            setPadding(dp(10), dp(if (big) 14 else 12), dp(10), dp(if (big) 14 else 12))
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun row(vararg items: Pair<View, Float>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        items.forEachIndexed { i, (v, weight) ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                if (i > 0) marginStart = dp(10)
            })
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private fun card(accent: Boolean, children: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = if (accent) rounded(CARD, 16, 1, 0x4D00F5D4) else rounded(CARD, 16, 1, LINE)
        setPadding(dp(16), dp(15), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
        children.forEach { addView(it) }
    }

    /** A telemetry tile: big mono number + unit over a small label. */
    private fun metric(value: TextView, unit: String, caption: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(FLOAT, 12)
        setPadding(dp(12), dp(10), dp(12), dp(11))
        value.apply {
            textSize = 23f
            setTextColor(INK)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(value)
            addView(label(" $unit", 11f, DIM).apply { typeface = Typeface.MONOSPACE; setPadding(0, 0, 0, dp(3)) })
        })
        addView(label(caption, 11f, MUTED).apply { setPadding(0, dp(2), 0, 0) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val shortHost = Uri.parse(BuildConfig.SCREEN_URL).host ?: BuildConfig.SCREEN_URL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }

        // ---- header: compact brand only (status lives in the telemetry card) ----
        val mark = label("▶", 20f, MINT, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(FLOAT, 12, 1, 0x4000F5D4)
            setPadding(0, 0, 0, dp(2))
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("AA 2 TESLA", 20f, INK, bold = true).apply { letterSpacing = 0.02f })
            addView(label("Android Auto → Tesla", 12f, DIM))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mark, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(11) })
            addView(brand, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })

        // ---- live telemetry ----
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DIM) }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(7) }
        }
        status = label("מוכן", 12.5f, MINT, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val statusChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0x1400F5D4, 50, 1, 0x3300F5D4)
            setPadding(dp(11), dp(6), dp(11), dp(6))
            addView(statusDot)
            addView(status)
        }
        val telemetryHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(eyebrow("שידור חי"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusChip)
        }
        statusLine = label("", 13f, MUTED).apply { setPadding(0, dp(8), 0, 0) }
        fpsNum = TextView(this).apply { text = "—" }
        kbpsNum = TextView(this).apply { text = "—" }
        spark = SparklineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
                .apply { topMargin = dp(10) }
        }
        root.addView(card(true, listOf(
            telemetryHead,
            statusLine,
            row(
                metric(fpsNum, "fps", "קצב פריימים") to 1f,
                metric(kbpsNum, "Mbps", "ביטרייט") to 1f,
            ),
            spark,
        )))

        // ---- main action ----
        root.addView(card(false, listOf(
            eyebrow("אנדרואיד אוטו"),
            label("נפתח אוטומטית בכל פתיחה של האפליקציה.", 13f, MUTED),
            row(
                button("התחל", MINT, filled = true, big = true) { startAndroidAuto() } to 2f,
                button("עצור", CORAL, big = true) {
                    autoStarted = true
                    startService(Intent(this, AaService::class.java).setAction(AaService.ACTION_STOP))
                } to 1f,
            ),
        )))

        // ---- local hotspot ----
        localInfo = label("", 15f, MINT, bold = true).apply {
            setTextIsSelectable(true)
            textDirection = View.TEXT_DIRECTION_LTR
        }
        localInfo.setOnClickListener { showLocalInfo() }
        localBtn = button("הפעל חיבור מקומי", MINT) { toggleLocal() }
        root.addView(card(false, listOf(
            eyebrow("חיבור מקומי · ללא אינטרנט"),
            localInfo,
            label("הקש על הפרטים לשם הרשת, הסיסמה והכתובת לטסלה", 11.5f, DIM),
            localBtn,
        )))

        // ---- map day/night (overrides what Waze shows on the Tesla) ----
        dayNightBtn = button("תצוגה: אוטומטי", VIOLET) {
            AaService.nightMode = (AaService.nightMode + 1) % 3
            dayNightBtn.text = when (AaService.nightMode) {
                1 -> "תצוגה: יום ☀"
                2 -> "תצוגה: לילה ☾"
                else -> "תצוגה: אוטומטי"
            }
        }
        root.addView(card(false, listOf(
            eyebrow("תצוגת מפה · יום / לילה"),
            label("Waze עוקב אחרי המצב הזה. \"אוטומטי\" הולך לפי ערכת הנושא של הטלפון; בחר יום כדי לכפות תצוגה בהירה.", 13f, MUTED),
            dayNightBtn,
        )))

        // ---- pairing ----
        root.addView(card(false, listOf(
            eyebrow("חיבור לרכב · פעם אחת"),
            row(
                label(shortHost, 14f, CYBER, bold = true).apply {
                    setTextIsSelectable(true)
                    textDirection = View.TEXT_DIRECTION_LTR
                    typeface = Typeface.MONOSPACE
                } to 1.6f,
                button("חבר", CYBER) { askPairingCode() } to 1f,
            ),
        )))

        // ---- tools ----
        certInfo = label("", 12.5f, MUTED)
        log = label("", 11f, 0xFFB9F3EA.toInt()).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            background = rounded(0xFF0B0E14.toInt(), 12, 1, LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        lateinit var toggle: Button
        toggle = button("יומן", MUTED) {
            val show = log.visibility != View.VISIBLE
            log.visibility = if (show) View.VISIBLE else View.GONE
            toggle.text = if (show) "הסתר יומן" else "יומן"
            if (show) log.text = AppLog.text()
        }
        root.addView(card(false, listOf(
            eyebrow("כלים"),
            certInfo,
            row(
                button("Android Auto", VIOLET) { openAndroidAutoSettings() } to 1f,
                button("תעודה", AMBER) { askCertificate() } to 1f,
            ),
            row(
                toggle to 1f,
                button("שתף יומן", MUTED) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppLog.text())
                    startActivity(Intent.createChooser(send, null))
                } to 1f,
            ),
            log,
        )))

        if (BuildConfig.RELAY_URL.isEmpty()) {
            root.addView(label("שים לב: בבנייה הזו לא הוגדר שרת ממסר (RELAY_URL).", 13f, CORAL).apply { setPadding(0, dp(10), 0, 0) })
        }
        root.addView(label("גרסה ${BuildConfig.VERSION_NAME}", 11.5f, DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        })

        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            wanted += Manifest.permission.RECORD_AUDIO
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)

        maybeAutoLoadCertificate()
    }

    private fun maybeAutoLoadCertificate() {
        val link = BuildConfig.CERT_URL
        if (link.isEmpty() || HuCert.isInstalled(this)) return
        AppLog.add("מוריד תעודה…")
        Thread {
            val message = try {
                "התעודה נטענה: ${HuCert.download(this, link)}"
            } catch (e: Exception) {
                "הורדת התעודה נכשלה: ${e.message}"
            }
            AppLog.add(message)
        }.start()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
        if (!autoStarted && !AaService.running &&
            HuCert.isInstalled(this) && BuildConfig.RELAY_URL.isNotEmpty()
        ) {
            autoStarted = true
            startAndroidAuto(openAaIfMissing = false)
        }
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun startAndroidAuto(openAaIfMissing: Boolean = true) {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        startForegroundService(Intent(this, AaService::class.java))
        if (!openAaIfMissing) return
        ui.postDelayed({
            if (AaService.serverMissing) {
                Toast.makeText(this, "בתפריט שלוש הנקודות בחר Start head unit server, ואז חזור לכאן", Toast.LENGTH_LONG).show()
                openAndroidAutoSettings()
            }
        }, 1500)
    }

    private fun toggleLocal() {
        if (AaService.localActive) {
            startService(Intent(this, AaService::class.java).setAction(AaService.ACTION_LOCAL_OFF))
            return
        }
        // The app opens its OWN private hotspot (Local-Only Hotspot). Android 13+ gates this on the
        // NEARBY_WIFI_DEVICES permission; older versions on fine location + location services on.
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "אשר הרשאת \"מכשירים סמוכים\" כדי לפתוח נקודה חמה מקומית, ואז לחץ שוב", Toast.LENGTH_LONG).show()
                requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 2)
                return
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "אשר הרשאת מיקום כדי לפתוח נקודה חמה מקומית, ואז לחץ שוב", Toast.LENGTH_LONG).show()
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
                return
            }
            if (!isLocationOn()) {
                AlertDialog.Builder(this)
                    .setTitle("הדלק מיקום")
                    .setMessage("כדי לפתוח נקודה חמה מקומית צריך ששירותי המיקום (GPS) יהיו דלוקים.")
                    .setPositiveButton("פתח הגדרות מיקום") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
                    }
                    .setNegativeButton("ביטול", null)
                    .show()
                return
            }
        }
        autoStarted = true
        startForegroundService(Intent(this, AaService::class.java).setAction(AaService.ACTION_LOCAL_ON))
        ui.postDelayed({ if (AaService.serverMissing) openAndroidAutoSettings() else showLocalInfo() }, 2500)
    }

    private fun isLocationOn(): Boolean = try {
        getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    } catch (_: Exception) { true }

    private fun showLocalInfo() {
        val ssid = AaService.lohsSsid
        val pass = AaService.lohsPass
        val url = AaService.hotspotUrl() ?: AaService.localUrl ?: "http://192.168.49.1:8080"
        val err = AaService.lohsError
        val msg = when {
            ssid != null ->
                "נקודה חמה מקומית פעילה ✓\n\n" +
                    "1) בטסלה ← הגדרות ← WiFi, התחבר ל:\n" +
                    "     רשת:  $ssid\n" +
                    "     סיסמה:  $pass\n\n" +
                    "2) בדפדפן הטסלה פתח:\n$url"
            err != null ->
                "לא ניתן לפתוח נקודה חמה מקומית:\n$err\n\n" +
                    "נסה לכבות את ההוטספוט הרגיל ולהדליק מיקום. אם זה חסום ע\"י המעסיק — מצב מקומי לא יתאפשר בטלפון הזה, ונצטרך להישאר עם הממסר דרך האינטרנט."
            else -> "פותח נקודה חמה מקומית… המתן כמה שניות ולחץ שוב על הכתובת לרענון."
        }
        AlertDialog.Builder(this)
            .setTitle("חיבור מקומי")
            .setMessage(msg)
            .setPositiveButton("סגור", null)
            .apply {
                setNeutralButton("שתף פרטים") { _, _ ->
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg)
                    startActivity(Intent.createChooser(send, null))
                }
            }
            .show()
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

    private fun askCertificate() {
        val prefs = getSharedPreferences("tesla_waze", MODE_PRIVATE)
        val clip = try {
            getSystemService(android.content.ClipboardManager::class.java).primaryClip
                ?.getItemAt(0)?.text?.toString()?.trim()?.takeIf { it.startsWith("http") }
        } catch (_: Exception) { null }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://drive.google.com/file/d/…"
            setText(prefs.getString("cert_link", null) ?: BuildConfig.CERT_URL.ifEmpty { clip ?: "" })
            textDirection = View.TEXT_DIRECTION_LTR
        }
        AlertDialog.Builder(this)
            .setTitle("תעודת יחידת ראש")
            .setMessage("הדבק את הקישור לקובץ (למשל שיתוף מ-Google Drive). הקישור נשמר רק בטלפון הזה.")
            .setView(input)
            .setPositiveButton("הורד") { _, _ ->
                val link = input.text.toString().trim()
                if (!link.startsWith("https://")) {
                    Toast.makeText(this, "צריך קישור שמתחיל ב-https://", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                prefs.edit().putString("cert_link", link).apply()
                Toast.makeText(this, "מוריד את התעודה…", Toast.LENGTH_SHORT).show()
                Thread {
                    val message = try {
                        "התעודה נטענה: ${HuCert.download(this, link)}"
                    } catch (e: Exception) {
                        "הורדת התעודה נכשלה: ${e.message}"
                    }
                    AppLog.add(message)
                    ui.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
                }.start()
            }
            .setNeutralButton("בחר קובץ") { _, _ -> pickCertificate() }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun pickCertificate() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(pick, REQ_CERT)
    }

    private fun openAndroidAutoSettings() {
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
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Location was requested for the Local-Only Hotspot; if granted, start local mode now.
        if (requestCode == 2 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleLocal()
    }

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
        private const val BG = 0xFF0B0E14.toInt()
        private const val CARD = 0xFF161B22.toInt()
        private const val FLOAT = 0xFF1F242D.toInt()
        private const val LINE = 0x1AFFFFFF
        private const val INK = 0xFFF3F4F6.toInt()
        private const val MUTED = 0xFF9CA3AF.toInt()
        private const val DIM = 0xFF6B7280.toInt()
        private const val MINT = 0xFF00F5D4.toInt()
        private const val CYBER = 0xFF00BBF9.toInt()
        private const val VIOLET = 0xFF9B5DE5.toInt()
        private const val CORAL = 0xFFFF5C7A.toInt()
        private const val AMBER = 0xFFFFC247.toInt()
    }
}
