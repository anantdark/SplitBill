package com.anant.splitbill.data.backup.crypto

import android.util.Base64
import com.anant.splitbill.data.backup.BackupErrorMessages
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCrypto(private val moshi: Moshi) {

    private val envelopeAdapter by lazy { moshi.adapter(BackupEnvelope::class.java) }
    private val secureRandom by lazy { SecureRandom() }

    fun classify(raw: String): BackupFormat {
        val obj = parseJsonObject(raw) ?: return BackupFormat.UNKNOWN
        if (obj.has(MARKER_FIELD)) {
            val marker = obj.optInt(MARKER_FIELD, 0)
            if (marker < 1) return BackupFormat.UNKNOWN
            return when (obj.optString(ENC_FIELD)) {
                ENC_AES_GCM -> BackupFormat.ENCRYPTED
                ENC_NONE -> BackupFormat.PLAIN_WRAPPED
                else -> BackupFormat.UNKNOWN
            }
        }
        if (obj.has(LEGACY_VERSION_FIELD)) return BackupFormat.LEGACY_PLAIN
        return BackupFormat.UNKNOWN
    }

    /**
     * Gzip-compress [payloadJson] into a plain (`enc=none`) envelope.
     * Used for cloud sync — no encryption.
     */
    suspend fun sealCompressed(payloadJson: String): String = withContext(Dispatchers.Default) {
        val compressed = gzip(payloadJson.toByteArray(Charsets.UTF_8))
        val envelope = BackupEnvelope(
            splitbillBackup = ENVELOPE_VERSION,
            enc = ENC_NONE,
            compression = COMPRESSION_GZIP,
            ciphertext = encode(compressed),
        )
        envelopeAdapter.toJson(envelope)
    }

    /** Optional AES-GCM seal for local file export when the user sets a password. */
    suspend fun seal(payloadJson: String, password: CharArray?): String = withContext(Dispatchers.Default) {
        if (isNullOrBlank(password)) return@withContext sealCompressed(payloadJson)

        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }
        val passwordCopy = password!!.copyOf()
        var keyBytes: ByteArray? = null
        try {
            keyBytes = try {
                deriveKey(passwordCopy, salt, DEFAULT_ITERATIONS)
            } catch (e: Exception) {
                throw IllegalStateException(BackupErrorMessages.KEY_DERIVATION_FAILED, e)
            }
            val plainBytes = gzip(payloadJson.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, KEY_ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            val ciphertext = cipher.doFinal(plainBytes)
            val envelope = BackupEnvelope(
                splitbillBackup = ENVELOPE_VERSION,
                enc = ENC_AES_GCM,
                kdf = KDF_ID,
                iterations = DEFAULT_ITERATIONS,
                salt = encode(salt),
                iv = encode(iv),
                compression = COMPRESSION_GZIP,
                ciphertext = encode(ciphertext)
            )
            envelopeAdapter.toJson(envelope)
        } finally {
            passwordCopy.fill('\u0000')
            keyBytes?.fill(0)
        }
    }

    suspend fun open(raw: String, password: CharArray?): OpenResult = withContext(Dispatchers.Default) {
        when (classify(raw)) {
            BackupFormat.LEGACY_PLAIN -> OpenResult.Success(raw)
            BackupFormat.PLAIN_WRAPPED -> openPlainWrapped(raw)
            BackupFormat.ENCRYPTED -> openEncrypted(raw, password)
            BackupFormat.UNKNOWN -> OpenResult.Unreadable
        }
    }

    private fun openPlainWrapped(raw: String): OpenResult {
        val envelope = parseEnvelope(raw) ?: return OpenResult.Corrupt
        return try {
            val bytes = decode(envelope.ciphertext)
            OpenResult.Success(decodePayloadBytes(bytes, envelope.compression))
        } catch (_: Exception) {
            OpenResult.Corrupt
        }
    }

    private fun openEncrypted(raw: String, password: CharArray?): OpenResult {
        if (isNullOrBlank(password)) return OpenResult.WrongPassword
        val envelope = parseEnvelope(raw) ?: return OpenResult.Corrupt
        val salt = envelope.salt
        val iv = envelope.iv
        val iterations = envelope.iterations
        if (salt == null || iv == null || iterations == null || iterations < 1) return OpenResult.Corrupt

        val saltBytes: ByteArray
        val ivBytes: ByteArray
        val cipherBytes: ByteArray
        try {
            saltBytes = decode(salt)
            ivBytes = decode(iv)
            cipherBytes = decode(envelope.ciphertext)
        } catch (_: IllegalArgumentException) {
            return OpenResult.Corrupt
        }
        if (ivBytes.size != IV_BYTES || saltBytes.isEmpty() || cipherBytes.isEmpty()) return OpenResult.Corrupt

        val passwordCopy = password!!.copyOf()
        var keyBytes: ByteArray? = null
        return try {
            keyBytes = deriveKey(passwordCopy, saltBytes, iterations)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, KEY_ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, ivBytes)
            )
            val plaintext = cipher.doFinal(cipherBytes)
            OpenResult.Success(decodePayloadBytes(plaintext, envelope.compression))
        } catch (_: AEADBadTagException) {
            OpenResult.WrongPassword
        } catch (_: Exception) {
            OpenResult.WrongPassword
        } finally {
            passwordCopy.fill('\u0000')
            keyBytes?.fill(0)
        }
    }

    private fun decodePayloadBytes(bytes: ByteArray, compression: String?): String {
        val utf8Bytes = when (compression?.trim()?.lowercase()) {
            null, "" -> bytes
            COMPRESSION_GZIP -> gunzip(bytes)
            else -> error("Unsupported backup compression: $compression")
        }
        return String(utf8Bytes, Charsets.UTF_8)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size.coerceAtLeast(64))
        GZIPOutputStream(out).use { it.write(input) }
        return out.toByteArray()
    }

    private fun gunzip(input: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun parseEnvelope(raw: String): BackupEnvelope? = try {
        envelopeAdapter.fromJson(raw)
    } catch (_: Exception) {
        null
    }

    private fun parseJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trimStart()
        if (trimmed.isEmpty() || trimmed[0] != '{') return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private fun isNullOrBlank(password: CharArray?): Boolean =
        password == null || password.isEmpty() || password.all { it.isWhitespace() }

    companion object {
        const val DEFAULT_ITERATIONS = 120_000
        const val COMPRESSION_GZIP = "gzip"
        private const val ENVELOPE_VERSION = 1
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KDF_ID = "PBKDF2-HMAC-SHA256"
        private const val ENC_AES_GCM = "AES-GCM"
        private const val ENC_NONE = "none"
        private const val MARKER_FIELD = "splitbillBackup"
        private const val ENC_FIELD = "enc"
        private const val LEGACY_VERSION_FIELD = "version"
    }
}
