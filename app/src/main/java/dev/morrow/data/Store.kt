package dev.morrow.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite, no ORM: three small tables, no annotation processing, and the
 * whole file stays readable. The repository keeps the in-memory copy.
 */
class Store(context: Context) : SQLiteOpenHelper(context, "morrow.db", null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(itemTable("items"))
        db.execSQL("CREATE INDEX items_by_start ON items(start)")
        db.execSQL("CREATE TABLE memories(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE turns(id INTEGER PRIMARY KEY AUTOINCREMENT, user TEXT NOT NULL, reply TEXT NOT NULL, engine TEXT NOT NULL, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2 dropped the unused `notes` column. SQLite's DROP COLUMN needs
        // Android 14, so rebuild the table and copy everything across.
        if (oldVersion < 2) {
            db.execSQL(itemTable("items_v2"))
            db.execSQL(
                """
                INSERT INTO items_v2(id, kind, title, start, end, done, done_at, calendar_id, reminder_at, engine, created_at)
                SELECT id, kind, title, start, end, done, done_at, calendar_id, reminder_at, engine, created_at FROM items
                """.trimIndent()
            )
            db.execSQL("DROP TABLE items")
            db.execSQL("ALTER TABLE items_v2 RENAME TO items")
            db.execSQL("CREATE INDEX items_by_start ON items(start)")
        } else if (oldVersion < 3) {
            // v1 rebuilt above already carries these columns.
            db.execSQL("ALTER TABLE items ADD COLUMN rrule TEXT")
            db.execSQL("ALTER TABLE items ADD COLUMN series_id INTEGER")
        }
    }

    fun readItems(): List<Item> = readableDatabase.query(
        "items", null, null, null, null, null, "done ASC, start ASC, id ASC"
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Item(
                        id = c.getLong(0),
                        kind = if (c.getString(1) == "EVENT") Kind.EVENT else Kind.TASK,
                        title = c.getString(2),
                        start = c.getLong(3),
                        end = if (c.isNull(4)) null else c.getLong(4),
                        done = c.getInt(5) != 0,
                        doneAt = if (c.isNull(6)) null else c.getLong(6),
                        calendarEventId = if (c.isNull(7)) null else c.getLong(7),
                        reminderAt = if (c.isNull(8)) null else c.getLong(8),
                        engine = c.getString(9),
                        createdAt = c.getLong(10),
                        rrule = if (c.isNull(11)) null else c.getString(11),
                        seriesId = if (c.isNull(12)) null else c.getLong(12),
                    )
                )
            }
        }
    }

    fun readMemories(): List<Memory> = readableDatabase.query(
        "memories", null, null, null, null, null, "created_at DESC"
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Memory(c.getLong(0), c.getString(1), c.getLong(2)))
        }
    }

    fun readTurns(limit: Int = 60): List<Turn> = readableDatabase.query(
        "turns", null, null, null, null, null, "at DESC", limit.toString()
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Turn(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
        }.reversed()
    }

    fun insertItem(item: Item): Long = writableDatabase.insert("items", null, item.toValues())

    fun updateItem(item: Item) {
        writableDatabase.update("items", item.toValues(), "id = ?", arrayOf(item.id.toString()))
    }

    fun deleteItem(id: Long) {
        writableDatabase.delete("items", "id = ?", arrayOf(id.toString()))
    }

    fun insertMemory(text: String, now: Long): Long = writableDatabase.insert(
        "memories", null, ContentValues().apply {
            put("text", text)
            put("created_at", now)
        }
    )

    fun deleteMemory(id: Long) {
        writableDatabase.delete("memories", "id = ?", arrayOf(id.toString()))
    }

    fun clearTurns() {
        writableDatabase.delete("turns", null, null)
    }

    fun insertTurn(user: String, reply: String, engine: String, now: Long) {
        writableDatabase.insert("turns", null, ContentValues().apply {
            put("user", user)
            put("reply", reply)
            put("engine", engine)
            put("at", now)
        })
        // Keep the transcript bounded; older turns are not needed by the model.
        writableDatabase.execSQL("DELETE FROM turns WHERE id NOT IN (SELECT id FROM turns ORDER BY id DESC LIMIT 60)")
    }

    private companion object {
        const val DATABASE_VERSION = 3
        fun itemTable(name: String) =
            "CREATE TABLE $name(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, title TEXT NOT NULL, " +
                "start INTEGER NOT NULL DEFAULT 0, end INTEGER, done INTEGER NOT NULL DEFAULT 0, done_at INTEGER, " +
                "calendar_id INTEGER, reminder_at INTEGER, engine TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "rrule TEXT, series_id INTEGER)"
    }

    private fun Item.toValues() = ContentValues().apply {
        put("kind", kind.name)
        put("title", title)
        put("start", start)
        if (end == null) putNull("end") else put("end", end)
        put("done", if (done) 1 else 0)
        if (doneAt == null) putNull("done_at") else put("done_at", doneAt)
        if (calendarEventId == null) putNull("calendar_id") else put("calendar_id", calendarEventId)
        if (reminderAt == null) putNull("reminder_at") else put("reminder_at", reminderAt)
        put("engine", engine)
        put("created_at", createdAt)
        if (rrule == null) putNull("rrule") else put("rrule", rrule)
        if (seriesId == null) putNull("series_id") else put("series_id", seriesId)
    }
}
