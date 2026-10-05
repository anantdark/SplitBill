package com.anant.splitbill.data.backup

import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RechargeHistoryGuardTest {

    @Test
    fun missingRechargeIds_emptyWhenSuperset() {
        val baseline = backup(recharge("a"), recharge("b", deleted = true))
        val candidate = backup(recharge("a"), recharge("b", deleted = true), recharge("c"))
        assertTrue(RechargeHistoryGuard.missingRechargeIds(baseline, candidate).isEmpty())
    }

    @Test
    fun missingRechargeIds_reportsDroppedIncludingSoftDeleted() {
        val baseline = backup(recharge("a"), recharge("b", deleted = true))
        val candidate = backup(recharge("a"))
        assertEquals(listOf("b"), RechargeHistoryGuard.missingRechargeIds(baseline, candidate))
    }

    @Test
    fun missingRechargeIds_emptyBaselineAllowsAnything() {
        val baseline = backup()
        val candidate = backup()
        assertTrue(RechargeHistoryGuard.missingRechargeIds(baseline, candidate).isEmpty())
    }

    @Test
    fun softDeleteAlone_isNotHistoryLoss() {
        val baseline = backup(recharge("a"))
        val candidate = backup(recharge("a", deleted = true))
        assertTrue(RechargeHistoryGuard.missingRechargeIds(baseline, candidate).isEmpty())
    }

    @Test
    fun emptyLocal_missingAllCloudRecharges() {
        val cloud = backup(recharge("a"), recharge("b"))
        val local = backup()
        assertEquals(listOf("a", "b"), RechargeHistoryGuard.missingRechargeIds(cloud, local))
    }

    private fun backup(vararg entries: EntryEntity) = BackupData(
        exportedAt = 1L,
        entries = entries.toList(),
    )

    private fun recharge(id: String, deleted: Boolean = false) = EntryEntity(
        id = id,
        roomId = "room",
        type = EntryType.RECHARGE,
        memberId = "m1",
        memberName = "Ground Floor",
        value = 100.0,
        timestampEpochMs = 1L,
        groupId = "g-$id",
        balancesSnapshot = "",
        deleted = deleted,
    )
}
