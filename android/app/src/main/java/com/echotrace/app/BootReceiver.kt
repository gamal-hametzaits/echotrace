package com.echotrace.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the listener after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WorkScheduler.ensure(context)
        ListenService.sync(context)
    }
}
