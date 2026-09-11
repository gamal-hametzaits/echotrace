package com.echotrace.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class TraceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        WorkScheduler.ensure(context)
        for (id in ids) mgr.updateAppWidget(id, WidgetRenderer.render(context))
    }
    override fun onEnabled(context: Context) {
        WorkScheduler.ensure(context)
        WorkScheduler.pollNow(context)
    }
}
