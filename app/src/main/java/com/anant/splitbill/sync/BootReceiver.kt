package com.anant.splitbill.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anant.splitbill.crash.HeartbeatScheduler

/** Re-arms the daily heartbeat alarm after reboot or app update. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        HeartbeatScheduler.schedule(context.applicationContext)
    }
}
