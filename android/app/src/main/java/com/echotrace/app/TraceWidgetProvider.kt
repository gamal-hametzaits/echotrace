package com.echotrace.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class TraceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        try {
            WorkScheduler.ensure(context)
            WorkScheduler.pollNow(context)
            WidgetRenderer.updateAll(context, force = true)
        } catch (t: Throwable) {
            WidgetRenderer.pushError(context, "onUpdate", t)
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        // Resize: re-render with the layout variant + decode size for the new cells.
        try {
            WidgetRenderer.updateAll(context, force = true)
        } catch (t: Throwable) {
            WidgetRenderer.pushError(context, "optionsChanged", t)
        }
    }
    override fun onEnabled(context: Context) {
        try {
            WorkScheduler.ensure(context)
            WorkScheduler.pollNow(context)
        } catch (t: Throwable) {
            WidgetRenderer.pushError(context, "onEnabled", t)
        }
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Fires after an in-place update of this app: repaint immediately from the
        // locally saved photo (it survives updates in internal storage) instead of
        // waiting for a network poll, and make sure polling is (re)scheduled even
        // if the user never opens the app.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                WorkScheduler.ensure(context)
                WorkScheduler.pollNow(context)
                WidgetRenderer.updateAll(context, force = true)
            } catch (t: Throwable) {
                WidgetRenderer.pushError(context, "packageReplaced", t)
            }
        }
    }
}
