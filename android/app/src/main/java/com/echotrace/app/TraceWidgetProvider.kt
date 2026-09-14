package com.echotrace.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class TraceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        WorkScheduler.ensure(context)
        WorkScheduler.pollNow(context)
        for (id in ids) mgr.updateAppWidget(id, WidgetRenderer.render(context, WidgetRenderer.isCompact(mgr, id)))
        WidgetRenderer.markRendered(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        // Resize: re-render this instance with the layout variant for its new size.
        mgr.updateAppWidget(id, WidgetRenderer.render(context, WidgetRenderer.isCompact(mgr, id)))
        WidgetRenderer.markRendered(context)
    }
    override fun onEnabled(context: Context) {
        WorkScheduler.ensure(context)
        WorkScheduler.pollNow(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Fires after an in-place update of this app: make sure registration +
        // polling are (re)scheduled even if the user never opens the app.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WorkScheduler.ensure(context)
            WorkScheduler.pollNow(context)
        }
    }
}
