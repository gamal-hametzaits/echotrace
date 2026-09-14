package com.echotrace.app

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout

/** Shown when tapping the disconnected widget. Restart = wipe local link, new pairing code. */
class DisconnectedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disconnected)
        Anim.entrance(findViewById<LinearLayout>(R.id.disconnectedRoot), 90)
        val btn = findViewById<Button>(R.id.startOver)
        Anim.pressScale(btn)
        btn.setOnClickListener {
            Prefs.reset(this)
            WidgetRenderer.updateAll(this)
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
