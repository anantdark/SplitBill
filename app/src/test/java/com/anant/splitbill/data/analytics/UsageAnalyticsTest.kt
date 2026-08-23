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
        value: Double,
        consumption: Double,
        date: LocalDate,
    ) = EntryEntity(
        id = "$memberId-$date-$value",
        roomId = "room",
        type = EntryType.READING,
        memberId = memberId,
        memberName = name,
        value = value,
        consumption = consumption,
        timestampEpochMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        groupId = "g1",
        balancesSnapshot = "",
    )

    @Test
    fun summarize_excludes_baseline_reading_from_member_totals() {
        val now = LocalDate.now(zone)
        val entries = listOf(
            reading("a", "Alice", 150.0, 0.0, now.minusMonths(2).withDayOfMonth(1)),
            reading("a", "Alice", 180.0, 30.0, now.withDayOfMonth(5)),
            reading("b", "Bob", 200.0, 0.0, now.minusMonths(2).withDayOfMonth(1)),
            reading("b", "Bob", 270.0, 70.0, now.withDayOfMonth(5)),
        )

        val summary = UsageAnalytics.summarize(entries, months = 6, zoneId = zone)

        assertEquals(100.0, summary.totalUnits, 0.001)
        assertEquals(30.0, summary.byMember.first { it.memberId == "a" }.units, 0.001)
        assertEquals(70.0, summary.byMember.first { it.memberId == "b" }.units, 0.001)
    }

    @Test
    fun summarize_groups_monthly_and_member_totals() {
        val now = LocalDate.now(zone)
        val entries = listOf(
            reading("a", "Alice", 100.0, 0.0, now.minusMonths(2).withDayOfMonth(1)),
            reading("b", "Bob", 200.0, 0.0, now.minusMonths(2).withDayOfMonth(1)),
            reading("a", "Alice", 120.0, 20.0, now.minusMonths(1).withDayOfMonth(10)),
            reading("a", "Alice", 130.0, 10.0, now.withDayOfMonth(5)),
            reading("b", "Bob", 270.0, 70.0, now.withDayOfMonth(5)),
        )

        val summary = UsageAnalytics.summarize(entries, months = 2, zoneId = zone)

        assertEquals(100.0, summary.totalUnits, 0.001)
        assertEquals(30.0, summary.byMember.first { it.memberId == "a" }.units, 0.001)
        assertEquals(70.0, summary.byMember.first { it.memberId == "b" }.units, 0.001)
        assertEquals(80.0, summary.monthly.last().units, 0.001)
        assertEquals(20.0, summary.monthly[summary.monthly.lastIndex - 1].units, 0.001)
    }

    @Test
    fun summarize_monthly_uses_last_reading_diff_not_sum_of_logs() {
        val now = LocalDate.now(zone)
        val entries = listOf(
            reading("a", "Alice", 100.0, 0.0, now.withDayOfMonth(1)),
            reading("a", "Alice", 120.0, 20.0, now.withDayOfMonth(10)),
            reading("a", "Alice", 140.0, 20.0, now.withDayOfMonth(20)),
        )

        val summary = UsageAnalytics.summarize(entries, months = 1, zoneId = zone)

        assertEquals(40.0, summary.monthly.single().units, 0.001)
    }
}
