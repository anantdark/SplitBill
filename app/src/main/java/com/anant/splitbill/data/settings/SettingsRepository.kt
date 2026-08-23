package com.anant.splitbill.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.anant.splitbill.BuildConfig
import com.anant.splitbill.data.model.ThemeMode
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "splitbill_settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            onboardingComplete = prefs[KEY_ONBOARDING] ?: false,
            activeRoomId = prefs[KEY_ACTIVE_ROOM],
            themeMode = runCatching {
                ThemeMode.valueOf(prefs[KEY_THEME] ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            supportId = prefs[KEY_SUPPORT_ID].orEmpty(),
            crashReportingEnabled = prefs[KEY_CRASH_REPORTING]
                ?: (!BuildConfig.DEBUG && !BuildConfig.IS_FDROID),
            crashReportingPromptCompleted = prefs[KEY_CRASH_PROMPT]
                ?: BuildConfig.IS_FDROID,
            developerModeUnlocked = prefs[KEY_DEVELOPER] ?: false,
            cloudBackupEnabled = prefs[KEY_CLOUD_BACKUP] ?: true,
            cloudAutoUploadEnabled = prefs[KEY_CLOUD_AUTO_UPLOAD] ?: true,
            cloudBackupPasswordSet = prefs[KEY_CLOUD_PASSWORD_SET] ?: false,
            mongoDbName = prefs[KEY_MONGO_DB]?.ifBlank { null }
                ?: AppSettings.DEFAULT_MONGO_DB_NAME,
            mongoCollectionName = normalizeMongoCollection(prefs[KEY_MONGO_COLL]),
            lastCloudBackupAtEpochMs = prefs[KEY_LAST_CLOUD_BACKUP] ?: 0L,
            lastKnownVersionCode = prefs[KEY_LAST_VERSION] ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun ensureSupportId(): String {
        val existing = dataStore.data.first()[KEY_SUPPORT_ID].orEmpty()
        if (existing.isNotBlank()) return existing
        val id = UUID.randomUUID().toString()
        dataStore.edit { it[KEY_SUPPORT_ID] = id }
        return id
    }

    suspend fun regenerateSupportId(): String {
        val id = UUID.randomUUID().toString()
        dataStore.edit { it[KEY_SUPPORT_ID] = id }
        return id
    }

    suspend fun setSupportId(supportId: String) {
        val id = supportId.trim()
        if (id.isBlank()) return
        dataStore.edit { it[KEY_SUPPORT_ID] = id }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING] = next.onboardingComplete
            if (next.activeRoomId.isNullOrBlank()) prefs.remove(KEY_ACTIVE_ROOM)
            else prefs[KEY_ACTIVE_ROOM] = next.activeRoomId
            prefs[KEY_THEME] = next.themeMode.name
            prefs[KEY_DYNAMIC_COLOR] = next.dynamicColor
            prefs[KEY_CRASH_REPORTING] = next.crashReportingEnabled
            prefs[KEY_CRASH_PROMPT] = next.crashReportingPromptCompleted
            prefs[KEY_DEVELOPER] = next.developerModeUnlocked
            prefs[KEY_CLOUD_BACKUP] = next.cloudBackupEnabled
            prefs[KEY_CLOUD_AUTO_UPLOAD] = next.cloudAutoUploadEnabled
            prefs[KEY_CLOUD_PASSWORD_SET] = next.cloudBackupPasswordSet
            prefs[KEY_MONGO_DB] = next.mongoDbName
            prefs[KEY_MONGO_COLL] = next.mongoCollectionName
            prefs[KEY_LAST_CLOUD_BACKUP] = next.lastCloudBackupAtEpochMs
            prefs[KEY_LAST_VERSION] = next.lastKnownVersionCode
            if (next.supportId.isNotBlank()) prefs[KEY_SUPPORT_ID] = next.supportId
        }
    }

    suspend fun setLastKnownVersionCode(code: Int) {
        dataStore.edit { it[KEY_LAST_VERSION] = code }
    }

    suspend fun lastKnownVersionCode(): Int? {
        val v = dataStore.data.first()[KEY_LAST_VERSION] ?: return null
        return if (v == 0) null else v
    }

    
    private fun normalizeMongoCollection(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value in LEGACY_MONGO_COLLECTIONS) {
            return AppSettings.DEFAULT_MONGO_COLLECTION
        }
        return value
    }

    companion object {
        private val LEGACY_MONGO_COLLECTIONS = setOf(
            "splitbill_backup",
            "splitbill_backup",
            "splitbill",
        )

        private val KEY_ONBOARDING = booleanPreferencesKey("onboarding_complete")
        private val KEY_ACTIVE_ROOM = stringPreferencesKey("active_room_id")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_SUPPORT_ID = stringPreferencesKey("support_id")
        private val KEY_CRASH_REPORTING = booleanPreferencesKey("crash_reporting")
        private val KEY_CRASH_PROMPT = booleanPreferencesKey("crash_prompt_done")
        private val KEY_DEVELOPER = booleanPreferencesKey("developer_unlocked")
        private val KEY_CLOUD_BACKUP = booleanPreferencesKey("cloud_backup_enabled")
        private val KEY_CLOUD_AUTO_UPLOAD = booleanPreferencesKey("cloud_auto_upload_enabled")
        private val KEY_CLOUD_PASSWORD_SET = booleanPreferencesKey("cloud_backup_password_set")
        private val KEY_MONGO_DB = stringPreferencesKey("mongo_db_name")
        private val KEY_MONGO_COLL = stringPreferencesKey("mongo_collection")
        private val KEY_LAST_CLOUD_BACKUP = longPreferencesKey("last_cloud_backup_at")
        private val KEY_LAST_VERSION = intPreferencesKey("last_known_version_code")
    }
}
