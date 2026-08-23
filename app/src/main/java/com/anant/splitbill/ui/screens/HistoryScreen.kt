package com.anant.splitbill.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.EntryType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.anant.splitbill.ui.components.OutlinedButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    entries: List<EntryEntity>,
    currencySymbol: String,
    busy: Boolean,
    onBack: () -> Unit,
    onRevertLastGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val grouped = remember(entries) {
        entries
            .sortedByDescending { it.timestampEpochMs }
            .groupBy { it.groupId }
            .entries
            .sortedByDescending { it.value.maxOf { e -> e.timestampEpochMs } }
    }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Meter log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            OutlinedButton(
                onClick = onRevertLastGroup,
                enabled = !busy && entries.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Undo last log")
            }
        }
    ) { padding ->
        if (grouped.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            ) {
                Text("No meter logs yet — tap Log readings on the home screen.")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(grouped, key = { it.key }) { (groupId, groupEntries) ->
                val ts = groupEntries.maxOf { it.timestampEpochMs }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = dateFormat.format(Date(ts)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        groupEntries.sortedBy { it.timestampEpochMs }.forEach { entry ->
                            Text(
                                text = formatEntryLine(entry, currencySymbol),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
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
            // Legacy rows from older builds; expense logging is no longer exposed in UI.
            "Legacy expense — ${entry.memberName}: $currencySymbol${"%.2f".format(entry.value)}"
    }
