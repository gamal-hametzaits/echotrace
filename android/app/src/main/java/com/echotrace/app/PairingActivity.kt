package com.echotrace.app

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import android.widget.LinearLayout

class PairingActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkScheduler.ensure(this)
        WorkScheduler.pollNow(this)
        setContentView(R.layout.activity_pairing)
        val root = findViewById<LinearLayout>(R.id.pairingRoot)
        Anim.entrance(root)
        Anim.pop(findViewById(R.id.myCode))

        val me = Prefs.deviceId(this)
        findViewById<TextView>(R.id.myCode).text = me
        val input = findViewById<EditText>(R.id.partnerCode)
        val err = findViewById<TextView>(R.id.error)
        val btn = findViewById<Button>(R.id.connectBtn)
        val spinner = findViewById<ProgressBar>(R.id.spinner)
        val roles = findViewById<RadioGroup>(R.id.roleGroup)
        Anim.pressScale(btn)

        btn.setOnClickListener {
            val code = input.text.toString().trim().uppercase()
            if (code.length != 6) {
                Anim.shake(input)
                err.alpha = 0f
                err.visibility = View.VISIBLE
                err.animate().alpha(1f).setDuration(200).start()
                return@setOnClickListener
            }
            val role = when (roles.checkedRadioButtonId) {
                R.id.roleSender -> "sender"
                R.id.roleViewer -> "viewer"
                else -> "both"
            }
            btn.isEnabled = false
            btn.text = getString(R.string.connecting)
            spinner.visibility = View.VISIBLE
            err.visibility = View.GONE
            input.isEnabled = false
            input.animate().alpha(0.6f).setDuration(200).start()
            Thread {
                try {
                    Api.register(me)
                    with(Prefs) { registered = true }
                    Api.connect(me, code, role)
                    with(Prefs) { partner = code; this@PairingActivity.role = role; disconnected = false }
                    ui.post {
                        spinner.visibility = View.GONE
                        btn.text = getString(R.string.connected_ok)
                        Anim.celebrate(btn) {
                            WidgetRenderer.updateAll(this)
                            WorkScheduler.pollNow(this)
                            finish()
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                    }
                } catch (e: Exception) {
                    ui.post {
                        btn.isEnabled = true
                        btn.text = getString(R.string.connect)
                        spinner.visibility = View.GONE
                        input.isEnabled = true
                        input.animate().alpha(1f).setDuration(200).start()
                        Anim.shake(input)
                        err.alpha = 0f
                        err.visibility = View.VISIBLE
                        err.animate().alpha(1f).setDuration(200).start()
                    }
                }
            }.start()
        }
    }
}
