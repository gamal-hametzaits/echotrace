package com.echotrace.app

import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*

/**
 * The app is the control surface: it always shows the personal code, and when
 * unpaired it carries the full pairing flow itself - nothing depends on adding
 * the widget first. The widget stays the display surface.
 */
class MainActivity : BaseActivity() {
    private var pulse: ValueAnimator? = null
    private val ui = Handler(Looper.getMainLooper())
    private val foregroundPoll = object : Runnable {
        override fun run() {
            WorkScheduler.pollNow(this@MainActivity)
            // While the control surface is visible, keep checking. This is cheap,
            // bounded to foreground use, and avoids waiting for the 15-minute job.
            ui.postDelayed(this, with(Prefs) { foregroundSeconds }.toLong() * 1000L)
        }
    }

    private val refreshStatus = object : Runnable {
        override fun run() {
            refreshPairUi(); refreshUploadDiagUi(); refreshConnectionUi(); refreshStatusCard()
            ui.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkScheduler.ensure(this)
        WorkScheduler.pollNow(this)
        ListenService.sync(this)
        setContentView(R.layout.activity_main)
        if (with(Prefs) { animations }) Anim.entrance(findViewById<LinearLayout>(R.id.mainRoot), 60)

        findViewById<TextView>(R.id.myCode).text = Prefs.deviceId(this)
        Anim.pop(findViewById(R.id.myCode))

        var taps = 0
        var lastTap = 0L
        findViewById<TextView>(R.id.appTitle).setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            taps = if (now - lastTap <= 700L) taps + 1 else 1
            lastTap = now
            if (taps == 10) {
                taps = 0
                startActivity(android.content.Intent(this, AdminActivity::class.java))
            }
        }
        findViewById<Button>(R.id.copyCode).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("EchoTrace", Prefs.deviceId(this)))
            Toast.makeText(this, getString(R.string.code_copied), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.checkNow).setOnClickListener {
            WorkScheduler.pollNow(this)
            Toast.makeText(this, getString(R.string.check_queued), Toast.LENGTH_SHORT).show()
        }
        wireSettings()
        wireConnect()
        wireStartOver()
        refreshPairUi()
        findViewById<View>(R.id.widgetDebug).visibility = View.GONE
        refreshUploadDiagUi()

        val btn = findViewById<Button>(R.id.addWidget)
        Anim.pressScale(btn)
        // Keep the control surface still and quiet.
        btn.setOnClickListener {
            val mgr = AppWidgetManager.getInstance(this)
            val cn = ComponentName(this, TraceWidgetProvider::class.java)
            if (mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(cn, null, null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(foregroundPoll)
        foregroundPoll.run()
        ui.removeCallbacks(refreshStatus)
        refreshStatus.run()
        findViewById<TextView>(R.id.myCode)?.text = Prefs.deviceId(this)
        refreshPairUi()
        findViewById<View>(R.id.widgetDebug).visibility = View.GONE
        refreshUploadDiagUi()
    }

    private fun wireSettings() {
        val he = with(Prefs) { lang } == "he"
        val dark = with(Prefs) { darkTheme }
        // The button shows the language you would switch TO.
        findViewById<Button>(R.id.langBtn).apply {
            text = if (he) "EN" else "עב"
            setOnClickListener {
                with(Prefs) { lang = if (he) "en" else "he" }
                applyAndRecreate()
            }
        }
        findViewById<Button>(R.id.themeBtn).apply {
            text = getString(if (dark) R.string.theme_light else R.string.theme_dark)
            setOnClickListener {
                with(Prefs) { darkTheme = !dark }
                applyAndRecreate()
            }
        }
        findViewById<Switch>(R.id.wifiSwitch).apply {
            isChecked = with(Prefs) { wifiOnly }
            setOnCheckedChangeListener { _, v -> with(Prefs) { wifiOnly = v } }
        }
        findViewById<Switch>(R.id.animSwitch).apply {
            isChecked = with(Prefs) { animations }
            setOnCheckedChangeListener { _, v -> with(Prefs) { animations = v } }
        }
        findViewById<TextView>(R.id.versionRow).text = getString(R.string.set_version) + " " + BuildConfig.VERSION_NAME
        wireInstant()
        wireWallpaper()
        applySystemBars(dark)
    }

    private val restorePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val ok = bytes != null && bytes.isNotEmpty() && bytes.size < 30_000_000 &&
                Imaging.decodeSampled(bytes, 560) != null
            if (ok) { WallpaperApplier.saveRestorePicture(this, bytes!!); toast(R.string.restore_saved) }
            else toast(R.string.restore_fail)
        } catch (_: Throwable) { toast(R.string.restore_fail) }
    }

