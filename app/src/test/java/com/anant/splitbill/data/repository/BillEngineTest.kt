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
        assertEquals(1, result.entries.count { it.type == EntryType.RECHARGE })

        val byId = result.state.byId()
        // Prior recharge split 30/70 on consumption 30+70; then Bob gets +50 recharge
        assertEquals(BigDecimal("50.00"), byId.getValue("a").balance)
        assertEquals(BigDecimal("0.00"), byId.getValue("b").balance)
    }
}
