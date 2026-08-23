package com.anant.splitbill.data.analytics

import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.EntryType
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageAnalyticsTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun reading(
        memberId: String,
        name: String,
        consumption: Double,
        date: LocalDate,
    ) = EntryEntity(
        id = "$memberId-$date",
        roomId = "room",
        type = EntryType.READING,
        memberId = memberId,
        memberName = name,
        value = 100.0,
        consumption = consumption,
        timestampEpochMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        groupId = "g1",
        balancesSnapshot = "",
    )

    @Test
    fun summarize_groups_monthly_and_member_totals() {
        val now = LocalDate.now(zone)
        val entries = listOf(
            reading("a", "Alice", 30.0, now.withDayOfMonth(5)),
            reading("b", "Bob", 70.0, now.withDayOfMonth(5)),
            reading("a", "Alice", 20.0, now.minusMonths(1).withDayOfMonth(10)),
        )

        val summary = UsageAnalytics.summarize(entries, months = 2, zoneId = zone)

        assertEquals(120.0, summary.totalUnits, 0.001)
        assertEquals(50.0, summary.byMember.first { it.memberId == "a" }.units, 0.001)
        assertEquals(70.0, summary.byMember.first { it.memberId == "b" }.units, 0.001)
        assertEquals(100.0, summary.monthly.last().units, 0.001)
        assertEquals(20.0, summary.monthly[summary.monthly.lastIndex - 1].units, 0.001)
    }
}
