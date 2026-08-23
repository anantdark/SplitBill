package com.anant.splitbill.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ElectricMeter
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.anant.splitbill.ui.components.Button
import com.anant.splitbill.ui.components.CraftedWithLoveCredit
import com.anant.splitbill.ui.components.OutlinedButton
import com.anant.splitbill.ui.util.dismissKeyboardOnTap
import com.anant.splitbill.ui.util.rememberDismissKeyboard

private enum class OnboardingStep { Welcome, Room, Members, Self, Finish }

private val MemberAccentPalette = listOf(
    Color(0xFF0F6B5C),
    Color(0xFF8B5E00),
    Color(0xFF3D5A80),
    Color(0xFF9A3412),
    Color(0xFF5B4B8A),
    Color(0xFF0E7490),
)

@Composable
fun OnboardingScreen(
    isSaving: Boolean,
    isRestoring: Boolean,
    cloudRestoreAvailable: Boolean,
    onComplete: (roomName: String, members: List<String>, defaultMemberName: String) -> Unit,
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
    var selfMemberName by remember { mutableStateOf("") }
    var joinRoomId by remember { mutableStateOf("") }

    fun tryAddMember() {
        val name = memberInput.trim()
        if (name.isNotBlank() && name !in members) {
            members += name
            memberInput = ""
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onRestoreLocal(it) { null } }
    }

    val showBottomBar = step != OnboardingStep.Welcome

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (step) {
                            OnboardingStep.Welcome -> Unit
                            OnboardingStep.Room -> {
                                OutlinedButton(
                                    onClick = { step = OnboardingStep.Welcome },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("Back") }
                                Button(
                                    onClick = { step = OnboardingStep.Members },
                                    enabled = roomName.trim().isNotBlank(),
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Next")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            OnboardingStep.Members -> {
                                OutlinedButton(
                                    onClick = { step = OnboardingStep.Room },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("Back") }
                                Button(
                                    onClick = {
                                        if (selfMemberName !in members) {
                                            selfMemberName = members.firstOrNull().orEmpty()
                                        }
                                        step = OnboardingStep.Self
                                    },
                                    enabled = members.isNotEmpty(),
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Next")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            OnboardingStep.Self -> {
                                OutlinedButton(
                                    onClick = { step = OnboardingStep.Members },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("Back") }
                                Button(
                                    onClick = { step = OnboardingStep.Finish },
                                    enabled = selfMemberName.isNotBlank() && selfMemberName in members,
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("Next")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            OnboardingStep.Finish -> {
                                OutlinedButton(
                                    onClick = { step = OnboardingStep.Self },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    enabled = !isSaving
                                ) { Text("Back") }
                                Button(
                                    onClick = {
                                        onComplete(
                                            roomName.trim(),
                                            members.toList(),
                                            selfMemberName
                                        )
                                    },
                                    enabled = !isSaving &&
                                        members.isNotEmpty() &&
                                        selfMemberName.isNotBlank(),
                                    modifier = Modifier
                                        .weight(1.4f)
                                        .height(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    if (isSaving) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(22.dp),
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Text("Create room")
                                    }
                                }
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
                .verticalScroll(rememberScrollState())
                .dismissKeyboardOnTap(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (step) {
                OnboardingStep.Welcome -> {
                    WelcomeStep(
                        joinRoomId = joinRoomId,
                        onJoinRoomIdChange = { joinRoomId = it },
                        isRestoring = isRestoring,
                        cloudRestoreAvailable = cloudRestoreAvailable,
                        onGetStarted = { step = OnboardingStep.Room },
                        onJoinRoom = { onJoinRoom(joinRoomId) },
                        onRestoreCloud = onRestoreCloud,
                        onRestoreFile = {
                            importLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        onHeartDoubleTap = onHeartDoubleTap
                    )
                }
                OnboardingStep.Room -> {
                    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
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
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
                OnboardingStep.Members -> {
                    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Who's on the meter?",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Add everyone who shares this prepaid connection. " +
                                "You can reorder fairness later with readings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        OutlinedTextField(
                            value = memberInput,
                            onValueChange = { memberInput = it },
                            label = { Text("Name") },
                            placeholder = { Text("e.g. Priya") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { tryAddMember() }),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            trailingIcon = {
                                IconButton(
                                    onClick = { tryAddMember() },
                                    enabled = memberInput.trim().isNotBlank()
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "Add member"
                                    )
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { tryAddMember() },
                            enabled = memberInput.trim().isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add to room")
                        }

                        Spacer(modifier = Modifier.height(28.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Room roster",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = when (members.size) {
                                    0 -> "None yet"
                                    1 -> "1 person"
                                    else -> "${members.size} people"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        AnimatedVisibility(
                            visible = members.isEmpty(),
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            MemberEmptyState()
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            members.forEachIndexed { index, name ->
                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn() + slideInVertically(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        ),
                                        initialOffsetY = { it / 3 }
                                    ) + scaleIn(
                                        initialScale = 0.92f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                ) {
                                    MemberRosterRow(
                                        name = name,
                                        index = index,
                                        onRemove = { members.remove(name) }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                OnboardingStep.Self -> {
                    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Who are you?",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Recharges you log will be credited to this member by default.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        members.forEach { name ->
                            val selected = selfMemberName == name
                            Surface(
                                onClick = { selfMemberName = name },
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                OnboardingStep.Finish -> {
                    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                        ReadyToGoSection(
                            roomName = roomName.trim(),
                            members = members.toList(),
                            selfMemberName = selfMemberName
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(
    joinRoomId: String,
    onJoinRoomIdChange: (String) -> Unit,
    isRestoring: Boolean,
    cloudRestoreAvailable: Boolean,
    onGetStarted: () -> Unit,
    onJoinRoom: () -> Unit,
    onRestoreCloud: () -> Unit,
    onRestoreFile: () -> Unit,
    onHeartDoubleTap: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .padding(horizontal = 24.dp)
            .padding(top = 36.dp, bottom = 28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(primary.copy(alpha = 0.22f), primaryContainer)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ElectricMeter,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(44.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "SplitBill",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Log prepaid meter readings and recharges — see who should top up next.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "Get started",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = "  or join existing  ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.GroupAdd,
                        contentDescription = null,
                        tint = primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Already have a room?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Paste the Room ID shared with you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = joinRoomId,
                onValueChange = onJoinRoomIdChange,
                label = { Text("Room ID") },
                placeholder = { Text("xxxxxxxx-xxxx-…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                enabled = !isRestoring
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onJoinRoom,
                enabled = !isRestoring && joinRoomId.isNotBlank() && cloudRestoreAvailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                if (isRestoring) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onSecondary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Joining…")
                } else {
                    Text("Join room")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (cloudRestoreAvailable) {
            WelcomeSecondaryAction(
                icon = Icons.Outlined.CloudDownload,
                title = "Restore this device",
                subtitle = "Pull the cloud backup for this phone's Room ID",
                enabled = !isRestoring,
                onClick = onRestoreCloud
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        WelcomeSecondaryAction(
            icon = Icons.Outlined.FolderOpen,
            title = "Restore from file",
            subtitle = "Import a SplitBill backup JSON",
            enabled = !isRestoring,
            onClick = onRestoreFile
        )

        Spacer(modifier = Modifier.height(36.dp))
        CraftedWithLoveCredit(onHeartDoubleTap = onHeartDoubleTap)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeSecondaryAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val dismiss = rememberDismissKeyboard()
    Surface(
        onClick = { dismiss(); onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ReadyToGoSection(
    roomName: String,
    members: List<String>,
    selfMemberName: String,
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    Spacer(modifier = Modifier.height(20.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(primaryContainer, primary.copy(alpha = 0.22f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Ready to go",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Looks solid — create the room when you're set.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (selfMemberName.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Recharges default to $selfMemberName",
                style = MaterialTheme.typography.labelLarge,
                color = primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    Spacer(modifier = Modifier.height(28.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Home,
                    contentDescription = null,
                    tint = primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Your room",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = roomName.ifBlank { "Untitled room" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadyMetaChip(label = "Currency", value = "Rs.")
            ReadyMetaChip(
                label = "People",
                value = if (members.size == 1) "1 member" else "${members.size} members"
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Household",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        MemberAvatarStack(names = members)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        members.forEachIndexed { index, name ->
            MemberRosterRow(
                name = name,
                index = index,
                onRemove = null
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Tap Create room below to start logging readings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun ReadyMetaChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MemberAvatarStack(names: List<String>, maxVisible: Int = 4) {
    if (names.isEmpty()) return
    val visible = names.take(maxVisible)
    val overflow = names.size - visible.size
    val border = MaterialTheme.colorScheme.surface
    Row {
        visible.forEachIndexed { index, name ->
            val accent = MemberAccentPalette[index % MemberAccentPalette.size]
            Box(
                modifier = Modifier
                    .zIndex((visible.size - index).toFloat())
                    .offset(x = (-10 * index).dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(2.dp, border, CircleShape)
                    .background(accent.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = memberInitials(name).take(1),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }
        }
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .zIndex(0f)
                    .offset(x = (-10 * visible.size).dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(2.dp, border, CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun MemberEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PersonAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "No one added yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Type a name above and tap Add to room.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MemberRosterRow(
    name: String,
    index: Int,
    onRemove: (() -> Unit)?,
) {
    val accent = MemberAccentPalette[index % MemberAccentPalette.size]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = memberInitials(name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Member ${index + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Remove $name",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun memberInitials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}
