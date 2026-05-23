package com.blindfoldchess.app.service

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class KeyEventEntry(
    val id: Long,
    val timestamp: Long,
    val action: Int,
    val keyCode: Int,
    val repeatCount: Int,
)

object KeyEventLog {
    private const val MAX_ENTRIES = 500

    private val nextId = AtomicLong(0L)
    private val _entries = MutableStateFlow<List<KeyEventEntry>>(emptyList())
    val entries: StateFlow<List<KeyEventEntry>> = _entries.asStateFlow()

    fun record(event: KeyEvent) {
        val entry = KeyEventEntry(
            id = nextId.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            action = event.action,
            keyCode = event.keyCode,
            repeatCount = event.repeatCount,
        )
        _entries.update { current -> (listOf(entry) + current).take(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
