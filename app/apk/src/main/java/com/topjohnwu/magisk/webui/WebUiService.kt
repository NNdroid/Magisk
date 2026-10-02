package com.topjohnwu.magisk.webui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.ui.MainActivity
import com.topjohnwu.magisk.core.R as CoreR

/**
 * Keeps the embedded Android TV WebUI available independently of MainActivity.
 *
 * This is intentionally a foreground service: an HTTP listener is long-lived work and
 * Android may otherwise stop a background service shortly after the TV UI is closed.
 */
class WebUiService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Config.webUiEnabled) {
            WebUiManager.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        // Passing the Service instance tells WebUiManager that this is the actual
        // long-lived owner of the listeners, not a request coming from an Activity.
        WebUiManager.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        WebUiManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.webui_service_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.webui_service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("ForegroundServiceType")
    private fun promoteToForeground() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setSmallIcon(CoreR.drawable.ic_magisk)
            setContentTitle(getString(R.string.webui_service_title))
            setContentText(getString(R.string.webui_service_text, Config.webUiPort))
            setContentIntent(contentIntent)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setCategory(Notification.CATEGORY_SERVICE)
        }.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "tv_webui"
        private const val NOTIFICATION_ID = 0x18091

        fun start(context: Context) {
            val intent = Intent(context, WebUiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebUiService::class.java))
        }
    }
}
