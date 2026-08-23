package com.anant.splitbill.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.anant.splitbill.data.model.MemberBalance
import com.anant.splitbill.ui.components.Button
import com.anant.splitbill.ui.util.dismissKeyboardOnTap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordRechargeScreen(
    members: List<MemberBalance>,
    currencySymbol: String,
    busy: Boolean,
    onBack: () -> Unit,
    onSubmit: (readings: Map<String, Double>, rechargeMemberId: String, rechargeAmount: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val readings = remember { mutableStateMapOf<String, String>() }
    var rechargeMemberId by remember(members) {
        mutableStateOf(
            members.firstOrNull { it.isNextToRecharge }?.memberId
                ?: members.firstOrNull()?.memberId.orEmpty()
        )
    }
    var rechargeAmount by remember { mutableStateOf("") }

    members.forEach { m ->
        if (m.memberId !in readings) {
            readings[m.memberId] = if (m.lastReading > 0) m.lastReading.toString() else ""
        }
    }

    val canSave = !busy &&
        rechargeMemberId.isNotBlank() &&
        members.all { readings[it.memberId].orEmpty().toDoubleOrNull() != null } &&
        (rechargeAmount.toDoubleOrNull() ?: -1.0) >= 0.0

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Log meter & recharge") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .dismissKeyboardOnTap()
        ) {
            Text(
                text = "1. Meter readings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Enter the current reading for each meter. Values cannot go down.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            members.forEach { member ->
                val typed = readings[member.memberId].orEmpty()
                val newVal = typed.toDoubleOrNull()
                val consumption = if (newVal != null && member.lastReading > 0) {
                    (newVal - member.lastReading).coerceAtLeast(0.0)
                } else {
                    null
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { readings[member.memberId] = it },
                    label = { Text(member.name) },
                    placeholder = { Text("Last: ${formatReading(member.lastReading)}") },
                    supportingText = {
                        Text(
                            when {
                                consumption == null && member.lastReading <= 0 -> "First reading"
                                consumption != null -> "≈ ${"%.1f".format(consumption)} units since last log"
                                else -> "Last reading ${formatReading(member.lastReading)}"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = newVal != null && newVal < member.lastReading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "2. Prepaid recharge",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Who paid for this top-up? Their balance is credited; usage since the last recharge is settled first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            Column(modifier = Modifier.selectableGroup()) {
                members.forEach { member ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = rechargeMemberId == member.memberId,
                                onClick = { rechargeMemberId = member.memberId },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = rechargeMemberId == member.memberId,
                            onClick = { rechargeMemberId = member.memberId }
                        )
                        Column {
                            Text(member.name, style = MaterialTheme.typography.bodyLarge)
                            if (member.isNextToRecharge) {
                                Text(
                                    text = "Suggested — lowest balance",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = rechargeAmount,
                onValueChange = { rechargeAmount = it },
                label = { Text("Recharge amount ($currencySymbol)") },
                placeholder = { Text("0.00") },
                supportingText = { Text("Use 0 to log readings only (settle previous top-up).") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    val parsedReadings = members.associate { m ->
                        m.memberId to (readings[m.memberId].orEmpty().toDoubleOrNull() ?: 0.0)
                    }
                    val amount = rechargeAmount.toDoubleOrNull() ?: 0.0
                    onSubmit(parsedReadings, rechargeMemberId, amount)
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Saving…" else "Save readings & recharge")
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private fun formatReading(value: Double): String =
    if (value == 0.0) "—" else "%.1f".format(value)
