package com.flowglass.demo.xposed.method

/** In-memory per-process install state shown by the settings page in PR-5. */
internal object MethodHookStatusRegistry {
    data class Entry(
        val hookId: String,
        val packageName: String,
        val userId: Int,
        val processName: String,
        val status: String,
        val detail: String?,
        val updatedAt: Long,
    )

    private val entries = linkedMapOf<String, Entry>()

    @Synchronized
    fun update(entry: Entry) {
        val key = "${entry.hookId}|${entry.packageName}|${entry.userId}|${entry.processName}"
        entries[key] = entry
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.values.toList()

    @Synchronized
    fun clear() = entries.clear()
}
