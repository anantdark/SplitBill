package com.anant.splitbill.data.repository

import android.content.Context
import com.anant.splitbill.data.database.AppDatabase
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.database.MemberEntity
import com.anant.splitbill.data.database.RoomEntity
import com.anant.splitbill.data.model.MemberBalance
import com.anant.splitbill.data.model.RoomDashboard
import com.anant.splitbill.ui.widget.BalanceWidgetReceiver
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SplitBillRepository(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val roomDao = db.roomDao()
    private val memberDao = db.memberDao()
    private val entryDao = db.entryDao()

    fun observeRooms(): Flow<List<RoomEntity>> = roomDao.observeRooms()

    fun observeDashboard(roomId: String): Flow<RoomDashboard?> =
        combine(
            roomDao.observeRooms().map { rooms -> rooms.find { it.id == roomId } },
            memberDao.observeMembers(roomId),
            entryDao.observeEntries(roomId)
        ) { room, members, entries ->
            if (room == null) return@combine null
            val state = BillEngine.rebuild(members, entries)
            val next = state.nextRechargeMember()
            RoomDashboard(
                roomId = room.id,
                roomName = room.name,
                currencySymbol = room.currencySymbol,
                members = state.members.map { m ->
                    MemberBalance(
                        memberId = m.memberId,
                        name = m.name,
                        balance = BillEngine.formatMoney(m.balance, room.currencySymbol),
                        balanceValue = m.balance.toDouble(),
                        lastReading = m.lastReading,
                        isNextToRecharge = next?.memberId == m.memberId
                    )
                },
                nextRechargeMemberId = next?.memberId,
                nextRechargeMemberName = next?.name,
                lastRechargeAmount = state.lastRechargeAmount,
                lastRechargeMemberName = state.lastRechargeMemberName,
                entryCount = entries.size
            )
        }

    fun observeEntries(roomId: String): Flow<List<EntryEntity>> = entryDao.observeRecent(roomId, 200)

    suspend fun roomCount(): Int = roomDao.count()

    suspend fun getRoom(roomId: String): RoomEntity? = roomDao.getRoom(roomId)

    suspend fun getMembers(roomId: String): List<MemberEntity> = memberDao.getMembers(roomId)

    suspend fun getEntries(roomId: String): List<EntryEntity> = entryDao.getEntries(roomId)

    /**
     * Creates the household room. [roomId] should be the Support / Room ID so cloud sync
     * and join/restore share the same key.
     */
    suspend fun createRoom(
        name: String,
        memberNames: List<String>,
        roomId: String,
        currencySymbol: String = "Rs.",
    ): String {
        val id = roomId.trim()
        require(id.isNotBlank()) { "Room ID is required" }
        val now = System.currentTimeMillis()
        val cleaned = memberNames.map { it.trim() }.filter { it.isNotEmpty() }
        require(cleaned.isNotEmpty()) { "Add at least one member" }
        roomDao.upsert(
            RoomEntity(
                id = id,
                name = name.trim().ifBlank { "My room" },
                createdAtEpochMs = now,
                currencySymbol = currencySymbol
            )
        )
        memberDao.upsertAll(
            cleaned.mapIndexed { index, memberName ->
                MemberEntity(
                    id = UUID.randomUUID().toString(),
                    roomId = id,
                    name = memberName,
                    sortOrder = index,
                    createdAtEpochMs = now
                )
            }
        )
        BalanceWidgetReceiver.requestUpdate(context)
        return id
    }

    /**
     * Rewrites the primary room (and its members/entries) so room.id == [supportId].
     * Used when adopting Support ID as Room ID on existing installs or after join.
     */
    suspend fun alignPrimaryRoomId(supportId: String) {
        val id = supportId.trim()
        if (id.isBlank()) return
        val (rooms, members, entries) = exportSnapshot()
        if (rooms.isEmpty()) return
        if (rooms.any { it.id == id }) return
        val primaryId = rooms.first().id
        val rewrittenRooms = rooms.map { room ->
            if (room.id == primaryId) room.copy(id = id) else room
        }
        val rewrittenMembers = members.map { member ->
            if (member.roomId == primaryId) member.copy(roomId = id) else member
        }
        val rewrittenEntries = entries.map { entry ->
            if (entry.roomId == primaryId) entry.copy(roomId = id) else entry
        }
        replaceAllData(rewrittenRooms, rewrittenMembers, rewrittenEntries)
    }

    suspend fun addMember(roomId: String, name: String) {
        val members = memberDao.getMembers(roomId)
        memberDao.upsert(
            MemberEntity(
                id = UUID.randomUUID().toString(),
                roomId = roomId,
                name = name.trim(),
                sortOrder = (members.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )
        BalanceWidgetReceiver.requestUpdate(context)
    }

    suspend fun recordReadingsAndRecharge(
        roomId: String,
        readings: Map<String, Double>,
        rechargeMemberId: String,
        rechargeAmount: Double
    ) {
        val members = memberDao.getMembers(roomId)
        val entries = entryDao.getEntries(roomId)
        val current = BillEngine.rebuild(members, entries)
        val result = BillEngine.recordReadingsAndRecharge(
            roomId = roomId,
            members = members,
            current = current,
            readings = readings,
            rechargeMemberId = rechargeMemberId,
            rechargeAmount = rechargeAmount
        )
        entryDao.insertAll(result.entries)
        BalanceWidgetReceiver.requestUpdate(context)
    }

    suspend fun recordExpense(roomId: String, payerId: String, amount: Double, note: String) {
        val members = memberDao.getMembers(roomId)
        val entries = entryDao.getEntries(roomId)
        val current = BillEngine.rebuild(members, entries)
        val result = BillEngine.recordExpense(
            roomId = roomId,
            members = members,
            current = current,
            payerId = payerId,
            amount = amount,
            note = note
        )
        entryDao.insertAll(result.entries)
        BalanceWidgetReceiver.requestUpdate(context)
    }

    suspend fun revertLastGroup(roomId: String) {
        val latest = entryDao.latest(roomId) ?: return
        entryDao.deleteGroup(latest.groupId)
        BalanceWidgetReceiver.requestUpdate(context)
    }

    suspend fun replaceAllData(
        rooms: List<RoomEntity>,
        members: List<MemberEntity>,
        entries: List<EntryEntity>
    ) {
        db.clearAllTables()
        rooms.forEach { roomDao.upsert(it) }
        if (members.isNotEmpty()) memberDao.upsertAll(members)
        if (entries.isNotEmpty()) entryDao.insertAll(entries)
        BalanceWidgetReceiver.requestUpdate(context)
    }

    suspend fun exportSnapshot(): Triple<List<RoomEntity>, List<MemberEntity>, List<EntryEntity>> {
        val rooms = roomDao.observeRooms().first()
        val members = rooms.flatMap { memberDao.getMembers(it.id) }
        val entries = rooms.flatMap { entryDao.getEntries(it.id) }
        return Triple(rooms, members, entries)
    }

    suspend fun totalEntryCount(): Int {
        val rooms = roomDao.observeRooms().first()
        return rooms.sumOf { entryDao.getEntries(it.id).size }
    }

    fun buildShareText(dashboard: RoomDashboard): String {
        val lines = mutableListOf<String>()
        lines += "SplitBill — ${dashboard.roomName}"
        lines += ""
        lines += "Balances:"
        dashboard.members.forEach { m ->
            val mark = if (m.isNextToRecharge) " ← recharge next" else ""
            lines += "• ${m.name}: ${m.balance}$mark"
        }
        if (dashboard.nextRechargeMemberName != null) {
            lines += ""
            lines += "Who should recharge next: ${dashboard.nextRechargeMemberName}"
        }
        if (dashboard.lastRechargeAmount > 0) {
            lines += "Last recharge: ${dashboard.currencySymbol}${
                "%.2f".format(dashboard.lastRechargeAmount)
            } by ${dashboard.lastRechargeMemberName ?: "—"}"
        }
        return lines.joinToString("\n")
    }
}
