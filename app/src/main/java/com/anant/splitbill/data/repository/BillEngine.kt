package com.anant.splitbill.data.repository

import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.database.MemberEntity
import com.anant.splitbill.data.model.EntryType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.math.max

/**
 * Pure port of tenant-electricity-bill-calculator `CsvCalculator` logic,
 * generalized to a configurable ordered member list.
 */
object BillEngine {

    data class MemberState(
        val memberId: String,
        val name: String,
        val balance: BigDecimal = BigDecimal.ZERO.setScale(2),
        val lastReading: Double = 0.0,
        val lastReadingBeforeRecharge: Double = 0.0
    )

    data class RoomState(
        val members: List<MemberState>,
        val lastRechargeAmount: Double = 0.0,
        val lastRechargeMemberId: String? = null,
        val lastRechargeMemberName: String? = null
    ) {
        fun byId(): Map<String, MemberState> = members.associateBy { it.memberId }

        fun nextRechargeMember(): MemberState? =
            members.minWithOrNull(compareBy<MemberState> { it.balance }.thenBy { it.name })
    }

    data class RecordResult(
        val entries: List<EntryEntity>,
        val state: RoomState
    )

    fun emptyState(members: List<MemberEntity>): RoomState =
        RoomState(
            members = members.sortedBy { it.sortOrder }.map {
                MemberState(memberId = it.id, name = it.name)
            }
        )

    /** Rebuild live state by replaying persisted entries (CSV load equivalent). */
    fun rebuild(members: List<MemberEntity>, entries: List<EntryEntity>): RoomState {
        val ordered = members.sortedBy { it.sortOrder }
        var state = emptyState(ordered)
        if (entries.isEmpty()) return state

        val lastSnap = entries.lastOrNull { it.balancesSnapshot.isNotBlank() }?.balancesSnapshot
        if (lastSnap != null) {
            state = state.withBalances(parseBalances(lastSnap, state))
        }

        val lastRecharge = entries.lastOrNull { it.type == EntryType.RECHARGE }
        if (lastRecharge != null) {
            state = state.copy(
                lastRechargeAmount = lastRecharge.value,
                lastRechargeMemberId = lastRecharge.memberId,
                lastRechargeMemberName = lastRecharge.memberName
            )
            val rechargeIdx = entries.indexOf(lastRecharge)
            val seen = mutableSetOf<String>()
            val before = state.byId().toMutableMap()
            for (i in (rechargeIdx - 1) downTo 0) {
                val e = entries[i]
                if (e.type == EntryType.READING && e.memberId != null && e.memberId !in seen) {
                    before[e.memberId] = before.getValue(e.memberId).copy(
                        lastReadingBeforeRecharge = e.value
                    )
                    seen += e.memberId
                    if (seen.size == ordered.size) break
                }
            }
            state = state.copy(members = ordered.map { before.getValue(it.id) })
        }

        val readings = state.byId().toMutableMap()
        for (m in ordered) {
            val last = entries.lastOrNull { it.type == EntryType.READING && it.memberId == m.id }
            if (last != null) {
                readings[m.id] = readings.getValue(m.id).copy(lastReading = last.value)
            }
        }
        return state.copy(members = ordered.map { readings.getValue(it.id) })
    }

    /**
     * Port of `record_readings_and_recharge`:
     * 1) write READING rows
     * 2) deduct previous recharge proportionally by consumption since last recharge
     * 3) credit new recharge to payer
     */
    fun recordReadingsAndRecharge(
        roomId: String,
        members: List<MemberEntity>,
        current: RoomState,
        readings: Map<String, Double>,
        rechargeMemberId: String,
        rechargeAmount: Double,
        nowEpochMs: Long = System.currentTimeMillis(),
        groupId: String = UUID.randomUUID().toString()
    ): RecordResult {
        val ordered = members.sortedBy { it.sortOrder }
        require(ordered.isNotEmpty()) { "Add at least one member" }
        val stateMap = current.byId().toMutableMap()
        for (m in ordered) {
            val newVal = readings[m.id]
                ?: throw IllegalArgumentException("Missing reading for ${m.name}")
            val prev = stateMap.getValue(m.id).lastReading
            if (newVal < prev) {
                throw IllegalArgumentException(
                    "New reading for ${m.name} ($newVal) cannot be less than previous ($prev)"
                )
            }
        }

        val out = mutableListOf<EntryEntity>()
        var working = current

        for (m in ordered) {
            val newVal = readings.getValue(m.id)
            val prev = stateMap.getValue(m.id).lastReading
            val consumption = newVal - prev
            stateMap[m.id] = stateMap.getValue(m.id).copy(lastReading = newVal)
            working = working.copy(members = ordered.map { stateMap.getValue(it.id) })
            out += EntryEntity(
                id = UUID.randomUUID().toString(),
                roomId = roomId,
                type = EntryType.READING,
                memberId = m.id,
                memberName = m.name,
                value = newVal,
                consumption = consumption,
                timestampEpochMs = nowEpochMs,
                groupId = groupId,
                balancesSnapshot = balancesString(working)
            )
        }

        working = deductPreviousRecharge(working)
        val afterDeduct = working.byId().toMutableMap()

        if (rechargeAmount > 0.0) {
            val payer = ordered.find { it.id == rechargeMemberId }
                ?: throw IllegalArgumentException("Unknown recharge member")
            val credited = afterDeduct.getValue(payer.id).balance
                .add(BigDecimal.valueOf(rechargeAmount))
                .setScale(2, RoundingMode.HALF_UP)
            afterDeduct[payer.id] = afterDeduct.getValue(payer.id).copy(balance = credited)
            for (m in ordered) {
                afterDeduct[m.id] = afterDeduct.getValue(m.id).copy(
                    lastReadingBeforeRecharge = afterDeduct.getValue(m.id).lastReading
                )
            }
            working = working.copy(
                members = ordered.map { afterDeduct.getValue(it.id) },
                lastRechargeAmount = rechargeAmount,
                lastRechargeMemberId = payer.id,
                lastRechargeMemberName = payer.name
            )
            out += EntryEntity(
                id = UUID.randomUUID().toString(),
                roomId = roomId,
                type = EntryType.RECHARGE,
                memberId = payer.id,
                memberName = payer.name,
                value = rechargeAmount,
                consumption = null,
                timestampEpochMs = nowEpochMs + 1000L,
                groupId = groupId,
                balancesSnapshot = balancesString(working)
            )
        } else {
            working = working.copy(members = ordered.map { afterDeduct.getValue(it.id) })
        }

        return RecordResult(entries = out, state = working)
    }

