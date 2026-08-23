package com.anant.splitbill.data.backup

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.UUID

object RoomSyncMeta {
    private val moshi = Moshi.Builder().build()
    private val deviceListType = Types.newParameterizedType(List::class.java, RoomDevice::class.java)
    private val auditListType = Types.newParameterizedType(List::class.java, AuditEvent::class.java)
    private val deviceAdapter = moshi.adapter<List<RoomDevice>>(deviceListType)
    private val auditAdapter = moshi.adapter<List<AuditEvent>>(auditListType)

    fun decodeDevices(json: String): List<RoomDevice> =
        runCatching { deviceAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())

    fun encodeDevices(devices: List<RoomDevice>): String =
        deviceAdapter.toJson(devices)

    fun decodeAudit(json: String): List<AuditEvent> =
        runCatching { auditAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())

    fun encodeAudit(events: List<AuditEvent>): String =
        auditAdapter.toJson(events.takeLast(BackupData.MAX_AUDIT_EVENTS))

    fun upsertDevice(
        existing: List<RoomDevice>,
        deviceId: String,
        deviceName: String,
        memberId: String?,
        memberName: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): List<RoomDevice> {
        val id = deviceId.trim()
        if (id.isBlank()) return existing
        val others = existing.filterNot { it.deviceId == id }
        return others + RoomDevice(
            deviceId = id,
            deviceName = deviceName.trim().take(128),
            memberId = memberId,
            memberName = memberName,
            lastSeenAtEpochMs = nowMs,
        )
    }

    fun appendAudit(
        existing: List<AuditEvent>,
        action: String,
        deviceId: String,
        deviceName: String,
        memberId: String?,
        memberName: String?,
        entryId: String? = null,
        groupId: String? = null,
        detail: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): List<AuditEvent> {
        val event = AuditEvent(
            id = UUID.randomUUID().toString(),
            action = action,
            atEpochMs = nowMs,
            deviceId = deviceId,
            deviceName = deviceName,
            memberId = memberId,
            memberName = memberName,
            entryId = entryId,
            groupId = groupId,
            detail = detail,
        )
        return (existing + event).takeLast(BackupData.MAX_AUDIT_EVENTS)
    }

    fun mergeDevices(local: List<RoomDevice>, remote: List<RoomDevice>): List<RoomDevice> {
        val byId = linkedMapOf<String, RoomDevice>()
        for (d in local + remote) {
            val prev = byId[d.deviceId]
            if (prev == null || d.lastSeenAtEpochMs >= prev.lastSeenAtEpochMs) {
                byId[d.deviceId] = d
            }
        }
        return byId.values.sortedByDescending { it.lastSeenAtEpochMs }
    }

    fun mergeAudit(local: List<AuditEvent>, remote: List<AuditEvent>): List<AuditEvent> {
        val byId = linkedMapOf<String, AuditEvent>()
        for (e in local + remote) byId[e.id] = e
        return byId.values.sortedBy { it.atEpochMs }.takeLast(BackupData.MAX_AUDIT_EVENTS)
    }
}
