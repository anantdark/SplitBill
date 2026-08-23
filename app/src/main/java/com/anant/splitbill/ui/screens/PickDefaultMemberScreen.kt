package com.anant.splitbill.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anant.splitbill.data.model.MemberBalance
import com.anant.splitbill.ui.components.Button
import com.anant.splitbill.ui.util.dismissKeyboardOnTap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickDefaultMemberScreen(
    members: List<MemberBalance>,
    initialMemberId: String? = null,
    onConfirm: (memberId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedId by remember(members, initialMemberId) {
        mutableStateOf(
            initialMemberId?.takeIf { id -> members.any { it.memberId == id } }
                ?: members.firstOrNull()?.memberId.orEmpty()
        )
    }

    BackHandler { /* Require a choice */ }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Who are you?") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .dismissKeyboardOnTap()
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Recharges you log will be credited to this member by default. " +
                    "You can change this later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.selectableGroup()) {
                members.forEach { member ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedId == member.memberId,
                                onClick = { selectedId = member.memberId },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 6.dp)
                    ) {
                        RadioButton(
                            selected = selectedId == member.memberId,
                            onClick = { selectedId = member.memberId }
                        )
                        Text(
                            text = member.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selectedId == member.memberId) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = { onConfirm(selectedId) },
                enabled = selectedId.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
