package com.anant.splitbill.data.backup

object BackupErrorMessages {
    const val KEY_DERIVATION_FAILED = "Backup could not be encrypted: key derivation failed"
    const val INCORRECT_PASSWORD = "Incorrect password"
    const val BACKUP_CORRUPT = "Backup is corrupt or damaged"
    const val NOT_VALID_BACKUP = "Not a valid SplitBill backup"
    const val IMPORT_TOO_MANY_ATTEMPTS = "Import failed: too many incorrect password attempts"

    fun cloudRestoreFailed(reason: String?): String =
        "Cloud restore failed" + if (!reason.isNullOrBlank()) ": $reason" else ""
}
