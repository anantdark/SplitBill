package com.anant.splitbill.data.backup

import com.anant.splitbill.BuildConfig
import com.anant.splitbill.data.model.ThemeMode
import com.anant.splitbill.data.settings.AppSettings
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BackupSettings(
    val onboardingComplete: Boolean = false,
    val activeRoomId: String? = null,
    val themeMode: String = ThemeMode.SYSTEM.name,
    val dynamicColor: Boolean = true,
    val supportId: String = "",
    val crashReportingEnabled: Boolean = !BuildConfig.DEBUG && !BuildConfig.IS_FDROID,
    val crashReportingPromptCompleted: Boolean = BuildConfig.IS_FDROID,
    val developerModeUnlocked: Boolean = false,
    val cloudBackupEnabled: Boolean = true,
    val cloudAutoUploadEnabled: Boolean = true,
    val cloudBackupPasswordSet: Boolean = false,
    val mongoDbName: String = AppSettings.DEFAULT_MONGO_DB_NAME,
    val mongoCollectionName: String = AppSettings.DEFAULT_MONGO_COLLECTION,
    val lastCloudBackupAtEpochMs: Long = 0L,
) {
    fun toAppSettings(current: AppSettings): AppSettings {
        val mode = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM)
        return current.copy(
            onboardingComplete = onboardingComplete,
            activeRoomId = activeRoomId,
            themeMode = mode,
            dynamicColor = dynamicColor,
            supportId = supportId.ifBlank { current.supportId },
            crashReportingEnabled = crashReportingEnabled,
            crashReportingPromptCompleted = crashReportingPromptCompleted,
            developerModeUnlocked = developerModeUnlocked,
            cloudBackupEnabled = cloudBackupEnabled,
            cloudAutoUploadEnabled = cloudAutoUploadEnabled,
            cloudBackupPasswordSet = cloudBackupPasswordSet,
            mongoDbName = mongoDbName.ifBlank { AppSettings.DEFAULT_MONGO_DB_NAME },
            mongoCollectionName = mongoCollectionName.ifBlank { AppSettings.DEFAULT_MONGO_COLLECTION },
            lastCloudBackupAtEpochMs = lastCloudBackupAtEpochMs,
        )
    }

    companion object {
        fun from(settings: AppSettings): BackupSettings = BackupSettings(
            onboardingComplete = settings.onboardingComplete,
            activeRoomId = settings.activeRoomId,
            themeMode = settings.themeMode.name,
            dynamicColor = settings.dynamicColor,
            supportId = settings.supportId,
            crashReportingEnabled = settings.crashReportingEnabled,
            crashReportingPromptCompleted = settings.crashReportingPromptCompleted,
            developerModeUnlocked = settings.developerModeUnlocked,
            cloudBackupEnabled = settings.cloudBackupEnabled,
            cloudAutoUploadEnabled = settings.cloudAutoUploadEnabled,
            cloudBackupPasswordSet = settings.cloudBackupPasswordSet,
            mongoDbName = settings.mongoDbName,
            mongoCollectionName = settings.mongoCollectionName,
            lastCloudBackupAtEpochMs = settings.lastCloudBackupAtEpochMs,
        )
    }
}
