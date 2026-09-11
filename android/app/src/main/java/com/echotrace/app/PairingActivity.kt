package com.echotrace.app

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*

class PairingActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        val me = Prefs.deviceId(this)
        findViewById<TextView>(R.id.myCode).text = me
        val input = findViewById<EditText>(R.id.partnerCode)
        val err = findViewById<TextView>(R.id.error)
        val btn = findViewById<Button>(R.id.connectBtn)
        val roles = findViewById<RadioGroup>(R.id.roleGroup)
        btn.setOnClickListener {
            val code = input.text.toString().trim().uppercase()
            if (code.length != 6) { err.visibility = View.VISIBLE; return@setOnClickListener }
            val role = when (roles.checkedRadioButtonId) {
                R.id.roleSender -> "sender"
                R.id.roleViewer -> "viewer"
                else -> "both"
            }
            btn.isEnabled = false
            btn.text = getString(R.string.connecting)
            err.visibility = View.GONE
            Thread {
                try {
                    Api.register(me)
                    with(Prefs) { registered = true }
                    Api.connect(me, code, role)
                    with(Prefs) { partner = code; this@PairingActivity.role = role; disconnected = false }
                    ui.post {
                        WidgetRenderer.updateAll(this)
                        WorkScheduler.pollNow(this)
                        finish()
                    }
                } catch (e: Exception) {
                    ui.post {
                        btn.isEnabled = true
                        btn.text = getString(R.string.connect)
                        err.visibility = View.VISIBLE
                    }
                }
            }.start()
        }
    }
}
