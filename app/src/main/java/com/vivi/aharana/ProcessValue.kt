package com.vivi.aharana

/** Publish one process-owned value/start action, including under concurrent callers. */
internal class ProcessValue<T : Any> {
    @Volatile private var value: T? = null

    fun get(create: () -> T): T = value ?: synchronized(this) {
        value ?: create().also { value = it }
    }
}
