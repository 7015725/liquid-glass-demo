package com.flowglass.demo.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the bridge from growing a write surface. This is intentionally a reflection test: adding
 * a `replaceResult`/`setArgument` method would be visible here even if no current caller uses it.
 */
class HookBridgeContractTest {
    @Test
    fun bridgeOnlyExposesReadAndObserveOperations() {
        val names = HookBridge::class.java.methods.map { it.name }.toSet()
        assertEquals(
            setOf("getApiLevel", "log", "findClass", "hookAllMethods", "hookMethod",
                "getObjectField", "callMethod", "callStaticMethod", "readPreferences"),
            names,
        )
        assertFalse(names.any { it.contains("replace", ignoreCase = true) })
        assertFalse(names.any { it.contains("setArg", ignoreCase = true) })
        assertFalse(names.any { it.contains("skip", ignoreCase = true) })
    }

    @Test
    fun callbackReceivesAThreePartObservation() {
        var seen: Triple<Any?, Array<Any?>, Any?>? = null
        val callback = HookCallback { thisObject, args, result ->
            seen = Triple(thisObject, args, result)
        }
        callback.afterCall("receiver", arrayOf("first"), "result")
        assertTrue(seen != null)
        assertEquals("receiver", seen!!.first)
        assertEquals("first", seen!!.second.single())
        assertEquals("result", seen!!.third)
    }
}
