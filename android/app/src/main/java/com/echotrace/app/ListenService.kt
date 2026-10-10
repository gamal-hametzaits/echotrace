package com.echotrace.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlin.concurrent.thread

/** Foreground listener: keeps polling about every 30 s so photos arrive without opening the
 *  app. Android's Doze can still pause the network while the phone sits idle; see the in-app
 *  battery button. FCM could later replace this loop behind the same sync call. */
class ListenService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTE_ID, buildNotification(this))
        if (!running) {
            running = true
            worker = thread(name = "echotrace-listen", isDaemon = true) { loop() }
        }
        return START_STICKY
    }

    private fun loop() {
        var failures = 0
        while (running) {
            val c = applicationContext
            if (!with(Prefs) { c.instantMode } || with(Prefs) { c.partner } == null && with(Prefs) { c.pendingPartner } == null) {
                stopSelf(); return
            }
            failures = try { TraceSync.run(c); 0 } catch (_: Exception) { failures + 1 }
            try { Thread.sleep(Logic.listenDelayMs(failures)) } catch (_: InterruptedException) { return }
        }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "echotrace_listen"
        private const val NOTE_ID = 4101

        fun buildNotification(c: Context): Notification {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, Lang.ctx(c).getString(R.string.listen_channel), NotificationManager.IMPORTANCE_MIN))
            }
            val open = android.app.PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(c)
            return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(Lang.ctx(c).getString(R.string.listen_title))
                .setContentText(Lang.ctx(c).getString(R.string.listen_text))
                .setOngoing(true).setContentIntent(open).build()
        }

        /** Starts or stops the listener to match the setting and pairing. Never throws. */
        fun sync(c: Context) {
            try {
                val want = with(Prefs) { c.instantMode } && (with(Prefs) { c.partner } != null || with(Prefs) { c.pendingPartner } != null)
                val i = Intent(c, ListenService::class.java)
                if (want) { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) }
                else c.stopService(i)
            } catch (_: Throwable) { /* not allowed right now (background start limits); WorkManager stays as backup */ }
        }
    }
}
