package com.anant.splitbill.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.anant.splitbill.ui.components.Button
import com.anant.splitbill.ui.components.CraftedWithLoveCredit
import com.anant.splitbill.ui.components.OutlinedButton
import com.anant.splitbill.ui.util.dismissKeyboardOnTap

private enum class OnboardingStep { Welcome, Room, Members, Finish }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    isSaving: Boolean,
    isRestoring: Boolean,
    cloudRestoreAvailable: Boolean,
    onComplete: (roomName: String, members: List<String>) -> Unit,
    onRestoreLocal: (Uri, suspend () -> CharArray?) -> Unit,
    onRestoreCloud: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onHeartDoubleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var step by remember { mutableStateOf(OnboardingStep.Welcome) }
    var roomName by remember { mutableStateOf("") }
    val members = remember { mutableStateListOf<String>() }
    var memberInput by remember { mutableStateOf("") }
    var joinRoomId by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onRestoreLocal(it) { null } }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (step) {
                    OnboardingStep.Welcome -> {
                        Button(
                            onClick = { step = OnboardingStep.Room },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Get started") }
                    }
                    OnboardingStep.Room -> {
                        OutlinedButton(
                            onClick = { step = OnboardingStep.Welcome },
                            modifier = Modifier.weight(1f)
                        ) { Text("Back") }
                        Button(
                            onClick = { step = OnboardingStep.Members },
                            enabled = roomName.trim().isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Next") }
                    }
                    OnboardingStep.Members -> {
                        OutlinedButton(
                            onClick = { step = OnboardingStep.Room },
                            modifier = Modifier.weight(1f)
                        ) { Text("Back") }
                        Button(
                            onClick = { step = OnboardingStep.Finish },
                            enabled = members.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Next") }
                    }
                    OnboardingStep.Finish -> {
                        OutlinedButton(
                            onClick = { step = OnboardingStep.Members },
                            modifier = Modifier.weight(1f),
                            enabled = !isSaving
                        ) { Text("Back") }
                        Button(
                            onClick = { onComplete(roomName.trim(), members.toList()) },
                            enabled = !isSaving && members.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                            } else {
                                Text("Create room")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .dismissKeyboardOnTap(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (step) {
                OnboardingStep.Welcome -> {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = "SplitBill",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Log prepaid meter readings and recharges — see who should top up next.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    CraftedWithLoveCredit(onHeartDoubleTap = onHeartDoubleTap)
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Already have a room?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter the Room ID shared by someone in the house to restore their meter log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = joinRoomId,
                        onValueChange = { joinRoomId = it },
                        label = { Text("Room ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onJoinRoom(joinRoomId) },
                        enabled = !isRestoring && joinRoomId.isNotBlank() && cloudRestoreAvailable,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isRestoring) "Joining…" else "Join room")
                    }
                    if (cloudRestoreAvailable) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRestoreCloud,
                            enabled = !isRestoring,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isRestoring) "Restoring…" else "Restore with this device's Room ID")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        enabled = !isRestoring,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restore from file")
                    }

                }
                OnboardingStep.Room -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Name your room",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = roomName,
                        onValueChange = { roomName = it },
                        label = { Text("Room name") },
                        placeholder = { Text("Flat 4B") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OnboardingStep.Members -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Add members",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "People on this prepaid electricity meter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = memberInput,
                            onValueChange = { memberInput = it },
                            label = { Text("Member name") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                val name = memberInput.trim()
                                if (name.isNotBlank() && name !in members) {
                                    members += name
                                    memberInput = ""
                                }
                            }),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val name = memberInput.trim()
                                if (name.isNotBlank() && name !in members) {
                                    members += name
                                    memberInput = ""
                                }
                            }
                        ) { Text("Add") }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        members.forEach { name ->
                            AssistChip(
                                onClick = { members.remove(name) },
                                label = { Text(name) },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove")
                                }
                            )
                        }
                    }
                }
                OnboardingStep.Finish -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Ready to go",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Room: ${roomName.trim()}")
                    Text("Members: ${members.joinToString()}")
                    Text(
                        text = "Currency defaults to Rs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
