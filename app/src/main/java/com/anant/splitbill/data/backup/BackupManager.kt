package com.anant.splitbill.data.backup

import android.content.Context
import android.net.Uri
import com.anant.splitbill.data.backup.crypto.BackupCrypto
import com.anant.splitbill.data.backup.crypto.BackupFormat
import com.anant.splitbill.data.backup.crypto.BackupPasswordStore
import com.anant.splitbill.data.backup.crypto.OpenResult
import com.anant.splitbill.data.backup.mongo.MongoBackupRepository
import com.anant.splitbill.data.backup.mongo.MongoUriVault
import com.anant.splitbill.data.repository.SplitBillRepository
import com.anant.splitbill.data.settings.AppSettings
import com.anant.splitbill.data.settings.SettingsRepository
import com.anant.splitbill.util.DeviceIdentity
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class BackupManager(
    private val context: Context,
    private val repository: SplitBillRepository,
    private val settingsRepository: SettingsRepository,
    private val passwordStore: BackupPasswordStore,
    private val mongoRepository: MongoBackupRepository = MongoBackupRepository(),
    moshi: Moshi = Moshi.Builder().build(),
) {
    val crypto = BackupCrypto(moshi)
    private val adapter = moshi.adapter(BackupData::class.java).indent("  ")

    suspend fun buildBackupData(): BackupData = withContext(Dispatchers.IO) {
        val (rooms, members, entries) = repository.exportSnapshot()
        val settings = settingsRepository.current()
        BackupData(
            exportedAt = System.currentTimeMillis(),
            rooms = rooms,
            members = members,
            entries = entries,
            settings = BackupSettings.from(settings),
        )
    }

    fun encode(data: BackupData): String = adapter.toJson(data)

    suspend fun toJson(): String = encode(buildBackupData())

    fun countRecords(data: BackupData): Int =
        data.rooms.size + data.members.size + data.entries.size + if (data.settings != null) 1 else 0

    suspend fun exportTo(uri: Uri, password: CharArray? = null): Int = withContext(Dispatchers.IO) {
        val data = buildBackupData()
        val json = encode(data)
        val output = crypto.seal(json, password)
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(output.toByteArray(Charsets.UTF_8))
        } ?: error("Couldn't open the selected file for writing")
        countRecords(data)
    }

    suspend fun exportToFile(file: File, password: CharArray? = null): Int = withContext(Dispatchers.IO) {
        val data = buildBackupData()
        val json = encode(data)
        val output = crypto.seal(json, password)
        file.parentFile?.mkdirs()
        file.writeText(output, Charsets.UTF_8)
        countRecords(data)
    }

    suspend fun exportToShareCache(password: CharArray? = null): Pair<File, Int> {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "SplitBill-backup.json")
        val count = exportToFile(file, password)
        return file to count
    }

    suspend fun importFrom(
        uri: Uri,
        passwordProvider: suspend () -> CharArray? = { null }
    ): BackupImportResult = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("Couldn't open the selected file for reading")
        importRaw(raw, passwordProvider)
    }

    suspend fun importFromJson(json: String): Int = importFromJsonInternal(json)

    /**
     * Pull latest cloud snapshot into local storage when the cloud copy is newer.
     * No-ops (success) when the vault is not baked into this build.
     * Does not push.
     */
    suspend fun pullLatestFromCloud(): Result<Unit> = runCatching {
        if (!MongoUriVault.isAvailable()) return@runCatching
        val settings = settingsRepository.current()
        val supportId = settings.supportId.ifBlank { settingsRepository.ensureSupportId() }
        val password = resolveCloudPassword(settings)
        pullLatestUnlocked(supportId, password)
    }

    /** Push current local state to cloud. No-ops when vault is unavailable. */
    suspend fun pushCloud(): Result<Int> = runCatching {
        if (!MongoUriVault.isAvailable()) return@runCatching 0
        val settings = settingsRepository.current()
        val supportId = settings.supportId.ifBlank { settingsRepository.ensureSupportId() }
        val password = resolveCloudPassword(settings)
        pushCloudUnlocked(supportId, password)
    }

    /**
     * Pull latest cloud snapshot (when newer), then push local state.
     * Payload is always gzip-compressed inside the sealed envelope (Support ID key).
     */
    suspend fun syncCloud(): Result<Int> = runCatching {
        if (!MongoUriVault.isAvailable()) error("Cloud backup is not available in this build")
        val settings = settingsRepository.current()
        val supportId = settings.supportId.ifBlank { settingsRepository.ensureSupportId() }
        val password = resolveCloudPassword(settings)
        pullLatestUnlocked(supportId, password)
        pushCloudUnlocked(supportId, password)
    }

    suspend fun uploadCloud(): Result<Int> = syncCloud()

    suspend fun downloadCloud(): BackupImportResult = runCatching {
        if (!MongoUriVault.isAvailable()) return BackupImportResult.Unrecognized
        val settings = settingsRepository.current()
        val supportId = settings.supportId.ifBlank { return BackupImportResult.Unrecognized }
        val doc = mongoRepository.tryDownloadDoc(
            baseUrl = MongoUriVault.baseUrl(),
            apiKey = MongoUriVault.resolve(),
            databaseName = settings.mongoDbName,
            collectionName = settings.mongoCollectionName,
            supportId = supportId,
        ) ?: return BackupImportResult.Unrecognized
        val password = resolveCloudPassword(settings)
        when (val opened = crypto.open(doc.payloadJson, password)) {
            is OpenResult.Success -> BackupImportResult.Success(importFromJsonInternal(opened.payloadJson))
            OpenResult.WrongPassword -> BackupImportResult.WrongPassword
            OpenResult.Corrupt -> BackupImportResult.Corrupt
            OpenResult.Unreadable -> BackupImportResult.Unrecognized
        }
    }.getOrElse {
        BackupImportResult.Unrecognized
    }

    /**
     * Startup sync gate: vault present, Support ID set, and last sync older than 12h (or never).
     * Cloud sync is always on — no user toggle.
     */
    suspend fun shouldAutoUploadNow(settings: AppSettings? = null): Boolean {
        val resolved = settings ?: settingsRepository.current()
        if (!MongoUriVault.isAvailable()) return false
        if (resolved.supportId.isBlank()) return false
        val last = resolved.lastCloudBackupAtEpochMs
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= AppSettings.CLOUD_BACKUP_DEBOUNCE_MS
    }

    suspend fun setCloudPassword(password: CharArray) {
        passwordStore.savePassword(password)
        settingsRepository.update { it.copy(cloudBackupPasswordSet = true) }
    }

    suspend fun clearCloudPassword() {
        passwordStore.clear()
        settingsRepository.update { it.copy(cloudBackupPasswordSet = false) }
    }

    private suspend fun importRaw(
        raw: String,
        passwordProvider: suspend () -> CharArray?
    ): BackupImportResult = when (crypto.classify(raw)) {
        BackupFormat.LEGACY_PLAIN -> BackupImportResult.Success(importFromJsonInternal(raw))
        BackupFormat.PLAIN_WRAPPED -> openThenImport(raw, null)
        BackupFormat.ENCRYPTED -> {
            val password = passwordProvider()
            if (password == null || password.isEmpty()) {
                BackupImportResult.PasswordRequired
            } else {
                try {
                    val result = openThenImport(raw, password)
                    if (result is BackupImportResult.Success) {
                        rememberCustomPasswordIfNeeded(password)
                    }
                    result
                } finally {
                    password.fill('\u0000')
                }
            }
        }
        BackupFormat.UNKNOWN -> BackupImportResult.Unrecognized
    }

    private suspend fun openThenImport(raw: String, password: CharArray?): BackupImportResult =
        when (val opened = crypto.open(raw, password)) {
            is OpenResult.Success -> BackupImportResult.Success(importFromJsonInternal(opened.payloadJson))
            OpenResult.WrongPassword -> BackupImportResult.WrongPassword
            OpenResult.Corrupt -> BackupImportResult.Corrupt
            OpenResult.Unreadable -> BackupImportResult.Unrecognized
        }

    private suspend fun rememberCustomPasswordIfNeeded(password: CharArray) {
        runCatching {
            val supportId = settingsRepository.settings.first().supportId
            val isSupportId = supportId.isNotBlank() && supportId.toCharArray().contentEquals(password)
            if (!isSupportId) {
                passwordStore.savePassword(password)
                settingsRepository.update { it.copy(cloudBackupPasswordSet = true) }
            }
        }
    }

    private suspend fun importFromJsonInternal(json: String): Int {
        val data = adapter.fromJson(json) ?: error(BackupErrorMessages.NOT_VALID_BACKUP)
        repository.replaceAllData(data.rooms, data.members, data.entries)
        data.settings?.let { backupSettings ->
            val current = settingsRepository.current()
            settingsRepository.update { backupSettings.toAppSettings(current) }
        }
        return countRecords(data)
    }


    private suspend fun pullLatestUnlocked(supportId: String, password: CharArray) {
        val settings = settingsRepository.current()
        val remote = mongoRepository.tryDownloadDoc(
            baseUrl = MongoUriVault.baseUrl(),
            apiKey = MongoUriVault.resolve(),
            databaseName = settings.mongoDbName,
            collectionName = settings.mongoCollectionName,
            supportId = supportId,
        ) ?: return

        when (val opened = crypto.open(remote.payloadJson, password)) {
            is OpenResult.Success -> {
                val cloudData = adapter.fromJson(opened.payloadJson)
                val cloudExportedAt = maxOf(remote.exportedAt, cloudData?.exportedAt ?: 0L)
                val localFreshness = localDataFreshnessMs()
                if (cloudData != null && cloudExportedAt >= localFreshness) {
                    importFromJsonInternal(opened.payloadJson)
                }
            }
            OpenResult.WrongPassword, OpenResult.Corrupt, OpenResult.Unreadable -> {
                error("Could not open cloud backup — check Support ID / password")
            }
        }
    }

    private suspend fun pushCloudUnlocked(supportId: String, password: CharArray): Int {
        val settings = settingsRepository.current()
        // Seal always gzip-compresses when a password is present (Support ID).
        val payloadJson = crypto.seal(toJson(), password)
        val data = buildBackupData()
        mongoRepository.upload(
            baseUrl = MongoUriVault.baseUrl(),
            apiKey = MongoUriVault.resolve(),
            databaseName = settings.mongoDbName,
            collectionName = settings.mongoCollectionName,
            supportId = supportId,
            payloadJson = payloadJson,
            exportedAt = data.exportedAt,
            deviceName = DeviceIdentity.deviceName(context),
            macId = DeviceIdentity.macId(context),
        )
        settingsRepository.update {
            it.copy(
                lastCloudBackupAtEpochMs = System.currentTimeMillis(),
                cloudBackupEnabled = true,
                cloudAutoUploadEnabled = true,
                mongoCollectionName = it.mongoCollectionName.ifBlank { AppSettings.DEFAULT_MONGO_COLLECTION },
            )
        }
        return countRecords(data)
    }

    private suspend fun localDataFreshnessMs(): Long {
        val (rooms, members, entries) = repository.exportSnapshot()
        val roomMax = rooms.maxOfOrNull { it.createdAtEpochMs } ?: 0L
        val memberMax = members.maxOfOrNull { it.createdAtEpochMs } ?: 0L
        val entryMax = entries.maxOfOrNull { it.timestampEpochMs } ?: 0L
        return maxOf(roomMax, memberMax, entryMax)
    }

    private suspend fun resolveCloudPassword(settings: AppSettings): CharArray {
        passwordStore.loadPassword()?.let { return it }
        val support = settings.supportId.ifBlank { settingsRepository.ensureSupportId() }
        return support.toCharArray()
    }
}
