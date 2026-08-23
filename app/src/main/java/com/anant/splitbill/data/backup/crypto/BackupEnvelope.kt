package com.anant.splitbill.data.backup.crypto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BackupEnvelope(
    val splitbillBackup: Int,
    val enc: String,
    val kdf: String? = null,
    val iterations: Int? = null,
    val salt: String? = null,
    val iv: String? = null,
    val compression: String? = null,
    val ciphertext: String
)

enum class BackupFormat {
    ENCRYPTED,
    PLAIN_WRAPPED,
    LEGACY_PLAIN,
    UNKNOWN
}

sealed interface OpenResult {
    data class Success(val payloadJson: String) : OpenResult
    data object WrongPassword : OpenResult
    data object Corrupt : OpenResult
    data object Unreadable : OpenResult
}
