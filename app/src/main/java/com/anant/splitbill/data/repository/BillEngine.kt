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

    /** Rebuild live state by replaying non-deleted entries in order. */
    fun rebuild(members: List<MemberEntity>, entries: List<EntryEntity>): RoomState {
        val ordered = members.sortedBy { it.sortOrder }
        var state = emptyState(ordered)
        val active = entries.filter { !it.deleted }
        if (active.isEmpty()) return state

        val groups = active
            .groupBy { it.groupId }
            .entries
            .sortedBy { (_, group) -> group.minOf { it.timestampEpochMs } }

        for ((_, groupEntries) in groups) {
            val readings = groupEntries.filter { it.type == EntryType.READING }
            val recharge = groupEntries.firstOrNull { it.type == EntryType.RECHARGE }
            val expense = groupEntries.firstOrNull { it.type == EntryType.EXPENSE }

            when {
                readings.isNotEmpty() -> {
                    val readingMap = readings.mapNotNull { e ->
                        val id = e.memberId ?: return@mapNotNull null
                        id to e.value
                    }.toMap()
                    if (readingMap.size != ordered.size) {
                        // Incomplete group — fall back to last snapshot in this group.
                        val snap = groupEntries.lastOrNull { it.balancesSnapshot.isNotBlank() }
                            ?.balancesSnapshot
                        if (snap != null) {
                            state = state.withBalances(parseBalances(snap, state))
                            for (r in readings) {
                                val mid = r.memberId ?: continue
                                val map = state.byId().toMutableMap()
                                if (mid in map) {
                                    map[mid] = map.getValue(mid).copy(lastReading = r.value)
                                    state = state.copy(members = ordered.map { map.getValue(it.id) })
                                }
                            }
                            if (recharge != null) {
                                state = state.copy(
                                    lastRechargeAmount = recharge.value,
                                    lastRechargeMemberId = recharge.memberId,
                                    lastRechargeMemberName = recharge.memberName
                                )
                            }
                        }
                        continue
                    }
                    val result = recordReadingsAndRecharge(
                        roomId = readings.first().roomId,
                        members = ordered,
                        current = state,
                        readings = readingMap,
                        rechargeMemberId = recharge?.memberId.orEmpty(),
                        rechargeAmount = recharge?.value ?: 0.0,
                        nowEpochMs = readings.minOf { it.timestampEpochMs },
                        groupId = groupEntries.first().groupId,
                    )
                    state = result.state
                }
                expense != null -> {
                    val payerId = expense.memberId ?: continue
                    val result = recordExpense(
                        roomId = expense.roomId,
                        members = ordered,
                        current = state,
                        payerId = payerId,
                        amount = expense.value,
                        note = expense.note,
                        nowEpochMs = expense.timestampEpochMs,
                        groupId = expense.groupId,
                    )
                    state = result.state
                }
            }
        }
        return state
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
        note: String = "",
        nowEpochMs: Long = System.currentTimeMillis(),
        groupId: String = UUID.randomUUID().toString(),
        loggedByMemberId: String? = null,
        loggedByMemberName: String? = null,
        loggedByDeviceId: String? = null,
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
            val consumption = if (prev <= 0.0) 0.0 else newVal - prev
            stateMap[m.id] = stateMap.getValue(m.id).copy(lastReading = newVal)
            working = working.copy(members = ordered.map { stateMap.getValue(it.id) })
            out += EntryEntity(
                id = UUID.randomUUID().toString(),
                roomId = roomId,
                type = EntryType.READING,
                memberId = m.id,
                memberName = m.name,
                loggedByMemberId = loggedByMemberId,
                loggedByMemberName = loggedByMemberName,
                loggedByDeviceId = loggedByDeviceId,
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
                loggedByMemberId = loggedByMemberId,
                loggedByMemberName = loggedByMemberName,
                loggedByDeviceId = loggedByDeviceId,
                value = rechargeAmount,
                consumption = null,
                note = note,
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
        groupId: String = UUID.randomUUID().toString(),
        loggedByMemberId: String? = null,
        loggedByMemberName: String? = null,
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
            loggedByMemberId = loggedByMemberId,
            loggedByMemberName = loggedByMemberName,
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
