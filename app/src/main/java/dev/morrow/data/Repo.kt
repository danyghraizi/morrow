package dev.morrow.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-memory source of truth for the UI, written through to SQLite.
 *
 * The dataset is a personal calendar: hundreds of rows at most, so a full
 * re-read after every mutation is cheaper (and far simpler) than diffing.
 */
class Repo(private val store: Store) {
    sealed interface Undo {
        data class Added(val id: Long) : Undo
        data class Completion(val id: Long) : Undo
        data class Moved(val id: Long, val previousStart: Long, val previousReminder: Long?) : Undo
        data class SeriesAdded(val ids: List<Long>) : Undo
    }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _undo = MutableStateFlow<Undo?>(null)
    val undo: StateFlow<Undo?> = _undo.asStateFlow()

    private val mutex = Mutex()

    suspend fun load() = mutex.withLock {
        // Serialised: the app start and the boot receiver can both call this, and
        // two concurrent top-ups would double-add occurrences.
        withContext(Dispatchers.IO) {
            topUpSeries()
            val items = store.readItems()
            val memories = store.readMemories()
            val turns = store.readTurns()
            _snapshot.value = Snapshot(items, memories, turns)
        }
    }

    private fun topUpSeries() {
        val now = java.time.ZonedDateTime.now()
        val grouped = store.readItems().filter { it.rrule != null }
            .groupBy { it.seriesId ?: it.id }
        grouped.values.forEach { series ->
            dev.morrow.domain.Recurrence.missingOccurrences(series, now).forEach { store.insertItem(it) }
        }
    }

    private suspend fun refresh() = withContext(Dispatchers.IO) {
        _snapshot.value = Snapshot(
            items = store.readItems(),
            memories = store.readMemories(),
            turns = store.readTurns(),
        )
    }

    suspend fun add(item: Item): Item = mutex.withLock {
        val id = withContext(Dispatchers.IO) { store.insertItem(item) }
        val saved = item.copy(id = id)
        _undo.value = Undo.Added(id)
        refresh()
        saved
    }

    /**
     * Inserts the materialised occurrences of a repeating item. The first row is
     * written alone so its id can group the rest under the same series.
     */
    suspend fun addSeries(rows: List<Item>): List<Item> = mutex.withLock {
        if (rows.isEmpty()) return@withLock emptyList()
        val ids = withContext(Dispatchers.IO) {
            val first = store.insertItem(rows.first())
            val seriesId = first
            val rest = rows.drop(1).map { it.copy(seriesId = seriesId) }
            val otherIds = rest.map { store.insertItem(it) }
            listOf(first) + otherIds
        }
        withContext(Dispatchers.IO) { store.updateItem(rows.first().copy(id = ids.first(), seriesId = ids.first())) }
        _undo.value = Undo.SeriesAdded(ids)
        refresh()
        ids.mapIndexed { index, id -> rows[index].copy(id = id, seriesId = ids.first()) }
    }

    suspend fun update(item: Item) = mutex.withLock {
        withContext(Dispatchers.IO) { store.updateItem(item) }
        refresh()
    }

    suspend fun setDone(id: Long, done: Boolean, at: Long = System.currentTimeMillis()) = mutex.withLock {
        if (id <= 0L) return@withLock // rows mirrored from the system calendar are read-only
        val item = _snapshot.value.items.firstOrNull { it.id == id } ?: return@withLock
        withContext(Dispatchers.IO) {
            store.updateItem(item.copy(done = done, doneAt = if (done) at else null))
        }
        if (done) _undo.value = Undo.Completion(id)
        refresh()
    }

    suspend fun move(id: Long, start: Long, reminderAt: Long?) = mutex.withLock {
        if (id <= 0L) return@withLock
        val item = _snapshot.value.items.firstOrNull { it.id == id } ?: return@withLock
        val previous = Undo.Moved(id, item.start, item.reminderAt)
        val end = if (item.isEvent) start + ((item.end ?: start) - item.start).coerceAtLeast(15 * 60_000L) else null
        withContext(Dispatchers.IO) { store.updateItem(item.copy(start = start, end = end, reminderAt = reminderAt)) }
        _undo.value = previous
        refresh()
    }

    suspend fun delete(id: Long) = mutex.withLock {
        if (id <= 0L) return@withLock
        withContext(Dispatchers.IO) { store.deleteItem(id) }
        refresh()
    }

    suspend fun remember(text: String, now: Long = System.currentTimeMillis()) = mutex.withLock {
        withContext(Dispatchers.IO) { store.insertMemory(text, now) }
        refresh()
    }

    suspend fun forget(id: Long) = mutex.withLock {
        withContext(Dispatchers.IO) { store.deleteMemory(id) }
        refresh()
    }

    suspend fun logTurn(user: String, reply: String, engine: String, now: Long = System.currentTimeMillis()) =
        mutex.withLock {
            withContext(Dispatchers.IO) { store.insertTurn(user, reply, engine, now) }
            refresh()
        }

    suspend fun clearHistory() = mutex.withLock {
        withContext(Dispatchers.IO) { store.clearTurns() }
        refresh()
    }

    /** Reverts the last mutation and returns true when something was undone. */
    suspend fun undoLast(): Boolean {
        val action = _undo.value ?: return false
        _undo.value = null
        when (action) {
            is Undo.Added -> delete(action.id)
            is Undo.Completion -> setDone(action.id, false)
            is Undo.SeriesAdded -> action.ids.forEach { delete(it) }
            is Undo.Moved -> {
                val item = _snapshot.value.items.firstOrNull { it.id == action.id }
                if (item != null) update(item.copy(start = action.previousStart, reminderAt = action.previousReminder))
            }
        }
        return true
    }

    fun itemById(id: Long): Item? = _snapshot.value.items.firstOrNull { it.id == id }
}
