package android.database.sqlite

import android.content.Context
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

class SQLiteException(message: String) : RuntimeException(message)

/** Delegates every statement/transaction to real file-backed SQLite, not an in-memory map. */
class SQLiteDatabase(private val connection: Connection) {
    private var successful = false
    fun beginTransaction() { check(connection.autoCommit); connection.autoCommit = false; successful = false }
    fun setTransactionSuccessful() { successful = true }
    fun endTransaction() {
        try { if (successful) connection.commit() else connection.rollback() }
        finally { connection.autoCommit = true }
    }
    fun execSQL(sql: String) { connection.createStatement().use { it.execute(sql) } }
    fun compileStatement(sql: String) = SQLiteStatement(connection.prepareStatement(sql))
    fun rawQuery(sql: String, args: Array<String>?): Cursor {
        val statement = connection.prepareStatement(sql)
        args?.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        return Cursor(statement, statement.executeQuery())
    }
}

class SQLiteStatement(private val statement: PreparedStatement) : AutoCloseable {
    fun clearBindings() = statement.clearParameters()
    fun bindString(index: Int, value: String) = statement.setString(index, value)
    fun bindBlob(index: Int, value: ByteArray) = statement.setBytes(index, value)
    fun executeInsert(): Long = statement.executeUpdate().toLong()
    fun executeUpdateDelete(): Int = statement.executeUpdate()
    override fun close() = statement.close()
}

class Cursor(private val statement: PreparedStatement, private val rows: ResultSet) : AutoCloseable {
    fun moveToNext(): Boolean = rows.next()
    fun getString(index: Int): String = rows.getString(index + 1)
    fun getBlob(index: Int): ByteArray = rows.getBytes(index + 1)
    override fun close() { rows.close(); statement.close() }
}

@Suppress("UNUSED_PARAMETER")
abstract class SQLiteOpenHelper(context: Context, name: String, factory: Any?, version: Int) {
    private val database by lazy {
        val file = java.io.File(context.filesDir, name)
        val existed = file.exists()
        SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")).also {
            if (!existed) onCreate(it)
        }
    }
    val readableDatabase: SQLiteDatabase get() = database
    val writableDatabase: SQLiteDatabase get() = database
    abstract fun onCreate(db: SQLiteDatabase)
    abstract fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int)
}
