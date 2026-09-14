package com.echotrace.app

import androidx.activity.ComponentActivity
import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : ComponentActivity() {
    private var pulse: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkScheduler.ensure(this)
        WorkScheduler.pollNow(this)
        setContentView(R.layout.activity_main)
        Anim.entrance(findViewById<LinearLayout>(R.id.mainRoot), 90)
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

    override fun onDestroy() {
        pulse?.cancel()
        super.onDestroy()
    }
}
