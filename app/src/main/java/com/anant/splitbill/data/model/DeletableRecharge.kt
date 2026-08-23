package com.anant.splitbill.data.model

import com.anant.splitbill.data.database.EntryEntity

/** A recharge log group that can be soft-deleted. */
data class DeletableRecharge(
    val groupId: String,
    val recharge: EntryEntity,
    val timestampEpochMs: Long,
)
