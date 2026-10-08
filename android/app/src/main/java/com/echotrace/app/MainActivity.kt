package com.echotrace.app

import androidx.activity.ComponentActivity
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
class MainActivity : ComponentActivity() {
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
            refreshPairUi(); refreshUploadDiagUi(); refreshConnectionUi()
            ui.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkScheduler.ensure(this)
        WorkScheduler.pollNow(this)
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
            Toast.makeText(this, "הקוד הועתק", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.checkNow).setOnClickListener {
            WorkScheduler.pollNow(this)
            Toast.makeText(this, "בדיקה הועברה לתור. התוצאה תתעדכן כאן", Toast.LENGTH_SHORT).show()
        }
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
            st.text = "ממתינים לאישור חיבור · ${pending ?: ""}"
        } else {
            st.visibility = View.VISIBLE
            st.text = if (disconnected)
                "הצד השני לא פעיל לאחרונה"
            else
                "חיבור רשום · $partner"
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
                diag.startsWith("התקבלה בשרת") -> diag
                diag.contains("בתור") -> "התמונה בתור לשליחה"
                else -> "השליחה עדיין לא הצליחה. פרטים בחלון הניהול"
            }
        }
    }

    private fun refreshConnectionUi() {
        val last = with(Prefs) { lastSyncAt }
        val error = with(Prefs) { syncError }
        fun stamp(ms: Long) = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(ms))
        findViewById<TextView>(R.id.syncStatus).text = when {
            error != null -> "הבדיקה האחרונה לא הצליחה. החיבור לאינטרנט או השרת לא זמינים כרגע"
            last == 0L -> "עוד אין בדיקה מאומתת מול השרת"
            else -> "השרת נבדק · ${stamp(last)}"
        }
        val photo = TraceMeta.load(this)
        findViewById<TextView>(R.id.receiveStatus).text = if (photo == null)
            "עדיין לא התקבלה תמונה במכשיר הזה"
        else "תמונה אחרונה התקבלה במכשיר · ${stamp(photo.exposedAt)}"
        findViewById<TextView>(R.id.connectionNote).text = if (with(Prefs) { disconnected })
            "הצד השני לא פנה לשרת יותר מ־5 ימים. זה אינו אישור שהאפליקציה נמחקה"
        else "שליחה לשרת אינה אישור הגעה למכשיר השני"
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
                            btn.text = "בדיקת חיבור"
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
                                e is Api.ApiException && e.httpCode == 409 -> "אחד המכשירים כבר מחובר לאדם אחר"
                                e is Api.ApiException && e.httpCode == 400 -> "בדקו את הקוד. אי אפשר להתחבר לעצמך"
                                else -> "לא הצלחנו להגיע לשרת. בדקו את האינטרנט ונסו שוב"
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
