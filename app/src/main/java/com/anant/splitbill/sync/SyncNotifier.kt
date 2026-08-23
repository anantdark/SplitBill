package com.anant.splitbill.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.anant.splitbill.MainActivity
import com.anant.splitbill.R
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.EntryType
import java.util.Locale

/** Posts a local notification when cloud sync pulls new or deleted meter / recharge rows. */
object SyncNotifier {
    const val CHANNEL_ID = "cloud_sync_updates"
    private const val NOTIFICATION_ID = 4101
    private const val DELETE_NOTIFICATION_ID = 4102

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sync_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.sync_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyNewEntries(context: Context, newEntries: List<EntryEntity>, currencySymbol: String = "Rs.") {
        if (newEntries.isEmpty()) return
        val app = context.applicationContext
        val notifier = NotificationManagerCompat.from(app)
        if (!notifier.areNotificationsEnabled()) return
        ensureChannel(app)
        val (title, body) = summarize(newEntries, currencySymbol)
        post(app, notifier, NOTIFICATION_ID, title, body)
    }

    fun notifyDeletedEntries(
        context: Context,
        deletedEntries: List<EntryEntity>,
        currencySymbol: String = "Rs.",
    ) {
        val recharges = deletedEntries.filter { it.type == EntryType.RECHARGE }
        if (recharges.isEmpty()) return
        val app = context.applicationContext
        val notifier = NotificationManagerCompat.from(app)
        if (!notifier.areNotificationsEnabled()) return
        ensureChannel(app)
        val title = if (recharges.size == 1) "Recharge deleted" else "${recharges.size} recharges deleted"
        val body = recharges.take(3).joinToString(" · ") { e ->
            val by = e.deletedByMemberName?.takeIf { it.isNotBlank() } ?: "Someone"
            "$by removed ${formatMoney(currencySymbol, e.value)}"
        } + if (recharges.size > 3) "…" else ""
        post(app, notifier, DELETE_NOTIFICATION_ID, title, body)
    }

    private fun post(
        app: Context,
        notifier: NotificationManagerCompat,
        id: Int,
        title: String,
        body: String,
    ) {
        val contentIntent = PendingIntent.getActivity(
            app,
            id,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { notifier.notify(id, notification) }
    }

    fun summarize(entries: List<EntryEntity>, currencySymbol: String): Pair<String, String> {
        val recharges = entries.filter { it.type == EntryType.RECHARGE }
        val groups = entries.map { it.groupId }.distinct().size
        val title = when {
            recharges.size == 1 -> "New recharge logged"
            recharges.size > 1 -> "${recharges.size} new recharges"
            else -> "Room updated from cloud"
        }
        val body = when {
            recharges.size == 1 -> {
                val e = recharges.first()
                "${e.memberName} added ${formatMoney(currencySymbol, e.value)}"
            }
            recharges.isNotEmpty() -> recharges.take(3).joinToString(" · ") {
                "${it.memberName}: ${formatMoney(currencySymbol, it.value)}"
            } + if (recharges.size > 3) "…" else ""
            groups == 1 -> "1 new meter log synced"
            else -> "$groups new meter logs synced"
        }
        return title to body
    }

    private fun formatMoney(symbol: String, amount: Double): String {
        val trimmed = if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
        return "$symbol$trimmed"
    }
}
