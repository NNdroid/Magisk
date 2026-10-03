package com.topjohnwu.magisk.webui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.topjohnwu.magisk.core.Config

/** Restores the persistent TV WebUI after boot and application updates. */
class WebUiBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val supported = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            else -> false
        }
        if (!supported || !Config.webUiEnabled) return

        WebUiService.start(context.applicationContext)
    }
}
