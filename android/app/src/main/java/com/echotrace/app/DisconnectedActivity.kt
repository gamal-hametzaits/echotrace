package com.echotrace.app

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.widget.Button

/** Shown when tapping the disconnected widget. Restart = wipe local link, new pairing code. */
class DisconnectedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disconnected)
        findViewById<Button>(R.id.startOver).setOnClickListener {
            Prefs.reset(this)
            WidgetRenderer.updateAll(this)
            finish()
        }
    }
}
