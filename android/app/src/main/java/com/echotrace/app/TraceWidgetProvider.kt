package com.echotrace.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class TraceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        WorkScheduler.ensure(context)
        WorkScheduler.pollNow(context)
        WidgetRenderer.updateAll(context, force = true)
    }
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        // Resize: re-render with the layout variant + decode size for the new cells.
        WidgetRenderer.updateAll(context, force = true)
    }
    override fun onEnabled(context: Context) {
        WorkScheduler.ensure(context)
        WorkScheduler.pollNow(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Fires after an in-place update of this app: repaint immediately from the
        // locally saved photo (it survives updates in internal storage) instead of
        // waiting for a network poll, and make sure polling is (re)scheduled even
        // if the user never opens the app.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WorkScheduler.ensure(context)
            WorkScheduler.pollNow(context)
            WidgetRenderer.updateAll(context, force = true)
        }
    }
}
