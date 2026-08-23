package com.anant.splitbill.data.backup

import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.database.MemberEntity
import com.anant.splitbill.data.database.RoomEntity
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RoomDevice(
    val deviceId: String,
    val deviceName: String = "",
    val memberId: String? = null,
    val memberName: String? = null,
    val lastSeenAtEpochMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class AuditEvent(
    val id: String,
    val action: String,
    val atEpochMs: Long,
    val deviceId: String = "",
    val deviceName: String = "",
    val memberId: String? = null,
    val memberName: String? = null,
    val entryId: String? = null,
    val groupId: String? = null,
    val detail: String = "",
)

@JsonClass(generateAdapter = true)
data class BackupData(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val rooms: List<RoomEntity> = emptyList(),
    val members: List<MemberEntity> = emptyList(),
    val entries: List<EntryEntity> = emptyList(),
    val settings: BackupSettings? = null,
    /** Devices that have synced this room (for cloud tracing / device count). */
    val devices: List<RoomDevice> = emptyList(),
    /** Recent push / soft-delete actions for tracing. */
    val auditLog: List<AuditEvent> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 2
        const val MAX_AUDIT_EVENTS = 100
    }
}

sealed interface BackupImportResult {
    data class Success(val recordCount: Int) : BackupImportResult
    data object WrongPassword : BackupImportResult
    data object Corrupt : BackupImportResult
    data object Unrecognized : BackupImportResult
    data object PasswordRequired : BackupImportResult
    /** No cloud document exists for the requested Room / Support ID (HTTP 404). */
    data object NotFound : BackupImportResult
    data class Failed(val message: String) : BackupImportResult
}
