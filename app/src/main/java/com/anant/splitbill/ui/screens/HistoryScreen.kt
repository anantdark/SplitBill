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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import com.anant.splitbill.data.analytics.UsageAnalytics
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.DeletionRules
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
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(grouped, key = { it.key }) { (groupId, groupEntries) ->
                                val ts = groupEntries.maxOf { it.timestampEpochMs }
                                val recharge = groupEntries.firstOrNull { it.type == EntryType.RECHARGE }
                                val lineItems = groupEntries
                                    .filter { it.type != EntryType.RECHARGE }
                                    .sortedBy { it.memberName }
                                val loggedByName = groupEntries.firstNotNullOfOrNull {
                                    it.loggedByMemberName?.takeIf { n -> n.isNotBlank() }
                                }
                                // The chip shown next to "Added by"/"Created" is the device's ID, not
                                // the member's — a member can log in from more than one device, and
                                // the device-to-member mapping/metadata itself stays cloud-only.
                                val loggedByDeviceId = groupEntries.firstNotNullOfOrNull {
                                    it.loggedByDeviceId?.takeIf { id -> id.isNotBlank() }
                                }
                                val noteText = groupEntries.firstNotNullOfOrNull {
                                    it.note.takeIf { n -> n.isNotBlank() }
                                }
                                val canDelete = filter != HistoryFilter.Deleted &&
                                    canDeleteLatest &&
                                    groupId == latestRechargeGroupId
                                val quietSelfDelete = filter == HistoryFilter.Deleted &&
                                    recharge?.let { DeletionRules.isQuietSelfDelete(it) } == true
                                var expanded by remember(groupId) { mutableStateOf(false) }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(if (quietSelfDelete) 0.55f else 1f),
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            filter == HistoryFilter.Deleted && !quietSelfDelete ->
                                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                            else -> CardDefaults.cardColors().containerColor
                                        }
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                if (recharge != null) Icons.Filled.Bolt else Icons.Filled.Schedule,
                                                contentDescription = null,
                                                tint = if (filter == HistoryFilter.Deleted && !quietSelfDelete) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = recharge?.let {
                                                        "$currencySymbol${"%.2f".format(it.value)}"
                                                    } ?: "Meter reading",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                if (recharge != null) {
                                                    Text(
                                                        text = recharge.memberName,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            if (filter == HistoryFilter.Deleted && !quietSelfDelete) {
                                                Icon(
                                                    Icons.Filled.WarningAmber,
                                                    contentDescription = "Notable deletion",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = dateFormat.format(Date(ts)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (filter == HistoryFilter.Deleted && recharge != null) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            if (quietSelfDelete) {
                                                MetaLine(
                                                    icon = Icons.Filled.Person,
                                                    text = "Self-corrected within 5 min — not flagged",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontStyle = FontStyle.Italic,
                                                )
                                            } else {
                                                PersonMetaLine(
                                                    icon = Icons.Filled.Schedule,
                                                    label = "Created",
                                                    timeText = dateFormat.format(Date(recharge.timestampEpochMs)),
                                                    name = loggedByName,
                                                    memberId = loggedByDeviceId,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    clipboard = clipboard,
                                                    context = context,
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                PersonMetaLine(
                                                    icon = Icons.Filled.PersonOff,
                                                    label = "Deleted",
                                                    timeText = recharge.deletedAtEpochMs
                                                        ?.let { dateFormat.format(Date(it)) }.orEmpty(),
                                                    name = recharge.deletedByMemberName?.takeIf { it.isNotBlank() }
                                                        ?: "unknown",
                                                    // Same-name, different-device deletions are common (multiple
                                                    // devices logged in as this member) — the ID shown is the
                                                    // deleting device's, not a fixed per-member value.
                                                    memberId = recharge.deletedByDeviceId,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    clipboard = clipboard,
                                                    context = context,
                                                )
                                                recharge.deletedAtEpochMs?.let { deletedAt ->
                                                    Text(
                                                        text = formatGap(recharge.timestampEpochMs, deletedAt),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.Medium,
                                                        modifier = Modifier.padding(top = 4.dp, start = 22.dp)
                                                    )
                                                }
                                            }
                                        } else if (loggedByName != null) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            PersonMetaLine(
                                                icon = Icons.Filled.Person,
                                                label = "Added by",
                                                timeText = null,
                                                name = loggedByName,
                                                memberId = loggedByDeviceId,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                clipboard = clipboard,
                                                context = context,
                                            )
                                        }

                                        noteText?.let { note ->
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(verticalAlignment = Alignment.Top) {
                                                Icon(
                                                    Icons.Filled.FormatQuote,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = note,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontStyle = FontStyle.Italic,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }

                                        if (lineItems.isNotEmpty()) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 10.dp)
                                                    .clickable { expanded = !expanded }
                                            ) {
                                                Text(
                                                    text = "Meter readings (${lineItems.size})",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Icon(
                                                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                    contentDescription = if (expanded) "Collapse" else "Expand",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            AnimatedVisibility(
                                                visible = expanded,
                                                enter = fadeIn() + expandVertically(),
                                                exit = fadeOut() + shrinkVertically(),
                                            ) {
                                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                                    lineItems.forEach { entry ->
                                                        Text(
                                                            text = formatEntryLine(entry, currencySymbol),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            modifier = Modifier.padding(vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (canDelete) {
                                            Button(
                                                onClick = { pendingDeleteGroupId = groupId },
                                                enabled = !busy,
                                                modifier = Modifier
                                                    .padding(top = 10.dp)
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

/** Icon + label row, e.g. a quiet-self-delete caption. */
@Composable
private fun MetaLine(
    icon: ImageVector,
    text: String,
    tint: Color,
    fontStyle: FontStyle? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = tint, fontStyle = fontStyle)
    }
}

/**
 * Icon + "label [time ·] name [id-chip]" row — the id-chip copies the full ID on tap.
 * [memberId] here is the *device's* ID, not a fixed per-member value: the same member
 * name can show a different ID per line if they logged in from a different device.
 */
@Composable
private fun PersonMetaLine(
    icon: ImageVector,
    label: String,
    timeText: String?,
    name: String?,
    memberId: String?,
    tint: Color,
    clipboard: ClipboardManager,
    context: Context,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        val text = when {
            !timeText.isNullOrBlank() && !name.isNullOrBlank() -> "$label $timeText · $name"
            !timeText.isNullOrBlank() -> "$label $timeText"
            !name.isNullOrBlank() -> "$label $name"
            else -> label
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
        memberId?.takeIf { it.isNotBlank() }?.let { id ->
            Spacer(modifier = Modifier.width(4.dp))
            IdChip(id = id, tint = tint, copyLabel = "Member ID copied", clipboard = clipboard, context = context)
        }
    }
}

/** Small rounded pill showing a truncated ID; tapping copies the full ID to clipboard. */
@Composable
private fun IdChip(
    id: String,
    tint: Color,
    copyLabel: String,
    clipboard: ClipboardManager,
    context: Context,
) {
    Text(
        text = truncateId(id),
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clickable {
                clipboard.setText(AnnotatedString(id))
                SystemToast.show(context, copyLabel)
            }
    )
}

/** Human "Deleted Xm/h/d after creation" caption for a notable (non-quiet) deletion. */
private fun formatGap(fromMs: Long, toMs: Long): String {
    val diffMin = (toMs - fromMs).coerceAtLeast(0) / 60_000
    return when {
        diffMin < 1 -> "Deleted under a minute after creation"
        diffMin < 60 -> "Deleted $diffMin min after creation"
        diffMin < 60 * 24 -> {
            val h = diffMin / 60
            val m = diffMin % 60
            if (m == 0L) "Deleted ${h}h after creation" else "Deleted ${h}h ${m}m after creation"
        }
        else -> "Deleted ${diffMin / (60 * 24)}d after creation"
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
