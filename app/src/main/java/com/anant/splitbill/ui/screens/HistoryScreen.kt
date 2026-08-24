package com.anant.splitbill.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anant.splitbill.data.analytics.UsageAnalytics
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.EntryType
import com.anant.splitbill.ui.components.Button
import com.anant.splitbill.ui.components.MemberUsageBarChart
import com.anant.splitbill.util.SystemToast
import com.anant.splitbill.ui.components.MonthlyUsageBarChart
import com.anant.splitbill.ui.components.UsageChartCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HistoryFilter(val label: String) {
    Usage("Usage"),
    Recharges("Recharges"),
    Deleted("Deleted"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    entries: List<EntryEntity>,
    currencySymbol: String,
    busy: Boolean,
    onDeleteRechargeGroup: (groupId: String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    embedded: Boolean = false,
    modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf(HistoryFilter.Usage) }
    var pendingDeleteGroupId by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val usageSummary = remember(entries) { UsageAnalytics.summarize(entries) }
    val weekAgo = remember { System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L }
    val latestRecharge = remember(entries) {
        entries
            .filter { it.type == EntryType.RECHARGE && !it.deleted }
            .maxByOrNull { it.timestampEpochMs }
    }
    val latestRechargeGroupId = latestRecharge?.groupId
    val canDeleteLatest = latestRecharge != null && latestRecharge.timestampEpochMs >= weekAgo

    val grouped = remember(entries, filter) {
        val source = when (filter) {
            HistoryFilter.Recharges -> entries.filter { entry ->
                !entry.deleted && (
                    entry.type == EntryType.RECHARGE ||
                        entries.any {
                            it.groupId == entry.groupId &&
                                it.type == EntryType.RECHARGE &&
                                !it.deleted
                        }
                    )
            }
            HistoryFilter.Deleted -> entries.filter { it.deleted }
            HistoryFilter.Usage -> emptyList()
        }
        source
            .sortedByDescending { it.timestampEpochMs }
            .groupBy { it.groupId }
            .entries
            .sortedByDescending { it.value.maxOf { e -> e.timestampEpochMs } }
    }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    val body: @Composable (PaddingValues) -> Unit = { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HistorySectionTabs(
                filter = filter,
                onFilterChange = { filter = it },
            )
            when (filter) {
                HistoryFilter.Usage -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        UsageChartCard(
                            title = "Monthly usage",
                            subtitle = "Total meter units consumed per month (last 6 months)",
                        ) {
                            MonthlyUsageBarChart(points = usageSummary.monthly)
                        }
                        UsageChartCard(
                            title = "Usage per member",
                            subtitle = if (usageSummary.totalUnits > 0) {
                                "${formatUnits(usageSummary.totalUnits)} units logged in this window"
                            } else {
                                "Breakdown by household member"
                            },
                        ) {
                            MemberUsageBarChart(members = usageSummary.byMember)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                else -> {
                    if (grouped.isEmpty()) {
                        Text(
                            text = when (filter) {
                                HistoryFilter.Recharges ->
                                    "No recharges yet — tap Log readings on Home when you top up."
                                HistoryFilter.Deleted ->
                                    "No deleted recharges."
                                HistoryFilter.Usage -> ""
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(grouped, key = { it.key }) { (groupId, groupEntries) ->
                                val ts = groupEntries.maxOf { it.timestampEpochMs }
                                val recharge = groupEntries.firstOrNull { it.type == EntryType.RECHARGE }
                                val canDelete = filter != HistoryFilter.Deleted &&
                                    canDeleteLatest &&
                                    groupId == latestRechargeGroupId
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = dateFormat.format(Date(ts)),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (filter == HistoryFilter.Deleted && recharge != null) {
                                            Text(
                                                text = "Deleted by ${recharge.deletedByMemberName ?: "unknown"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                        groupEntries
                                            .sortedBy { it.timestampEpochMs }
                                            .filter { entry ->
                                                filter == HistoryFilter.Deleted ||
                                                    entry.type == EntryType.RECHARGE ||
                                                    entry.type == EntryType.READING
                                            }
                                            .forEach { entry ->
                                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                                    Text(
                                                        text = formatEntryLine(entry, currencySymbol),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = if (entry.type == EntryType.RECHARGE) {
                                                            FontWeight.SemiBold
                                                        } else {
                                                            FontWeight.Normal
                                                        },
                                                    )
                                                    entry.loggedByMemberName
                                                        ?.takeIf { it.isNotBlank() }
                                                        ?.let { name ->
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Text(
                                                                    text = "Added by $name",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                )
                                                                entry.loggedByMemberId
                                                                    ?.takeIf { it.isNotBlank() }
                                                                    ?.let { id ->
                                                                        Text(
                                                                            text = " · ${truncateId(id)}",
                                                                            style = MaterialTheme.typography.labelSmall,
                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                            modifier = Modifier.clickable {
                                                                                clipboard.setText(AnnotatedString(id))
                                                                                SystemToast.show(context, "Member ID copied")
                                                                            }
                                                                        )
                                                                    }
                                                            }
                                                        }
                                                    entry.note
                                                        .takeIf { it.isNotBlank() }
                                                        ?.let { note ->
                                                            Text(
                                                                text = "“$note”",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                }
                                            }
                                        if (canDelete) {
                                            Button(
                                                onClick = { pendingDeleteGroupId = groupId },
                                                enabled = !busy,
                                                modifier = Modifier
                                                    .padding(top = 8.dp)
                                                    .fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.error,
                                                    contentColor = MaterialTheme.colorScheme.onError,
                                                ),
                                            ) {
                                                Text("Delete")
                                            }
                                        }
                                    }
                                }
                            }
                            item { Spacer(modifier = Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }

        pendingDeleteGroupId?.let { groupId ->
            AlertDialog(
                onDismissRequest = { pendingDeleteGroupId = null },
                title = { Text("Delete?") },
                text = {
                    Text(
                        "This removes the recharge and its meter readings from balances. " +
                            "A deleted record is kept for audit and other room members will see " +
                            "the change on sync."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeleteGroupId = null
                            onDeleteRechargeGroup(groupId)
                        },
                        enabled = !busy,
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteGroupId = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }

    if (embedded) {
        body(PaddingValues(0.dp))
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("History & usage") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        body(padding)
    }
}

@Composable
private fun HistorySectionTabs(
    filter: HistoryFilter,
    onFilterChange: (HistoryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = HistoryFilter.entries
    val selectedIndex = sections.indexOf(filter)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            sections.forEach { section ->
                val selected = filter == section
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onFilterChange(section) }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = section.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
        ) {
            val tabWidth = maxWidth / sections.size
            Box(
                modifier = Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .offset(x = tabWidth * selectedIndex)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private fun formatEntryLine(entry: EntryEntity, currencySymbol: String): String =
    when (entry.type) {
        EntryType.READING -> {
            val units = entry.consumption?.let { " (${"%.1f".format(it)} units)" }.orEmpty()
            "Reading — ${entry.memberName}: ${"%.1f".format(entry.value)}$units"
        }
        EntryType.RECHARGE ->
            "Recharge — ${entry.memberName}: $currencySymbol${"%.2f".format(entry.value)}"
        EntryType.EXPENSE ->
            "Legacy expense — ${entry.memberName}: $currencySymbol${"%.2f".format(entry.value)}"
    }

private fun formatUnits(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.1f".format(value)

/** Short, tappable form of a member ID — full ID copies to clipboard on tap. */
private fun truncateId(id: String): String =
    if (id.length <= 8) id else "${id.take(8)}…"
