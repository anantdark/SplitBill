package com.anant.splitbill.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anant.splitbill.data.model.EntryType
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
@Entity(tableName = "rooms")
data class RoomEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val currencySymbol: String = "Rs."
)

@JsonClass(generateAdapter = true)
@Entity(
    tableName = "members",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("roomId"), Index(value = ["roomId", "sortOrder"])]
)
data class MemberEntity(
    @PrimaryKey val id: String,
    val roomId: String,
    val name: String,
    val sortOrder: Int,
    val createdAtEpochMs: Long
)

@JsonClass(generateAdapter = true)
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("roomId"), Index("groupId"), Index("timestampEpochMs")]
)
data class EntryEntity(
    @PrimaryKey val id: String,
    val roomId: String,
    val type: EntryType,
    /** Member this row belongs to (reading / payer for recharge or expense). */
    val memberId: String?,
    val memberName: String,
    /** Meter reading (READING) or rupee amount (RECHARGE / EXPENSE). */
    val value: Double,
    /** Units consumed since previous reading (READING only). */
    val consumption: Double? = null,
    val note: String = "",
    val timestampEpochMs: Long,
    /** Groups READING+RECHARGE recorded together (and equal-split EXPENSE rows). */
    val groupId: String,
    /** Snapshot of all balances after this row, encoded as "Name: Rs.12.34; …". */
    val balancesSnapshot: String
)
