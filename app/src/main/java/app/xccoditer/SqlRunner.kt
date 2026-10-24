package app.xccoditer

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal

/** Runs SQL on Android's real built-in SQLite engine, in a throwaway in-memory database. */
object SqlRunner {
    private const val MAX_ROWS = 500
    private const val QUERY_TIMEOUT_MS = 10_000L

    fun runToHtml(script: String): String {
        val db = SQLiteDatabase.create(null)
        try {
            val version = runCatching { DatabaseUtils.stringForQuery(db, "select sqlite_version()", null) }.getOrDefault("?")
            return SqlHtml.render(version, SqlSplit.split(script).map { run(db, it) })
        } finally {
            db.close()
        }
    }

    private fun run(db: SQLiteDatabase, stmt: String): SqlResult {
        return try {
            if (SqlSplit.returnsRows(stmt)) query(db, stmt)
            else {
                db.execSQL(stmt)
                val dml = stmt.trimStart().substringBefore(' ').lowercase() in setOf("insert", "update", "delete", "replace")
                val n = if (dml) DatabaseUtils.longForQuery(db, "select changes()", null) else -1L
                SqlResult.Ok(stmt, if (n >= 0) "OK, $n row(s) affected" else "OK")
            }
        } catch (e: android.os.OperationCanceledException) {
            SqlResult.Error(stmt, "Cancelled after ${QUERY_TIMEOUT_MS / 1000}s")
        } catch (e: Exception) {
            SqlResult.Error(stmt, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun query(db: SQLiteDatabase, stmt: String): SqlResult {
        val signal = CancellationSignal()
        val timer = Thread {
            try { Thread.sleep(QUERY_TIMEOUT_MS); signal.cancel() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }
        try {
            db.rawQuery(stmt, null, signal).use { c ->
                val cols = c.columnNames.toList()
                val rows = ArrayList<List<String>>()
                var truncated = false
                while (c.moveToNext()) {
                    if (rows.size >= MAX_ROWS) { truncated = true; break }
                    rows += cols.indices.map { i ->
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> "NULL"
                            Cursor.FIELD_TYPE_BLOB -> "<blob ${c.getBlob(i).size} bytes>"
                            else -> c.getString(i)
                        }
                    }
                }
                return SqlResult.Table(stmt, cols, rows, truncated)
            }
        } finally {
            timer.interrupt()
        }
    }
}
