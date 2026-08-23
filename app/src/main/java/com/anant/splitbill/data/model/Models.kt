package com.anant.splitbill.data.model

enum class EntryType {
    /** Absolute meter reading for a member (port of READING). */
    READING,

    /** Prepaid bill top-up credited to one member (port of RECHARGE). */
    RECHARGE,

    /** Shared expense paid by one member, split equally. */
    EXPENSE
}

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

/** Snapshot used by the dashboard / widget. */
data class RoomDashboard(
    val roomId: String,
    val roomName: String,
    val currencySymbol: String,
    val members: List<MemberBalance>,
    val nextRechargeMemberId: String?,
    val nextRechargeMemberName: String?,
    val lastRechargeAmount: Double,
    val lastRechargeMemberName: String?,
    val entryCount: Int
)

data class MemberBalance(
    val memberId: String,
    val name: String,
    val balance: String,
    val balanceValue: Double,
    val lastReading: Double,
    val isNextToRecharge: Boolean
)
