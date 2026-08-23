package com.anant.splitbill.data.backup.mongo

import com.anant.splitbill.BuildConfig
import java.util.Base64

/**
 * Client-side vault for build-baked proxy API key and XOR-decoded secrets (Sentry DSN, backup key).
 */
object MongoUriVault {

    @Volatile
    private var cachedApiKey: String? = null

    fun isAvailable(): Boolean = BuildConfig.BACKUP_API_KEY_BLOB.isNotBlank()

    fun databaseName(): String =
        BuildConfig.MONGO_DB_NAME.trim().ifBlank { "fitbuddy" }

    fun baseUrl(): String =
        BuildConfig.CLOUD_BACKUP_BASE_URL.trim().trimEnd('/')

    fun resolve(): String {
        cachedApiKey?.let { return it }
        val blob = BuildConfig.BACKUP_API_KEY_BLOB
        require(blob.isNotBlank()) { "Cloud backup is not available in this build" }
        val decoded = decode(blob, BuildConfig.BACKUP_API_KEY_MASK)
        require(decoded.isNotBlank()) { "Cloud backup API key could not be decoded" }
        cachedApiKey = decoded
        return decoded
    }

    internal fun decode(base64Blob: String, maskSeed: String): String {
        val masked = Base64.getDecoder().decode(base64Blob)
        val mask = maskSeed.toByteArray(Charsets.UTF_8)
        if (mask.isEmpty()) return String(masked, Charsets.UTF_8)
        val plain = ByteArray(masked.size) { i ->
            (masked[i].toInt() xor mask[i % mask.size].toInt()).toByte()
        }
        return String(plain, Charsets.UTF_8)
    }

    internal fun encode(plain: String, maskSeed: String): String {
        val mask = maskSeed.toByteArray(Charsets.UTF_8)
        val plainBytes = plain.toByteArray(Charsets.UTF_8)
        val out = ByteArray(plainBytes.size) { i ->
            (plainBytes[i].toInt() xor mask[i % mask.size].toInt()).toByte()
        }
        return Base64.getEncoder().encodeToString(out)
    }
}
