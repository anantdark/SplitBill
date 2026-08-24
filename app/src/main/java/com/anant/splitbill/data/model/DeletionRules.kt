package com.anant.splitbill.data.model

import com.anant.splitbill.data.database.EntryEntity

/**
 * Shared logic for telling a quiet self-correction (same person undoing their
 * own recharge within minutes) apart from a notable deletion worth flagging —
 * used by both the cloud-sync notifier and the History UI so they agree.
 */
object DeletionRules {
    const val QUIET_SELF_DELETE_WINDOW_MS = 5L * 60L * 1000L

    /** True when the same person who logged this recharge deleted it within the quiet window. */
    fun isQuietSelfDelete(entry: EntryEntity): Boolean {
        if (!entry.deleted || entry.type != EntryType.RECHARGE) return false
        val deletedAt = entry.deletedAtEpochMs ?: return false
        val creatorId = entry.loggedByMemberId?.takeIf { it.isNotBlank() }
            ?: entry.memberId?.takeIf { it.isNotBlank() }
        val deleterId = entry.deletedByMemberId?.takeIf { it.isNotBlank() }
        if (creatorId == null || deleterId == null || creatorId != deleterId) return false
        val gap = deletedAt - entry.timestampEpochMs
        return gap in 0..QUIET_SELF_DELETE_WINDOW_MS
    }
}
