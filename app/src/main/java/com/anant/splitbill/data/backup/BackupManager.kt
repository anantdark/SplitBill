package com.anant.splitbill.data.backup

import android.content.Context
import android.net.Uri
import com.anant.splitbill.data.backup.crypto.BackupCrypto
import com.anant.splitbill.data.backup.crypto.BackupFormat
import com.anant.splitbill.data.backup.crypto.BackupPasswordStore
import com.anant.splitbill.data.backup.crypto.OpenResult
import com.anant.splitbill.data.backup.mongo.MongoBackupRepository
import android.util.Log
import com.anant.splitbill.data.backup.mongo.MongoUriVault
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.DeletionRules
import com.anant.splitbill.data.model.EntryType
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
        val deviceId = DeviceIdentity.macId(context)
        val deviceName = DeviceIdentity.deviceName(context)
        val selfMember = members.firstOrNull { it.id == settings.defaultMemberId }
        val devices = RoomSyncMeta.upsertDevice(
            existing = RoomSyncMeta.decodeDevices(settings.roomDevicesJson),
            deviceId = deviceId,
            deviceName = deviceName,
            memberId = selfMember?.id,
            memberName = selfMember?.name,
        )
        BackupData(
            exportedAt = System.currentTimeMillis(),
            rooms = rooms,
            members = members,
            entries = entries,
            settings = BackupSettings.from(settings),
            devices = devices,
            auditLog = RoomSyncMeta.decodeAudit(settings.auditLogJson),
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
        settingsRepository.recordSuccessfulBackup()
        countRecords(data)
    }

    suspend fun exportToFile(file: File, password: CharArray? = null): Int = withContext(Dispatchers.IO) {
        val data = buildBackupData()
        val json = encode(data)
        val output = crypto.seal(json, password)
        file.parentFile?.mkdirs()
        file.writeText(output, Charsets.UTF_8)
        settingsRepository.recordSuccessfulBackup()
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
    suspend fun pullLatestFromCloud(): Result<SyncCloudResult> = runCatching {
        if (!MongoUriVault.isAvailable()) error("Cloud backup is not available in this build")
        val settings = settingsRepository.current()
        val supportId = settings.supportId.ifBlank { settingsRepository.ensureSupportId() }
        val pull = pullLatestUnlocked(supportId)
        SyncCloudResult(
            recordCount = countRecords(buildBackupData()),
            newEntries = pull.newEntries,
            newlyDeletedEntries = pull.newlyDeleted,
        )
    }

    /**
     * Push current local state to cloud. No-ops when vault is unavailable, or when
     * Developer settings is unlocked — dev mode is pull-only, so real device data
     * (yours or the room's) never gets overwritten by whatever a developer is
     * poking at locally.
     */
    suspend fun pushCloud(): Result<Int> = runCatching {
        if (!MongoUriVault.isAvailable()) return@runCatching 0
        val settings = settingsRepository.current()
        if (settings.developerModeUnlocked) return@runCatching 0
        val supportId = settings.supportId.ifBlank { settingsRepository.ensureSupportId() }
        pushCloudUnlocked(supportId)
    }

    /**
     * Pull latest cloud snapshot (when newer), then push local state — unless
     * Developer settings is unlocked, in which case this only pulls.
     * Cloud payloads are gzip-compressed only (not encrypted).
     * [SyncCloudResult.newEntries] lists entries that arrived from the cloud on this pull.
     */
    suspend fun syncCloud(): Result<SyncCloudResult> = runCatching {
        if (!MongoUriVault.isAvailable()) error("Cloud backup is not available in this build")
        val settings = settingsRepository.current()
        val supportId = settings.supportId.ifBlank { settingsRepository.ensureSupportId() }
        val pull = pullLatestUnlocked(supportId)
        val count = if (settings.developerModeUnlocked) {
            countRecords(buildBackupData())
        } else {
            pushCloudUnlocked(supportId)
        }
        SyncCloudResult(
            recordCount = count,
            newEntries = pull.newEntries,
            newlyDeletedEntries = pull.newlyDeleted,
        )
    }

    suspend fun uploadCloud(): Result<SyncCloudResult> = syncCloud()

    /** Records that this device has already shown delete alerts for these entry IDs. */
    suspend fun markDeletionsNotified(entryIds: Collection<String>) {
        if (entryIds.isEmpty()) return
        settingsRepository.update {
            it.copy(notifiedDeletionIds = it.notifiedDeletionIds + entryIds)
        }
    }

    /**
     * Soft-deletes a recharge group, appends audit, marks local notifications as seen,
     * and pushes to cloud so other devices learn about the deletion.
     */
    suspend fun softDeleteRechargeAndSync(roomId: String, groupId: String): Result<List<EntryEntity>> =
        runCatching {
            val settings = settingsRepository.current()
            val deviceId = DeviceIdentity.macId(context)
            val deviceName = DeviceIdentity.deviceName(context)
            val members = repository.getMembers(roomId)
            val self = members.firstOrNull { it.id == settings.defaultMemberId }
            val deleted = repository.softDeleteRechargeGroup(
                roomId = roomId,
                groupId = groupId,
                deletedByMemberId = self?.id,
                deletedByMemberName = self?.name,
                deletedByDeviceId = deviceId,
                deletedByDeviceName = deviceName,
            )
            val recharge = deleted.firstOrNull { it.type == com.anant.splitbill.data.model.EntryType.RECHARGE }
            val audit = RoomSyncMeta.appendAudit(
                existing = RoomSyncMeta.decodeAudit(settings.auditLogJson),
                action = "delete_recharge",
                deviceId = deviceId,
                deviceName = deviceName,
                memberId = self?.id,
                memberName = self?.name,
                entryId = recharge?.id,
                groupId = groupId,
                detail = recharge?.let {
                    "${it.memberName} ${it.value}"
                }.orEmpty(),
            )
            val devices = RoomSyncMeta.upsertDevice(
                existing = RoomSyncMeta.decodeDevices(settings.roomDevicesJson),
                deviceId = deviceId,
                deviceName = deviceName,
                memberId = self?.id,
                memberName = self?.name,
            )
            settingsRepository.update {
                it.copy(
                    auditLogJson = RoomSyncMeta.encodeAudit(audit),
                    roomDevicesJson = RoomSyncMeta.encodeDevices(devices),
                    notifiedDeletionIds = it.notifiedDeletionIds + deleted.map { e -> e.id },
                )
            }
            if (MongoUriVault.isAvailable()) {
                pushCloud()
            }
            deleted
        }

    /**
     * Join a household by Room ID: download that cloud doc, import it, and force
     * local Room ID / Support ID / activeRoomId to match.
     * Throws with a clear message when the room is missing or unreadable.
     */
    suspend fun joinFromCloud(roomId: String): BackupImportResult {
        if (!MongoUriVault.isAvailable()) {
            error("Cloud sync unavailable in this build")
        }
        val id = roomId.trim()
        require(id.isNotBlank()) { "Enter a Room ID" }
        val settings = settingsRepository.current()
        val doc = mongoRepository.tryDownloadDoc(
            baseUrl = MongoUriVault.baseUrl(),
            apiKey = MongoUriVault.resolve(),
            databaseName = effectiveMongoDbName(settings),
            collectionName = effectiveMongoCollection(settings),
            supportId = id,
        ) ?: error("No cloud room found for that Room ID")
        return when (val opened = openCloudPayload(doc.payloadJson)) {
            is OpenResult.Success -> {
                val count = importFromJsonInternal(opened.payloadJson, adoptRoomId = id)
                BackupImportResult.Success(count)
            }
            OpenResult.WrongPassword -> BackupImportResult.WrongPassword
            OpenResult.Corrupt -> BackupImportResult.Corrupt
            OpenResult.Unreadable -> error("Could not open cloud backup for that Room ID")
        }
    }

    suspend fun downloadCloud(): BackupImportResult {
        if (!MongoUriVault.isAvailable()) return BackupImportResult.Unrecognized
        return try {
            val supportId = settingsRepository.current().supportId.ifBlank {
                settingsRepository.ensureSupportId()
            }
            val settings = settingsRepository.current()
            Log.d(TAG, "downloadCloud: supportId=$supportId")
            val doc = mongoRepository.tryDownloadDoc(
                baseUrl = MongoUriVault.baseUrl(),
                apiKey = MongoUriVault.resolve(),
                databaseName = effectiveMongoDbName(settings),
                collectionName = effectiveMongoCollection(settings),
                supportId = supportId,
            ) ?: run {
                Log.w(TAG, "downloadCloud: no cloud backup for supportId=$supportId")
                return BackupImportResult.NotFound
            }
            when (val opened = openCloudPayload(doc.payloadJson)) {
                is OpenResult.Success -> BackupImportResult.Success(
                    importFromJsonInternal(opened.payloadJson, adoptRoomId = supportId)
                )
                OpenResult.WrongPassword -> BackupImportResult.WrongPassword
                OpenResult.Corrupt -> BackupImportResult.Corrupt
                OpenResult.Unreadable -> BackupImportResult.Unrecognized
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadCloud failed", e)
            BackupImportResult.Failed(
                BackupErrorMessages.cloudRestoreFailed(e.message)
            )
        }
    }

    /**
     * Startup / periodic sync gate: vault present, Support ID set, and last sync
     * older than [AppSettings.CLOUD_BACKUP_DEBOUNCE_MS] (or never).
     */
    suspend fun shouldAutoUploadNow(settings: AppSettings? = null): Boolean {
        val resolved = settings ?: settingsRepository.current()
        if (!resolved.cloudBackupEnabled || !resolved.cloudAutoUploadEnabled) return false
        if (!MongoUriVault.isAvailable()) return false
        if (resolved.supportId.isBlank()) return false
        if (!resolved.onboardingComplete) return false
        val last = resolved.lastCloudBackupAtEpochMs
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= AppSettings.CLOUD_BACKUP_DEBOUNCE_MS
    }

    /** True when a process-start sync should run (short coalesce only). */
    suspend fun shouldSyncOnAppStart(settings: AppSettings? = null): Boolean {
        val resolved = settings ?: settingsRepository.current()
        if (!resolved.cloudBackupEnabled || !resolved.cloudAutoUploadEnabled) return false
        if (!MongoUriVault.isAvailable()) return false
        if (resolved.supportId.isBlank()) return false
        if (!resolved.onboardingComplete) return false
        val last = resolved.lastCloudBackupAtEpochMs
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= AppSettings.STARTUP_SYNC_MIN_INTERVAL_MS
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

    private suspend fun importFromJsonInternal(
        json: String,
        adoptRoomId: String? = null,
    ): Int {
        val data = adapter.fromJson(json) ?: error(BackupErrorMessages.NOT_VALID_BACKUP)
        repository.replaceAllData(data.rooms, data.members, data.entries)

        val canonicalId = adoptRoomId?.trim()?.takeIf { it.isNotBlank() }
            ?: data.settings?.supportId?.trim()?.takeIf { it.isNotBlank() }
            ?: settingsRepository.current().supportId.trim().takeIf { it.isNotBlank() }

        if (canonicalId != null) {
            repository.alignPrimaryRoomId(canonicalId)
        }

        val current = settingsRepository.current()
        val fromBackup = data.settings?.toAppSettings(current)
        val mergedDevices = RoomSyncMeta.mergeDevices(
            RoomSyncMeta.decodeDevices(current.roomDevicesJson),
            data.devices,
        )
        val mergedAudit = RoomSyncMeta.mergeAudit(
            RoomSyncMeta.decodeAudit(current.auditLogJson),
            data.auditLog,
        )
        val merged = (fromBackup ?: current).copy(
            supportId = canonicalId ?: current.supportId,
            activeRoomId = canonicalId ?: fromBackup?.activeRoomId ?: current.activeRoomId,
            onboardingComplete = if (canonicalId != null) {
                true
            } else {
                fromBackup?.onboardingComplete ?: current.onboardingComplete
            },
            // Per-device identity — never take another phone's "you are" from the cloud.
            defaultMemberId = current.defaultMemberId,
            notifiedDeletionIds = current.notifiedDeletionIds,
            roomDevicesJson = RoomSyncMeta.encodeDevices(mergedDevices),
            auditLogJson = RoomSyncMeta.encodeAudit(mergedAudit),
            cloudBackupEnabled = true,
            cloudAutoUploadEnabled = true,
            mongoDbName = effectiveMongoDbName(fromBackup ?: current),
            mongoCollectionName = effectiveMongoCollection(fromBackup ?: current),
        )
        settingsRepository.update { merged }
        return countRecords(data)
    }

    private data class PullDiff(
        val newEntries: List<EntryEntity> = emptyList(),
        val newlyDeleted: List<EntryEntity> = emptyList(),
    )

    /** Diffes cloud vs local, then imports when cloud is newer. */
    private suspend fun pullLatestUnlocked(supportId: String): PullDiff {
        val settings = settingsRepository.current()
        val remote = mongoRepository.tryDownloadDoc(
            baseUrl = MongoUriVault.baseUrl(),
            apiKey = MongoUriVault.resolve(),
            databaseName = effectiveMongoDbName(settings),
            collectionName = effectiveMongoCollection(settings),
            supportId = supportId,
        ) ?: return PullDiff()

        return when (val opened = openCloudPayload(remote.payloadJson)) {
            is OpenResult.Success -> {
                val cloudData = adapter.fromJson(opened.payloadJson)
                val cloudExportedAt = maxOf(remote.exportedAt, cloudData?.exportedAt ?: 0L)
                val localFreshness = localDataFreshnessMs()
                if (cloudData == null || cloudExportedAt < localFreshness) {
                    return PullDiff()
                }
                val before = repository.exportSnapshot().third
                val beforeById = before.associateBy { it.id }
                val beforeIds = beforeById.keys
                val localDeviceId = DeviceIdentity.macId(context)
                importFromJsonInternal(opened.payloadJson, adoptRoomId = supportId)
                val after = repository.exportSnapshot().third
                val newEntries = after.filter { it.id !in beforeIds && !it.deleted }
                val newlyDeleted = after.filter { entry ->
                    // A soft-deleted group is READING rows + one RECHARGE row — that's
                    // one deletion event, not N. Only the RECHARGE row represents it.
                    if (entry.type != EntryType.RECHARGE) return@filter false
                    if (!entry.deleted) return@filter false
                    val prior = beforeById[entry.id]
                    val becameDeleted = prior == null || !prior.deleted
                    if (!becameDeleted) return@filter false
                    // Don't alert this device about its own delete, a repeat, or a quiet
                    // same-person self-correction made within minutes of creation.
                    entry.deletedByDeviceId != localDeviceId &&
                        entry.id !in settings.notifiedDeletionIds &&
                        !DeletionRules.isQuietSelfDelete(entry)
                }
                PullDiff(newEntries = newEntries, newlyDeleted = newlyDeleted)
            }
            OpenResult.WrongPassword, OpenResult.Corrupt, OpenResult.Unreadable -> {
                error("Could not open cloud backup")
            }
        }
    }

    private suspend fun pushCloudUnlocked(supportId: String): Int {
        repository.alignPrimaryRoomId(supportId)
        val deviceId = DeviceIdentity.macId(context)
        val deviceName = DeviceIdentity.deviceName(context)
        val members = repository.getMembers(supportId)
        settingsRepository.update {
            val self = members.firstOrNull { m -> m.id == it.defaultMemberId }
            val devices = RoomSyncMeta.upsertDevice(
                existing = RoomSyncMeta.decodeDevices(it.roomDevicesJson),
                deviceId = deviceId,
                deviceName = deviceName,
                memberId = self?.id,
                memberName = self?.name,
            )
            it.copy(
                supportId = supportId,
                activeRoomId = supportId,
                mongoDbName = effectiveMongoDbName(it),
                mongoCollectionName = effectiveMongoCollection(it),
                roomDevicesJson = RoomSyncMeta.encodeDevices(devices),
            )
        }
        val settings = settingsRepository.current()
        val data = buildBackupData()
        val payloadJson = crypto.sealCompressed(encode(data))
        val lastAudit = data.auditLog.lastOrNull()
        mongoRepository.upload(
            baseUrl = MongoUriVault.baseUrl(),
            apiKey = MongoUriVault.resolve(),
            databaseName = effectiveMongoDbName(settings),
            collectionName = effectiveMongoCollection(settings),
            supportId = supportId,
            payloadJson = payloadJson,
            exportedAt = data.exportedAt,
            deviceName = deviceName,
            macId = deviceId,
            deviceCount = data.devices.size,
            devicesJson = RoomSyncMeta.encodeDevices(data.devices),
            auditLogJson = RoomSyncMeta.encodeAudit(data.auditLog),
            lastAction = lastAudit?.action.orEmpty(),
            lastActionByMember = lastAudit?.memberName.orEmpty(),
        )
        settingsRepository.update {
            it.copy(
                lastCloudBackupAtEpochMs = System.currentTimeMillis(),
                lastSuccessfulBackupAt = System.currentTimeMillis(),
                cloudBackupEnabled = true,
                cloudAutoUploadEnabled = true,
                mongoDbName = effectiveMongoDbName(it),
                mongoCollectionName = effectiveMongoCollection(it),
                roomDevicesJson = RoomSyncMeta.encodeDevices(data.devices),
                auditLogJson = RoomSyncMeta.encodeAudit(data.auditLog),
            )
        }
        return countRecords(data)
    }

    /**
     * Opens a cloud payload. New backups are gzip-only (`enc=none`).
     * Legacy AES-GCM cloud docs still open with the Support ID if present.
     */
    private suspend fun openCloudPayload(raw: String): OpenResult {
        when (val plain = crypto.open(raw, null)) {
            is OpenResult.Success -> return plain
            OpenResult.WrongPassword -> {
                val password = resolveCloudPassword(settingsRepository.current())
                try {
                    return crypto.open(raw, password)
                } finally {
                    password.fill('\u0000')
                }
            }
            else -> return plain
        }
    }

    private suspend fun localDataFreshnessMs(): Long {
        val (rooms, members, entries) = repository.exportSnapshot()
        val roomMax = rooms.maxOfOrNull { it.createdAtEpochMs } ?: 0L
        val memberMax = members.maxOfOrNull { it.createdAtEpochMs } ?: 0L
        val entryMax = entries.maxOfOrNull {
            maxOf(it.timestampEpochMs, it.deletedAtEpochMs ?: 0L)
        } ?: 0L
        return maxOf(roomMax, memberMax, entryMax)
    }

    private suspend fun resolveCloudPassword(settings: AppSettings): CharArray {
        passwordStore.loadPassword()?.let { return it }
        val support = settings.supportId.ifBlank { settingsRepository.ensureSupportId() }
        return support.toCharArray()
    }

    private fun effectiveMongoDbName(settings: AppSettings): String {
        val raw = settings.mongoDbName.trim()
        if (raw.isEmpty() || raw in LEGACY_MONGO_DB_NAMES) {
            return AppSettings.DEFAULT_MONGO_DB_NAME
        }
        return raw
    }

    private fun effectiveMongoCollection(settings: AppSettings): String {
        val raw = settings.mongoCollectionName.trim()
        if (raw.isEmpty() || raw in LEGACY_MONGO_COLLECTIONS) {
            return AppSettings.DEFAULT_MONGO_COLLECTION
        }
        return raw
    }

    companion object {
        private const val TAG = "BackupManager"
        private val LEGACY_MONGO_DB_NAMES = setOf("splitbill")
        private val LEGACY_MONGO_COLLECTIONS = setOf(
            "splitbill_backup",
            "splitbill",
        )
    }
}
