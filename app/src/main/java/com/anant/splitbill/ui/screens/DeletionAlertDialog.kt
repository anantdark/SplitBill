package com.anant.splitbill.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anant.splitbill.data.database.EntryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shown when cloud sync finds recharges another device deleted that this device hasn't seen yet. */
@Composable
fun DeletionAlertDialog(
    deletedEntries: List<EntryEntity>,
    currencySymbol: String,
    onDismiss: () -> Unit,
) {
    if (deletedEntries.isEmpty()) return
    val dateFormat = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                if (deletedEntries.size == 1) "A recharge was deleted" else "${deletedEntries.size} recharges were deleted"
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                deletedEntries.forEach { entry ->
                    val by = entry.deletedByMemberName?.takeIf { it.isNotBlank() } ?: "Someone"
                    val amount = formatMoney(currencySymbol, entry.value)
                    val when_ = entry.deletedAtEpochMs?.let { dateFormat.format(Date(it)) }
                    Column {
                        Text(
                            text = "$by removed $amount",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (when_ != null) {
                            Text(
                                text = when_,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

private fun formatMoney(symbol: String, amount: Double): String {
    val trimmed = if (amount == amount.toLong().toDouble()) {
        amount.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", amount)
    }
    return "$symbol$trimmed"
}
