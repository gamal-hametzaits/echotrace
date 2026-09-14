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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkScheduler.ensure(this)
        WorkScheduler.pollNow(this)
        setContentView(R.layout.activity_main)
        Anim.entrance(findViewById<LinearLayout>(R.id.mainRoot), 60)

        findViewById<TextView>(R.id.myCode).text = Prefs.deviceId(this)
        Anim.pop(findViewById(R.id.myCode))

        wireConnect()
        wireStartOver()
        refreshPairUi()
        refreshDebugUi()
        refreshUploadDiagUi()

        val btn = findViewById<Button>(R.id.addWidget)
        Anim.pressScale(btn)
        pulse = Anim.pulse(btn)
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
        findViewById<TextView>(R.id.myCode)?.text = Prefs.deviceId(this)
        refreshPairUi()
        refreshDebugUi()
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
            st.visibility = View.GONE
        } else {
            st.visibility = View.VISIBLE
            st.text = if (disconnected)
                getString(R.string.disconnected)
            else
                getString(R.string.pair_status_paired, partner)
        }
    }

    /** Diagnostic build: surface the last widget push outcome inside the app, so a
     *  launcher-side failure is distinguishable from an app-side one. */
    private fun refreshDebugUi() {
        val dbg = findViewById<TextView>(R.id.widgetDebug)
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
            view.text = getString(R.string.debug_upload, diag)
        }
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
            if (code.length != 6) {
                Anim.shake(input)
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
                    Api.connect(me, code, role)
                    with(Prefs) { partner = code; this@MainActivity.role = role; disconnected = false }
                    ui.post {
                        WidgetRenderer.updateAll(this, force = true)
                        WorkScheduler.pollNow(this)
                        if (!isFinishing && !isDestroyed) {
                            btn.text = getString(R.string.connected_ok)
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

    override fun onDestroy() {
        pulse?.cancel()
        super.onDestroy()
    }
}
