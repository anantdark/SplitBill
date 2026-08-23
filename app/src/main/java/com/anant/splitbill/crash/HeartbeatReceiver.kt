package com.anant.splitbill.crash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anant.splitbill.SplitBillApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fires around UTC midnight (inexact alarm). Sends the daily heartbeat if not already
 * sent today, then re-arms the alarm for the next midnight.
 */
class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_HEARTBEAT) return
        val app = context.applicationContext as? SplitBillApp ?: return
        val settingsRepository = app.settingsRepository

        runBlocking {
            val today = LocalDate.now(ZoneOffset.UTC).toString()
            if (settingsRepository.lastHeartbeatUtcDay() == today) {
                HeartbeatScheduler.schedule(context)
                return@runBlocking
            }
            val settings = settingsRepository.settings.first()
            val roomCount = runCatching { app.repository.roomCount() }.getOrDefault(0)
            val entryCount = runCatching { app.repository.totalEntryCount() }.getOrDefault(0)
            val info = HeartbeatInfo(
                roomCount = roomCount,
                entryCount = entryCount,
                isDeveloper = settings.developerModeUnlocked,
            )
            if (CrashReporter.sendHeartbeat(info, HeartbeatKind.DAILY)) {
                settingsRepository.markHeartbeatSent(today)
            }
            HeartbeatScheduler.schedule(context)
        }
    }

    companion object {
        const val ACTION_HEARTBEAT = "com.anant.splitbill.action.SENTRY_HEARTBEAT"
    }
}
