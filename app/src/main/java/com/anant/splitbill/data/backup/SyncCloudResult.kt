package com.anant.splitbill.data.backup

import com.anant.splitbill.data.database.EntryEntity

/** Result of a pull+push cloud sync. */
data class SyncCloudResult(
    val recordCount: Int,
    val newEntries: List<EntryEntity> = emptyList(),
    /** Soft-deleted entries discovered on pull that this device has not notified yet. */
    val newlyDeletedEntries: List<EntryEntity> = emptyList(),
    /** True when a push pulled cloud history first to avoid wiping recharges. */
    val recoveredHistory: Boolean = false,
    /**
     * Set when pull refused to import because cloud is missing local recharges.
     * UI should prompt; [BackupManager.confirmOverwriteLocalFromCloud] applies cloud.
     */
    val historyConflict: CloudHistoryConflict? = null,
)
