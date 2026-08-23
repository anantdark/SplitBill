package com.anant.splitbill.data.settings

import com.anant.splitbill.BuildConfig
import com.anant.splitbill.data.model.ThemeMode

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val activeRoomId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val supportId: String = "",
    val crashReportingEnabled: Boolean = !BuildConfig.DEBUG && !BuildConfig.IS_FDROID,
    val crashReportingPromptCompleted: Boolean = BuildConfig.IS_FDROID,
    val developerModeUnlocked: Boolean = false,
    val cloudBackupEnabled: Boolean = true,
    /**
     * Reserved for backup payloads; cloud sync is always on when the vault is present.
     * Startup sync still respects [CLOUD_BACKUP_DEBOUNCE_MS].
     */
    val cloudAutoUploadEnabled: Boolean = true,
    val cloudBackupPasswordSet: Boolean = false,
    val mongoDbName: String = DEFAULT_MONGO_DB_NAME,
    val mongoCollectionName: String = DEFAULT_MONGO_COLLECTION,
    val lastCloudBackupAtEpochMs: Long = 0L,
    val lastKnownVersionCode: Int = 0,
) {
    companion object {
        const val DEFAULT_MONGO_DB_NAME = "splitbill"
        const val DEFAULT_MONGO_COLLECTION = "split_bill"
        const val CLOUD_BACKUP_DEBOUNCE_MS = 12L * 60L * 60L * 1000L
    }
}
