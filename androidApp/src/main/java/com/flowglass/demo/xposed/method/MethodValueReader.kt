package com.flowglass.demo.xposed.method

import java.lang.reflect.Field

/**
 * Declarative field-path reader. It deliberately has no method invocation or script hook: a
 * configured path can only walk fields and then call `toString()` on the final value. A missing
 * field, null intermediate, access failure or throwing toString all yield null; the hook callback
 * must not fail because extraction failed.
 */
internal object MethodValueReader {
    const val MAX_TEXT = 512

    fun read(value: Any?, path: String): String? {
        var current: Any? = value ?: return null
        val segments = path.trim().takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
        for (segment in segments) {
            val field = findField(current!!.javaClass, segment) ?: return null
            current = runCatching {
                field.isAccessible = true
                field.get(current)
            }.getOrNull() ?: return null
        }
        val text = runCatching { current.toString() }.getOrNull() ?: return null
        return text.take(MAX_TEXT)
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }
}
