package com.anant.splitbill.data.repository

import com.anant.splitbill.data.database.MemberEntity
import com.anant.splitbill.data.model.EntryType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class BillEngineTest {

    private fun member(id: String, name: String, order: Int) = MemberEntity(
        id = id,
        roomId = "room1",
        name = name,
        sortOrder = order,
        createdAtEpochMs = 0L
    )

    @Test
    fun deductPreviousRecharge_splits_proportionally_by_consumption() {
        val a = member("a", "Alice", 0)
        val b = member("b", "Bob", 1)
        val members = listOf(a, b)

        var state = BillEngine.emptyState(members)
        state = state.copy(
            members = listOf(
                state.members[0].copy(
                    balance = BigDecimal("100.00"),
                    lastReading = 130.0,
                    lastReadingBeforeRecharge = 100.0
                ),
                state.members[1].copy(
                    balance = BigDecimal("50.00"),
                    lastReading = 170.0,
                    lastReadingBeforeRecharge = 100.0
                )
            ),
            lastRechargeAmount = 100.0,
            lastRechargeMemberId = "a",
            lastRechargeMemberName = "Alice"
        )

        val after = BillEngine.deductPreviousRecharge(state)
        val byId = after.byId()

        // Alice consumed 30, Bob 70 → deduct Rs.30 and Rs.70 from prior recharge pool
        assertEquals(BigDecimal("70.00"), byId.getValue("a").balance)
        assertEquals(BigDecimal("-20.00"), byId.getValue("b").balance)
    }

    @Test
    fun recordExpense_splits_equally_and_credits_payer() {
        val a = member("a", "Alice", 0)
        val b = member("b", "Bob", 1)
        val c = member("c", "Carol", 2)
        val members = listOf(a, b, c)
        val state = BillEngine.emptyState(members)

        val result = BillEngine.recordExpense(
            roomId = "room1",
            members = members,
            current = state,
            payerId = "a",
            amount = 90.0,
            note = "Groceries"
        )

        assertEquals(1, result.entries.size)
        assertEquals(EntryType.EXPENSE, result.entries.first().type)

        val byId = result.state.byId()
        // Each owes 30; Alice paid 90 → net +60
        assertEquals(BigDecimal("60.00"), byId.getValue("a").balance)
        assertEquals(BigDecimal("-30.00"), byId.getValue("b").balance)
        assertEquals(BigDecimal("-30.00"), byId.getValue("c").balance)
    }

    @Test
    fun recordReadingsAndRecharge_applies_proportional_deduction_before_new_recharge() {
        val a = member("a", "Alice", 0)
        val b = member("b", "Bob", 1)
        val members = listOf(a, b)

        var state = BillEngine.emptyState(members)
        state = state.copy(
            members = listOf(
                state.members[0].copy(
                    balance = BigDecimal("80.00"),
                    lastReading = 100.0,
                    lastReadingBeforeRecharge = 70.0
                ),
                state.members[1].copy(
                    balance = BigDecimal("20.00"),
                    lastReading = 100.0,
                    lastReadingBeforeRecharge = 30.0
                )
            ),
            lastRechargeAmount = 100.0,
            lastRechargeMemberId = "a",
            lastRechargeMemberName = "Alice"
        )

        val result = BillEngine.recordReadingsAndRecharge(
            roomId = "room1",
            members = members,
            current = state,
            readings = mapOf("a" to 130.0, "b" to 170.0),
            rechargeMemberId = "b",
            rechargeAmount = 50.0,
            groupId = UUID.randomUUID().toString()
        )

        val readings = result.entries.filter { it.type == EntryType.READING }
        assertEquals(2, readings.size)
        assertEquals(30.0, readings.first { it.memberId == "a" }.consumption!!, 0.001)
        assertEquals(70.0, readings.first { it.memberId == "b" }.consumption!!, 0.001)
        assertEquals(1, result.entries.count { it.type == EntryType.RECHARGE })

        val byId = result.state.byId()
        // Prior recharge split 30/70 on consumption 30+70; then Bob gets +50 recharge
        assertEquals(BigDecimal("50.00"), byId.getValue("a").balance)
        assertEquals(BigDecimal("0.00"), byId.getValue("b").balance)
    }

    @Test
    fun prepaidRecharge_afterBaselineReadings_deductsOnlyFutureConsumption() {
        val a = member("a", "Alice", 0)
        val b = member("b", "Bob", 1)
        val c = member("c", "Carol", 2)
        val members = listOf(a, b, c)
        var state = BillEngine.emptyState(members)

        // Meters already at 30, 50, 100 — baseline log (no recharge yet).
        val baselineResult = BillEngine.recordReadingsAndRecharge(
            roomId = "room1",
            members = members,
            current = state,
            readings = mapOf("a" to 30.0, "b" to 50.0, "c" to 100.0),
            rechargeMemberId = "a",
            rechargeAmount = 0.0,
            groupId = "g1"
        )
        for (reading in baselineResult.entries.filter { it.type == EntryType.READING }) {
            assertEquals(0.0, reading.consumption!!, 0.001)
        }
        state = baselineResult.state

        assertEquals(30.0, state.byId().getValue("a").lastReading, 0.001)
        assertEquals(0.0, state.byId().getValue("a").lastReadingBeforeRecharge, 0.001)

        // Someone recharges at the same meter levels — full amount is prepaid credit.
        state = BillEngine.recordReadingsAndRecharge(
            roomId = "room1",
            members = members,
            current = state,
            readings = mapOf("a" to 30.0, "b" to 50.0, "c" to 100.0),
            rechargeMemberId = "b",
            rechargeAmount = 600.0,
            groupId = "g2"
        ).state

        val afterRecharge = state.byId()
        assertEquals(BigDecimal("0.00"), afterRecharge.getValue("a").balance)
        assertEquals(BigDecimal("600.00"), afterRecharge.getValue("b").balance)
        assertEquals(BigDecimal("0.00"), afterRecharge.getValue("c").balance)
        assertEquals(30.0, afterRecharge.getValue("a").lastReadingBeforeRecharge, 0.001)
        assertEquals(50.0, afterRecharge.getValue("b").lastReadingBeforeRecharge, 0.001)
        assertEquals(100.0, afterRecharge.getValue("c").lastReadingBeforeRecharge, 0.001)

        // Next readings: consumption 10 + 10 + 30 = 50 units → split Rs.600 prepaid pool.
        state = BillEngine.recordReadingsAndRecharge(
            roomId = "room1",
            members = members,
            current = state,
            readings = mapOf("a" to 40.0, "b" to 60.0, "c" to 130.0),
            rechargeMemberId = "c",
            rechargeAmount = 0.0,
            groupId = "g3"
        ).state

        val afterUsage = state.byId()
        // 10/50 * 600 = 120, 10/50 * 600 = 120, 30/50 * 600 = 360
        assertEquals(BigDecimal("-120.00"), afterUsage.getValue("a").balance)
        assertEquals(BigDecimal("480.00"), afterUsage.getValue("b").balance)
        assertEquals(BigDecimal("-360.00"), afterUsage.getValue("c").balance)
    }
}
