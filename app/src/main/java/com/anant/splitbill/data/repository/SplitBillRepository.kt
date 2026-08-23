package com.anant.splitbill.data.repository

import android.content.Context
import com.anant.splitbill.data.database.AppDatabase
import com.anant.splitbill.data.database.EntryEntity
import com.anant.splitbill.data.database.MemberEntity
import com.anant.splitbill.data.database.RoomEntity
import com.anant.splitbill.data.model.EntryType
import com.anant.splitbill.data.model.MemberBalance
import com.anant.splitbill.data.model.RoomDashboard
import com.anant.splitbill.ui.widget.BalanceWidgetReceiver
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
            val activeCount = entries.count { !it.deleted }
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
                entryCount = activeCount
            )
        }

    fun observeEntries(roomId: String): Flow<List<EntryEntity>> = entryDao.observeRecent(roomId, 300)

    suspend fun roomCount(): Int = withContext(Dispatchers.IO) { roomDao.count() }

    suspend fun getRoom(roomId: String): RoomEntity? =
        withContext(Dispatchers.IO) { roomDao.getRoom(roomId) }

    suspend fun getMembers(roomId: String): List<MemberEntity> =
        withContext(Dispatchers.IO) { memberDao.getMembers(roomId) }

    suspend fun getEntries(roomId: String): List<EntryEntity> =
        withContext(Dispatchers.IO) { entryDao.getEntries(roomId) }

    /**
     * Creates the household room. [roomId] should be the Support / Room ID so cloud sync
     * and join/restore share the same key.
     */
    suspend fun createRoom(
        name: String,
        memberNames: List<String>,
        roomId: String,
        currencySymbol: String = "Rs.",
    ): List<MemberEntity> = withContext(Dispatchers.IO) {
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
        val members = cleaned.mapIndexed { index, memberName ->
            MemberEntity(
                id = UUID.randomUUID().toString(),
                roomId = id,
                name = memberName,
                sortOrder = index,
                createdAtEpochMs = now
            )
        }
        memberDao.upsertAll(members)
        BalanceWidgetReceiver.requestUpdate(context)
        members
    }

    /**
     * Rewrites the primary room (and its members/entries) so room.id == [supportId].
     * Used when adopting Support ID as Room ID on existing installs or after join.
     */
    suspend fun alignPrimaryRoomId(supportId: String) = withContext(Dispatchers.IO) {
        val id = supportId.trim()
        if (id.isBlank()) return@withContext
        val (rooms, members, entries) = exportSnapshotUnlocked()
        if (rooms.isEmpty()) return@withContext
        if (rooms.any { it.id == id }) return@withContext
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
        replaceAllDataUnlocked(rewrittenRooms, rewrittenMembers, rewrittenEntries)
    }

    suspend fun addMember(roomId: String, name: String) = withContext(Dispatchers.IO) {
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
        rechargeAmount: Double,
        loggedByMemberId: String? = null,
        loggedByMemberName: String? = null,
    ) = withContext(Dispatchers.IO) {
        val members = memberDao.getMembers(roomId)
        val entries = entryDao.getEntries(roomId)
        val current = BillEngine.rebuild(members, entries)
        val result = BillEngine.recordReadingsAndRecharge(
            roomId = roomId,
            members = members,
            current = current,
            readings = readings,
            rechargeMemberId = rechargeMemberId,
            rechargeAmount = rechargeAmount,
            loggedByMemberId = loggedByMemberId,
            loggedByMemberName = loggedByMemberName,
        )
        entryDao.insertAll(result.entries)
        BalanceWidgetReceiver.requestUpdate(context)
    }

    suspend fun recordExpense(
        roomId: String,
        payerId: String,
        amount: Double,
        note: String,
        loggedByMemberId: String? = null,
        loggedByMemberName: String? = null,
    ) =
        withContext(Dispatchers.IO) {
            val members = memberDao.getMembers(roomId)
            val entries = entryDao.getEntries(roomId)
            val current = BillEngine.rebuild(members, entries)
            val result = BillEngine.recordExpense(
                roomId = roomId,
                members = members,
                current = current,
                payerId = payerId,
                amount = amount,
                note = note,
                loggedByMemberId = loggedByMemberId,
                loggedByMemberName = loggedByMemberName,
            )
            entryDao.insertAll(result.entries)
            BalanceWidgetReceiver.requestUpdate(context)
        }

    suspend fun revertLastGroup(roomId: String) = withContext(Dispatchers.IO) {
        val latest = entryDao.latestActive(roomId) ?: return@withContext
        entryDao.deleteGroup(latest.groupId)
        BalanceWidgetReceiver.requestUpdate(context)
    }

    /**
     * Soft-deletes a recharge group (readings + recharge). Keeps rows for audit;
     * balances recompute from remaining active entries.
     */
    suspend fun softDeleteRechargeGroup(
        roomId: String,
        groupId: String,
        deletedByMemberId: String?,
        deletedByMemberName: String?,
        deletedByDeviceId: String,
        deletedByDeviceName: String,
    ): List<EntryEntity> = withContext(Dispatchers.IO) {
        val group = entryDao.getGroup(roomId, groupId)
        require(group.isNotEmpty()) { "Log not found" }
        require(group.any { it.type == EntryType.RECHARGE && !it.deleted }) {
            "Only recharge logs can be deleted this way"
        }
        require(group.none { it.deleted }) { "Already deleted" }
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24L * 60L * 60L * 1000L
        val rechargeTs = group.filter { it.type == EntryType.RECHARGE }.maxOf { it.timestampEpochMs }
        val isLatest = entryDao.getActiveRecharges(roomId).firstOrNull()?.groupId == groupId
        require(isLatest) { "Only the latest recharge can be deleted" }
        require(rechargeTs >= weekAgo) {
            "The latest recharge is older than 7 days and can no longer be deleted"
        }
        val updated = group.map { entry ->
            entry.copy(
                deleted = true,
                deletedAtEpochMs = now,
                deletedByMemberId = deletedByMemberId,
                deletedByMemberName = deletedByMemberName,
                deletedByDeviceId = deletedByDeviceId,
                deletedByDeviceName = deletedByDeviceName,
            )
        }
        entryDao.insertAll(updated)
        BalanceWidgetReceiver.requestUpdate(context)
        updated
    }

    suspend fun listDeletableRecharges(roomId: String): List<com.anant.splitbill.data.model.DeletableRecharge> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val weekAgo = now - 7L * 24L * 60L * 60L * 1000L
            val latest = entryDao.getActiveRecharges(roomId).firstOrNull() ?: return@withContext emptyList()
            if (latest.timestampEpochMs < weekAgo) return@withContext emptyList()
            listOf(
                com.anant.splitbill.data.model.DeletableRecharge(
                    groupId = latest.groupId,
                    recharge = latest,
                    timestampEpochMs = latest.timestampEpochMs,
                )
            )
        }

    suspend fun replaceAllData(
        rooms: List<RoomEntity>,
        members: List<MemberEntity>,
        entries: List<EntryEntity>
    ) = withContext(Dispatchers.IO) {
        replaceAllDataUnlocked(rooms, members, entries)
    }

    suspend fun exportSnapshot(): Triple<List<RoomEntity>, List<MemberEntity>, List<EntryEntity>> =
        withContext(Dispatchers.IO) { exportSnapshotUnlocked() }

    suspend fun totalEntryCount(): Int = withContext(Dispatchers.IO) {
        val rooms = roomDao.observeRooms().first()
        rooms.sumOf { entryDao.getEntries(it.id).size }
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

    /** Caller must already be on [Dispatchers.IO]. */
    private suspend fun exportSnapshotUnlocked():
        Triple<List<RoomEntity>, List<MemberEntity>, List<EntryEntity>> {
        val rooms = roomDao.observeRooms().first()
        val members = rooms.flatMap { memberDao.getMembers(it.id) }
        val entries = rooms.flatMap { entryDao.getEntries(it.id) }
        return Triple(rooms, members, entries)
    }

    /** Caller must already be on [Dispatchers.IO]. */
    private suspend fun replaceAllDataUnlocked(
        rooms: List<RoomEntity>,
        members: List<MemberEntity>,
        entries: List<EntryEntity>
    ) {
        // clearAllTables() is blocking and forbids the main thread.
        db.clearAllTables()
        rooms.forEach { roomDao.upsert(it) }
        if (members.isNotEmpty()) memberDao.upsertAll(members)
        if (entries.isNotEmpty()) entryDao.insertAll(entries)
        BalanceWidgetReceiver.requestUpdate(context)
    }
}
