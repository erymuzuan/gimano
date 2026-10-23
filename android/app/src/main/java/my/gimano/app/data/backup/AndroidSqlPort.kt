package my.gimano.app.data.backup

import androidx.sqlite.db.SupportSQLiteDatabase

class AndroidSqlPort(private val db: SupportSQLiteDatabase) : SqlPort {
    override fun exec(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
    }

    override fun queryLong(sql: String, vararg args: Any?): Long =
        db.query(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    override fun queryStrings(sql: String, vararg args: Any?): List<String> =
        db.query(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) ?: "") } }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }
}
