package com.anant.splitbill.data.settings

import com.anant.splitbill.BuildConfig
import com.anant.splitbill.data.model.ThemeMode

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val activeRoomId: String? = null,
    /** Member who pays / logs recharges by default on this device (“yourself”). Local-only. */
    val defaultMemberId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val supportId: String = "",
    val crashReportingEnabled: Boolean = !BuildConfig.IS_FDROID,
    val crashReportingPromptCompleted: Boolean = true,
    val developerModeUnlocked: Boolean = false,
    val cloudBackupEnabled: Boolean = true,
    /**
     * When false (developer option), skips startup / hourly auto sync.
     * Manual Sync now / push still available from Settings.
     */
    val cloudAutoUploadEnabled: Boolean = true,
    val cloudBackupPasswordSet: Boolean = false,
    val mongoDbName: String = DEFAULT_MONGO_DB_NAME,
    val mongoCollectionName: String = DEFAULT_MONGO_COLLECTION,
    val lastCloudBackupAtEpochMs: Long = 0L,
    val autoCheckUpdates: Boolean = !BuildConfig.IS_FDROID,
    /** Used to gate auto-download after "Export backup & download". */
    val lastSuccessfulBackupAt: Long = 0L,
    val lastKnownVersionCode: Int = 0,
    /**
     * Soft-deleted entry IDs this device already notified about (avoids duplicate alerts).
     * Local-only — not synced to cloud.
     */
    val notifiedDeletionIds: Set<String> = emptySet(),
    /** JSON array of [com.anant.splitbill.data.backup.RoomDevice] for this room (synced in backup). */
    val roomDevicesJson: String = "[]",
    /** JSON array of [com.anant.splitbill.data.backup.AuditEvent] (synced in backup). */
    val auditLogJson: String = "[]",
) {
    companion object {
        const val DEFAULT_MONGO_DB_NAME = "fitbuddy"
        const val DEFAULT_MONGO_COLLECTION = "split_bill"
        /** Minimum gap between automatic background / due syncs. */
        const val CLOUD_BACKUP_DEBOUNCE_MS = 60L * 60L * 1000L
        /** Avoid double-sync if the process restarts within a minute. */
        const val STARTUP_SYNC_MIN_INTERVAL_MS = 60L * 1000L
        /** Max age of [lastSuccessfulBackupAt] before auto-download after backup-and-download. */
        const val BACKUP_FRESHNESS_FOR_UPDATE_MS: Long = 5L * 60L * 1000L
    }

    fun hasFreshSuccessfulBackup(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lastSuccessfulBackupAt <= 0L) return false
        val age = nowMs - lastSuccessfulBackupAt
        return age in 0L..BACKUP_FRESHNESS_FOR_UPDATE_MS
    }
}
