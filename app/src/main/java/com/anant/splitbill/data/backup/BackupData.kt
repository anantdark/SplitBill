package com.anant.splitbill.data.backup

import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.database.MemberEntity
import com.anant.splitbill.data.database.RoomEntity
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BackupData(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val rooms: List<RoomEntity> = emptyList(),
    val members: List<MemberEntity> = emptyList(),
    val entries: List<EntryEntity> = emptyList(),
    val settings: BackupSettings? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

sealed interface BackupImportResult {
    data class Success(val recordCount: Int) : BackupImportResult
    data object WrongPassword : BackupImportResult
    data object Corrupt : BackupImportResult
    data object Unrecognized : BackupImportResult
    data object PasswordRequired : BackupImportResult
}
