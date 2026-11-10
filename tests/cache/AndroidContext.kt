package android.content

import java.io.File

/** Minimal Android boundary for executing the real cache wrapper on host SQLite/JDBC. */
open class Context(val filesDir: File) {
    val applicationContext: Context get() = this
}
