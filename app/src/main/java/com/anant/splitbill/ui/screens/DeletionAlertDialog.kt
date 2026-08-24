package com.anant.splitbill.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.util.SystemToast
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
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        // Must be dismissed via the button below, not by tapping outside or pressing back.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$by removed $amount",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            entry.deletedByMemberId?.takeIf { it.isNotBlank() }?.let { id ->
                                Text(
                                    text = " · ${truncateIdForDialog(id)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable {
                                        clipboard.setText(AnnotatedString(id))
                                        SystemToast.show(context, "Member ID copied")
                                    }
                                )
                            }
                        }
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

private fun truncateIdForDialog(id: String): String =
    if (id.length <= 8) id else "${id.take(8)}…"

private fun formatMoney(symbol: String, amount: Double): String {
    val trimmed = if (amount == amount.toLong().toDouble()) {
        amount.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", amount)
    }
    return "$symbol$trimmed"
}
