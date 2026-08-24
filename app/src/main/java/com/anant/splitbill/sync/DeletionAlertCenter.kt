package com.anant.splitbill.sync

import com.anant.splitbill.data.database.EntryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds recharge deletions discovered by cloud sync (app start / background worker)
 * that the UI hasn't shown an in-app dialog for yet.
 */
object DeletionAlertCenter {
    private val _pending = MutableStateFlow<List<EntryEntity>>(emptyList())
    val pending: StateFlow<List<EntryEntity>> = _pending.asStateFlow()

    fun post(entries: List<EntryEntity>) {
        if (entries.isEmpty()) return
        _pending.update { current -> current + entries.filter { e -> current.none { it.id == e.id } } }
    }

    fun consume() {
        _pending.value = emptyList()
    }
}
