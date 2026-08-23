package com.anant.splitbill.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.anant.splitbill.data.database.AppDatabase
import com.anant.splitbill.data.repository.BillEngine
import com.anant.splitbill.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

class BalanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = SettingsRepository(context).settings.first()
        val roomId = settings.activeRoomId
        val snapshot = if (roomId.isNullOrBlank()) {
            WidgetSnapshot.empty()
        } else {
            val db = AppDatabase.get(context)
            val room = db.roomDao().getRoom(roomId)
            val members = db.memberDao().getMembers(roomId)
            val entries = db.entryDao().getEntries(roomId)
            if (room == null || members.isEmpty()) {
                WidgetSnapshot.empty()
            } else {
                val state = BillEngine.rebuild(members, entries)
                val next = state.nextRechargeMember()
                WidgetSnapshot(
                    roomName = room.name,
                    currency = room.currencySymbol,
                    nextName = next?.name,
                    lines = state.members.map { m ->
                        "${m.name}: ${BillEngine.formatMoney(m.balance, room.currencySymbol)}"
                    }
                )
            }
        }

        provideContent {
            GlanceTheme {
                BalanceWidgetContent(snapshot)
            }
        }
    }
}

internal data class WidgetSnapshot(
    val roomName: String?,
    val currency: String,
    val nextName: String?,
    val lines: List<String>
) {
    companion object {
        fun empty() = WidgetSnapshot(null, "Rs.", null, emptyList())
    }
}

@Composable
internal fun BalanceWidgetContent(snapshot: WidgetSnapshot) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp)
    ) {
        if (snapshot.roomName == null) {
            Text(
                text = "Open SplitBill to set up a room",
                style = TextStyle(fontSize = 14.sp)
            )
            return@Column
        }
        Text(
            text = snapshot.roomName,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        )
        snapshot.nextName?.let { next ->
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = "Next: $next",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        snapshot.lines.forEach { line ->
            Text(text = line, style = TextStyle(fontSize = 12.sp))
            Spacer(GlanceModifier.height(2.dp))
        }
    }
}
