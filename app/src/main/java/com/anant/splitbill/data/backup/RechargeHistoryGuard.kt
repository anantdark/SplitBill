package com.anant.splitbill.data.backup

import com.anant.splitbill.data.model.EntryType

/**
 * Recharges are immutable history: soft-delete is allowed, dropping rows is not.
 * Used before cloud push/pull so an empty or partial local DB cannot wipe a room.
 */
object RechargeHistoryGuard {

    /** RECHARGE entry ids present in [data], including soft-deleted rows. */
    fun rechargeIds(data: BackupData): Set<String> =
        data.entries
            .asSequence()
            .filter { it.type == EntryType.RECHARGE }
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Ids that exist in [baseline] but are missing from [candidate].
     * Empty when [candidate] is a superset (append / soft-delete only).
     */
    fun missingRechargeIds(baseline: BackupData, candidate: BackupData): List<String> {
        val keep = rechargeIds(candidate)
        return rechargeIds(baseline)
            .filter { it !in keep }
            .sorted()
    }

    fun rejectionMessage(missingCount: Int): String {
        val n = missingCount.coerceAtLeast(1)
        return "Cloud sync refused: this device is still missing $n existing recharge" +
            "${if (n == 1) "" else "s"}. " +
            "Overwrite from cloud, or join the room again — history cannot be wiped."
    }
}
