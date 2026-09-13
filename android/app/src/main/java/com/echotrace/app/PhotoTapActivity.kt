package com.echotrace.app

import androidx.activity.ComponentActivity
import android.os.Bundle

/** Tapping the photo marks it as viewed (the fade clock itself starts at first display). */
class PhotoTapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TraceMeta.load(this)?.let { m ->
            if (!m.viewed) TraceMeta.save(this, m.copy(viewed = true))
        }
        // A tap is the user's "check now": don't make them wait for the 15-min timer.
        WorkScheduler.pollNow(this)
        WidgetRenderer.updateAll(this)
        finish()
    }
}