    private val notifPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { _ ->
        ListenService.sync(this)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun wireInstant() {
        findViewById<Switch>(R.id.instantSwitch).apply {
            isChecked = with(Prefs) { instantMode }
            setOnCheckedChangeListener { _, v ->
                with(Prefs) { instantMode = v }
                if (v && android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else ListenService.sync(this@MainActivity)
            }
        }
        findViewById<Button>(R.id.batteryBtn).setOnClickListener {
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + packageName)))
            } catch (_: Throwable) {}
        }
    }

    private fun wireWallpaper() {
        val group = findViewById<RadioGroup>(R.id.wallGroup)
        val ids = intArrayOf(R.id.wall0, R.id.wall1, R.id.wall2, R.id.wall3)
        group.check(ids[with(Prefs) { wallpaperMode }])
        group.setOnCheckedChangeListener { _, id ->
            val i = ids.indexOf(id)
            if (i >= 0) {
                with(Prefs) { wallpaperMode = i }
                try { WidgetRenderer.updateAll(applicationContext) } catch (_: Throwable) {}
                if (i > 0) Thread { WallpaperApplier.refresh(applicationContext, force = true) }.start()
            }
        }
        findViewById<Button>(R.id.restoreBtn).setOnClickListener {
            try { restorePicker.launch("image/*") } catch (_: Throwable) { toast(R.string.restore_fail) }
        }
        findViewById<Button>(R.id.restoreNow).setOnClickListener {
            val mode = with(Prefs) { wallpaperMode }
            when {
                mode == 0 -> toast(R.string.restore_mode)
                !WallpaperApplier.hasRestorePicture(this) -> toast(R.string.restore_none)
                WallpaperApplier.restore(this, mode) -> toast(R.string.restore_done)
                else -> toast(R.string.restore_fail)
            }
        }
    }

    private fun ago(ms: Long): String {
        val p = Logic.agoParts(System.currentTimeMillis() - ms)
        return getString(when (p[0]) { 0 -> R.string.ago_min; 1 -> R.string.ago_hour; else -> R.string.ago_day }, p[1])
    }

    private fun refreshStatusCard() {
        val partner = with(Prefs) { partner }
        val pending = with(Prefs) { pendingPartner }
        val st = Logic.connState(partner != null, pending != null, with(Prefs) { disconnected }, with(Prefs) { syncError } != null)
        val seen = with(Prefs) { partnerLastSeenAt }
        val dark = with(Prefs) { darkTheme }
        // Muted tones: sage / sand / dusty rose, readable on both themes.
        val color = when (st) {
            Logic.ConnState.CONNECTED -> if (dark) 0xFF8FB39A else 0xFF4F7A5C
            Logic.ConnState.WAITING -> if (dark) 0xFFD4B483 else 0xFF9A7B3F
            Logic.ConnState.OFFLINE -> if (dark) 0xFFB1A69A else 0xFF7A7064
            Logic.ConnState.ERROR -> if (dark) 0xFFD08B7A else 0xFF9C4A38
            Logic.ConnState.NONE -> if (dark) 0xFFB1A69A else 0xFF7A7064
        }.toInt()
        findViewById<TextView>(R.id.cardDot).setTextColor(color)
        val (t, s) = when (st) {
            Logic.ConnState.NONE -> getString(R.string.st_none_t) to getString(R.string.st_none_s)
            Logic.ConnState.WAITING -> getString(R.string.st_wait_t) to getString(R.string.st_wait_s, pending ?: "")
            Logic.ConnState.CONNECTED -> getString(R.string.st_ok_t) to
                (if (seen > 0) getString(R.string.st_ok_s, ago(seen)) else getString(R.string.st_ok_s_unknown))
            Logic.ConnState.OFFLINE -> getString(R.string.st_off_t) to
                (if (seen > 0) getString(R.string.st_off_s, ago(seen)) else getString(R.string.st_off_s_unknown))
            Logic.ConnState.ERROR -> getString(R.string.st_err_t) to getString(R.string.st_err_s)
        }
        findViewById<TextView>(R.id.cardTitle).text = t
        findViewById<TextView>(R.id.cardSub).text = s
    }

    private fun applySystemBars(dark: Boolean) {
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } catch (_: Throwable) {}
    }

    /** Language/theme apply by recreating the activity; the widget is refreshed so its
     *  few words follow the language. Never lets a widget error break the toggle. */
    private fun applyAndRecreate() {
        try { WidgetRenderer.updateAll(applicationContext, force = true) } catch (_: Throwable) {}
        recreate()
    }

    private fun refreshPairUi() {
        val partner = with(Prefs) { partner }
        findViewById<View>(R.id.pairSection).visibility =
            if (partner == null) View.VISIBLE else View.GONE
        val disconnected = with(Prefs) { disconnected }
        val st = findViewById<TextView>(R.id.pairStatus)
        val reset = findViewById<Button>(R.id.startOver)
        reset.visibility = if (partner != null && disconnected) View.VISIBLE else View.GONE
        if (partner == null) {
            val pending = with(Prefs) { pendingPartner }
            st.visibility = if (pending == null) View.GONE else View.VISIBLE
            st.text = getString(R.string.pending_fmt, pending ?: "")
        } else {
            st.visibility = View.VISIBLE
            st.text = if (disconnected)
                getString(R.string.partner_inactive)
            else
                getString(R.string.paired_fmt, partner)
        }
    }

    /** Diagnostic build: surface the last widget push outcome inside the app, so a
     *  launcher-side failure is distinguishable from an app-side one. */
    private fun refreshDebugUi() {
        val dbg = findViewById<TextView>(R.id.widgetDebug)
        // While unpaired the pairing surface owns the screen: a stale "widget
        // updated" diagnostic next to a pairing error reads as a contradiction.
        if (with(Prefs) { partner } == null) {
            dbg.visibility = View.GONE
            return
        }
        val err = WidgetRenderer.lastError(this)
        val ok = WidgetRenderer.lastPush(this)
        when {
            err != null -> {
                dbg.visibility = View.VISIBLE
                dbg.text = getString(R.string.debug_push_err, err)
            }
            ok != null -> {
                dbg.visibility = View.VISIBLE
                dbg.text = getString(R.string.debug_push_ok, ok)
            }
            else -> dbg.visibility = View.GONE
        }
    }

    /** Last upload attempt, recorded by UploadWorker - visible without asking the server. */
    private fun refreshUploadDiagUi() {
        val view = findViewById<TextView>(R.id.uploadDebug)
        val diag = with(Prefs) { lastUploadDiag }
        if (diag == null) {
            view.visibility = View.GONE
        } else {
            view.visibility = View.VISIBLE
            view.text = when {
                diag.startsWith("התקבלה בשרת") -> getString(R.string.upload_received) + diag.removePrefix("התקבלה בשרת")
                diag.contains("בתור") -> getString(R.string.upload_queued)
                else -> getString(R.string.upload_failed)
            }
        }
    }

    private fun refreshConnectionUi() {
        val last = with(Prefs) { lastSyncAt }
        val error = with(Prefs) { syncError }
        fun stamp(ms: Long) = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(ms))
        findViewById<TextView>(R.id.syncStatus).text = when {
            error != null -> getString(R.string.sync_error)
            last == 0L -> getString(R.string.sync_none)
            else -> getString(R.string.sync_ok, stamp(last))
        }
        val photo = TraceMeta.load(this)
        findViewById<TextView>(R.id.receiveStatus).text = if (photo == null)
            getString(R.string.receive_none)
        else getString(R.string.receive_ok, stamp(photo.exposedAt))
        findViewById<TextView>(R.id.connectionNote).text = if (with(Prefs) { disconnected })
            getString(R.string.conn_note_disc)
        else getString(R.string.conn_note_ok)
    }

    private fun wireConnect() {
        val input = findViewById<EditText>(R.id.partnerCode)
        val err = findViewById<TextView>(R.id.error)
        val btn = findViewById<Button>(R.id.connectBtn)
        val roles = findViewById<RadioGroup>(R.id.roleGroup)
        Anim.pressScale(btn)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btn.performClick()
                true
            } else false
        }

        btn.setOnClickListener {
            val code = input.text.toString().trim().uppercase()
            if (!code.matches(Regex("^[A-HJ-NP-Z2-9]{6}$")) || code == Prefs.deviceId(this)) {
                Anim.shake(input)
                err.text = getString(R.string.bad_code)
                err.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val role = when (roles.checkedRadioButtonId) {
                R.id.roleSender -> "sender"
                R.id.roleViewer -> "viewer"
                else -> "both"
            }
            val me = Prefs.deviceId(this)
            input.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
            btn.isEnabled = false
            btn.text = getString(R.string.connecting)
            err.visibility = View.GONE
            input.isEnabled = false
            Thread {
                try {
                    Api.register(me)
                    with(Prefs) { registered = true }
                    val result = Api.connect(me, code, role)
                    // A pending response is not a connection. Adopt only poll-verified state.
                    with(Prefs) { pendingPartner = code; this@MainActivity.role = role }
                    if (result.optBoolean("pending", false)) {
                        with(Prefs) { partner = null }
                    }
                    ui.post {
                        WidgetRenderer.updateAll(this, force = true)
                        WorkScheduler.pollNow(this)
                        if (!isFinishing && !isDestroyed) {
                            btn.text = getString(R.string.check_conn)
                            btn.isEnabled = true
                            input.isEnabled = true
                            refreshPairUi()
                        }
                    }
                } catch (e: Exception) {
                    ui.post {
                        if (!isFinishing && !isDestroyed) {
                            btn.isEnabled = true
                            btn.text = getString(R.string.connect)
                            input.isEnabled = true
                            Anim.shake(input)
                            err.visibility = View.VISIBLE
                            err.text = when {
                                e is Api.ApiException && e.httpCode == 409 -> getString(R.string.err_conflict)
                                e is Api.ApiException && e.httpCode == 400 -> getString(R.string.err_bad_code)
                                else -> getString(R.string.err_network)
                            }
                        }
                    }
                }
            }.start()
        }
    }


    private fun wireStartOver() {
        val btn = findViewById<Button>(R.id.startOver)
        Anim.pressScale(btn)
        btn.setOnClickListener {
            Prefs.reset(this)
            findViewById<EditText>(R.id.partnerCode).text.clear()
            findViewById<RadioButton>(R.id.roleBoth).isChecked = true
            findViewById<Button>(R.id.connectBtn).apply {
                isEnabled = true
                text = getString(R.string.connect)
            }
            WidgetRenderer.updateAll(this, force = true)
            WorkScheduler.pollNow(this)
            findViewById<TextView>(R.id.myCode).text = Prefs.deviceId(this)
            refreshPairUi()
        }
    }

    override fun onPause() {
        ui.removeCallbacks(foregroundPoll)
        ui.removeCallbacks(refreshStatus)
        super.onPause()
    }

    override fun onDestroy() {
        pulse?.cancel()
        super.onDestroy()
    }
}
