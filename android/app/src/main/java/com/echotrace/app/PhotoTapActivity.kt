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
        WidgetRenderer.updateAll(this)
        finish()
    }
}
