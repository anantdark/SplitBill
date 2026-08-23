package com.anant.splitbill.data.analytics

import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.model.EntryType
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

data class UsagePoint(
    val yearMonth: YearMonth,
    val label: String,
    val units: Double,
)

data class MemberUsage(
    val memberId: String,
    val name: String,
    val units: Double,
)

data class UsageSummary(
    val monthly: List<UsagePoint>,
    val byMember: List<MemberUsage>,
    val totalUnits: Double,
)

object UsageAnalytics {

    fun summarize(
        entries: List<EntryEntity>,
        months: Int = 6,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): UsageSummary {
        val readings = entries.filter {
            it.type == EntryType.READING && it.consumption != null && !it.deleted
        }
        if (readings.isEmpty()) {
            return UsageSummary(monthly = emptyList(), byMember = emptyList(), totalUnits = 0.0)
        }

        val currentMonth = YearMonth.now(zoneId)
        val window = (months - 1 downTo 0).map { currentMonth.minusMonths(it.toLong()) }.toSet()

        val readingsByMember = readings
            .filter { it.memberId != null }
            .groupBy { it.memberId!! }
            .mapValues { (_, memberReadings) ->
                memberReadings.sortedBy { it.timestampEpochMs }
            }

        val baselineReadingIds = readingsByMember.mapValues { (_, memberReadings) ->
            memberReadings.first().id
        }

        val monthlyTotals = window.associateWith { 0.0 }.toMutableMap()
        val memberTotals = mutableMapOf<String, Pair<String, Double>>()

        for ((memberId, memberReadings) in readingsByMember) {
            for (entry in memberReadings) {
                if (entry.id in baselineReadingIds.values) continue
                val consumption = entry.consumption ?: continue
                if (consumption <= 0.0) continue
                val existing = memberTotals[memberId]
                memberTotals[memberId] = entry.memberName to ((existing?.second ?: 0.0) + consumption)
            }

            for (month in window) {
                val monthStart = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val monthEnd = month.atEndOfMonth().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1

                val inMonth = memberReadings.filter { it.timestampEpochMs in monthStart..monthEnd }
                if (inMonth.isEmpty()) continue

                val lastInMonth = inMonth.last()
                val priorReading = memberReadings.lastOrNull { it.timestampEpochMs < monthStart }
                val startValue = priorReading?.value ?: inMonth.first().value
                val monthlyUsage = (lastInMonth.value - startValue).coerceAtLeast(0.0)
                if (monthlyUsage > 0.0) {
                    monthlyTotals[month] = monthlyTotals.getValue(month) + monthlyUsage
                }
            }
        }

        val monthly = window.sorted().map { month ->
            UsagePoint(
                yearMonth = month,
                label = monthLabel(month, locale),
                units = monthlyTotals.getValue(month),
            )
        }
        val byMember = memberTotals.entries
            .map { (id, nameAndUnits) ->
                MemberUsage(memberId = id, name = nameAndUnits.first, units = nameAndUnits.second)
            }
            .sortedByDescending { it.units }

        return UsageSummary(
            monthly = monthly,
            byMember = byMember,
            totalUnits = byMember.sumOf { it.units },
        )
    }

    private fun monthLabel(month: YearMonth, locale: Locale): String {
        val name = month.month.getDisplayName(TextStyle.SHORT, locale)
        return if (month.year == YearMonth.now().year) name else "$name '${month.year % 100}"
    }
}
