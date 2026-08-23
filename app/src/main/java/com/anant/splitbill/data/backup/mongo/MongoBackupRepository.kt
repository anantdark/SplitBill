package com.anant.splitbill.data.backup.mongo

import com.anant.splitbill.BuildConfig
import com.anant.splitbill.data.backup.BackupData
import com.anant.splitbill.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTPS client for the FitBuddy cloud-backup proxy:
 *   GET/PUT /api/backup/{supportId}?db=&collection=&chainSupport=1&maxSchemaVersion=
 * Documents are keyed by Support ID; [payloadJson] is client-sealed (gzip + AES-GCM).
 */
data class CloudBackupDoc(
    val supportId: String,
    val chunkId: String,
    val payloadJson: String,
    val exportedAt: Long,
)

open class MongoBackupRepository(
    private val http: OkHttpClient = defaultClient()
) {
    open suspend fun upload(
        baseUrl: String,
        apiKey: String,
        databaseName: String,
        collectionName: String,
        supportId: String,
        payloadJson: String,
        exportedAt: Long,
        deviceName: String,
        macId: String
    ) = withContext(Dispatchers.IO) {
        val id = supportId.trim()
        require(id.isNotBlank()) { "Support ID is blank — cannot upload backup" }
        val dbName = databaseName.trim().ifBlank { AppSettings.DEFAULT_MONGO_DB_NAME }
        val collName = collectionName.trim().ifBlank { AppSettings.DEFAULT_MONGO_COLLECTION }

        val body = JSONObject()
            .put("payloadJson", payloadJson)
            .put("schemaVersion", BackupData.CURRENT_VERSION)
            .put("exportedAt", exportedAt)
            .put("appPackage", BuildConfig.APPLICATION_ID)
            .put("deviceName", deviceName.trim().take(128))
            .put("macId", macId.trim().take(64))
            .put("chunkId", id)
            .put("chunkIndex", 0)
            .put("storageVersion", 2)
            .put("nextChunkId", JSONObject.NULL)
            .put("tipChunkId", id)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val url = backupUrl(baseUrl, id, dbName, collName)
            .newBuilder()
            .addQueryParameter("chainSupport", "1")
            .addQueryParameter("chunkId", id)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .put(body)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error(errorMessage(response.body?.string(), response.code))
            }
        }
    }

    /** Returns null when no backup exists for this Support ID (HTTP 404). */
    open suspend fun tryDownloadDoc(
        baseUrl: String,
        apiKey: String,
        databaseName: String,
        collectionName: String,
        supportId: String
    ): CloudBackupDoc? = withContext(Dispatchers.IO) {
        val id = supportId.trim()
        require(id.isNotBlank()) { "Support ID is required to restore" }
        val dbName = databaseName.trim().ifBlank { AppSettings.DEFAULT_MONGO_DB_NAME }
        val collName = collectionName.trim().ifBlank { AppSettings.DEFAULT_MONGO_COLLECTION }

        val url = backupUrl(baseUrl, id, dbName, collName)
            .newBuilder()
            .addQueryParameter("maxSchemaVersion", BackupData.CURRENT_VERSION.toString())
            .addQueryParameter("chainSupport", "1")
            .addQueryParameter("chunkId", id)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) {
                error(errorMessage(bodyString, response.code))
            }
            val json = JSONObject(bodyString)
            val payload = json.optString("payloadJson").takeIf { it.isNotBlank() }
                ?: error("Cloud backup is missing payloadJson")
            CloudBackupDoc(
                supportId = json.optString("supportId").ifBlank { id },
                chunkId = json.optString("chunkId").ifBlank { id },
                payloadJson = payload,
                exportedAt = json.optLong("exportedAt", 0L),
            )
        }
    }

    open suspend fun downloadPayloadJson(
        baseUrl: String,
        apiKey: String,
        databaseName: String,
        collectionName: String,
        supportId: String
    ): String = tryDownloadDoc(
        baseUrl = baseUrl,
        apiKey = apiKey,
        databaseName = databaseName,
        collectionName = collectionName,
        supportId = supportId,
    )?.payloadJson ?: error("No cloud backup found for Support ID $supportId")

    private fun backupUrl(
        baseUrl: String,
        supportId: String,
        databaseName: String,
        collectionName: String
    ): HttpUrl = "${baseUrl.trimEnd('/')}/api/backup/$supportId".toHttpUrlOrNull()
        ?.newBuilder()
        ?.addQueryParameter("db", databaseName)
        ?.addQueryParameter("collection", collectionName)
        ?.build()
        ?: error("Invalid cloud backup URL: $baseUrl")

    private fun errorMessage(rawBody: String?, code: Int): String {
        val fallback = "Cloud backup request failed (HTTP $code)"
        val body = rawBody?.takeIf { it.isNotBlank() } ?: return fallback
        return runCatching { JSONObject(body).optString("error") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallback
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
