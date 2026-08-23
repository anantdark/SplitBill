package com.anant.splitbill.crash

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar
import java.util.TimeZone

/**
 * Schedules the daily Sentry heartbeat alarm at the next 00:00 UTC.
 * Android may batch inexact alarms (Doze), but each firing re-arms for the following midnight.
 */
object HeartbeatScheduler {

    private const val TAG = "HeartbeatScheduler"
    private const val REQUEST_CODE = 8201

    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = nextMidnightUtcMillis()
        val pending = pendingIntent(app)
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }.onFailure { e ->
            Log.e(TAG, "Failed to schedule heartbeat alarm", e)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(app))
    }

    private fun nextMidnightUtcMillis(): Long {
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val next = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return next.timeInMillis
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, HeartbeatReceiver::class.java).apply {
            action = HeartbeatReceiver.ACTION_HEARTBEAT
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
