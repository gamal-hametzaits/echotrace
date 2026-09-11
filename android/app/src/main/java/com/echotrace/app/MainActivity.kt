package com.echotrace.app

import androidx.activity.ComponentActivity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Button

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkScheduler.ensure(this)
        WorkScheduler.pollNow(this)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.addWidget).setOnClickListener {
            val mgr = AppWidgetManager.getInstance(this)
            val cn = ComponentName(this, TraceWidgetProvider::class.java)
            if (mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(cn, null, null)
            }
        }
    }
}
