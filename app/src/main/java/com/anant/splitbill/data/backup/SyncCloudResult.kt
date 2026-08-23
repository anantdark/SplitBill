package com.anant.splitbill.data.backup

import com.anant.splitbill.data.database.EntryEntity

/** Result of a pull+push cloud sync. */
data class SyncCloudResult(
    val recordCount: Int,
    val newEntries: List<EntryEntity> = emptyList(),
    /** Soft-deleted entries discovered on pull that this device has not notified yet. */
    val newlyDeletedEntries: List<EntryEntity> = emptyList(),
)