    /** Equal-split expense: payer credited full amount, everyone owes amount/n. */
    fun recordExpense(
        roomId: String,
        members: List<MemberEntity>,
        current: RoomState,
        payerId: String,
        amount: Double,
        note: String = "",
        nowEpochMs: Long = System.currentTimeMillis(),
        groupId: String = UUID.randomUUID().toString()
    ): RecordResult {
        require(amount > 0.0) { "Expense amount must be positive" }
        val ordered = members.sortedBy { it.sortOrder }
        val payer = ordered.find { it.id == payerId }
            ?: throw IllegalArgumentException("Unknown payer")
        val share = BigDecimal.valueOf(amount)
            .divide(BigDecimal.valueOf(ordered.size.toLong()), 2, RoundingMode.HALF_UP)
        val map = current.byId().toMutableMap()
        for (m in ordered) {
            map[m.id] = map.getValue(m.id).copy(
                balance = map.getValue(m.id).balance.subtract(share).setScale(2, RoundingMode.HALF_UP)
            )
        }
        map[payer.id] = map.getValue(payer.id).copy(
            balance = map.getValue(payer.id).balance
                .add(BigDecimal.valueOf(amount))
                .setScale(2, RoundingMode.HALF_UP)
        )
        val working = current.copy(members = ordered.map { map.getValue(it.id) })
        val entry = EntryEntity(
            id = UUID.randomUUID().toString(),
            roomId = roomId,
            type = EntryType.EXPENSE,
            memberId = payer.id,
            memberName = payer.name,
            value = amount,
            note = note,
            timestampEpochMs = nowEpochMs,
            groupId = groupId,
            balancesSnapshot = balancesString(working)
        )
        return RecordResult(listOf(entry), working)
    }

    /** Port of `calculate_and_deduct_previous_recharge`. */
    fun deductPreviousRecharge(state: RoomState): RoomState {
        if (state.members.all { it.lastReadingBeforeRecharge == 0.0 }) return state

        val consumption = state.members.associate { m ->
            m.memberId to max(0.0, m.lastReading - m.lastReadingBeforeRecharge)
        }
        val total = consumption.values.sum()
        if (total <= 0.0 || state.lastRechargeAmount <= 0.0) return state

        val map = state.byId().toMutableMap()
        for (m in state.members) {
            val ratio = consumption.getValue(m.memberId) / total
            val deduction = BigDecimal.valueOf(state.lastRechargeAmount)
                .multiply(BigDecimal.valueOf(ratio))
                .setScale(2, RoundingMode.HALF_UP)
            map[m.memberId] = m.copy(
                balance = m.balance.subtract(deduction).setScale(2, RoundingMode.HALF_UP)
            )
        }
        return state.copy(members = state.members.map { map.getValue(it.memberId) })
    }

    fun balancesString(state: RoomState, currency: String = "Rs."): String =
        state.members.joinToString("; ") { "${it.name}: $currency${it.balance.setScale(2)}" }

    fun formatMoney(amount: BigDecimal, currency: String = "Rs."): String =
        "$currency${amount.setScale(2, RoundingMode.HALF_UP)}"

    fun parseBalances(snapshot: String, template: RoomState): Map<String, BigDecimal> {
        val byName = template.members.associate { it.name to it.memberId }
        val out = template.members.associate { it.memberId to it.balance }.toMutableMap()
        for (part in snapshot.split(';')) {
            val p = part.trim()
            if (": Rs." !in p && ":Rs." !in p) continue
            val sep = if (": Rs." in p) ": Rs." else ":Rs."
            val bits = p.split(sep, limit = 2)
            if (bits.size != 2) continue
            val id = byName[bits[0].trim()] ?: continue
            out[id] = bits[1].trim().replace(",", "").toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
                ?: continue
        }
        return out
    }

    private fun RoomState.withBalances(balances: Map<String, BigDecimal>): RoomState =
        copy(members = members.map { it.copy(balance = balances[it.memberId] ?: it.balance) })
}
