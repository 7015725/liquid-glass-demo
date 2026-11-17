package com.flowglass.demo.system.trigger

import com.flowglass.demo.xposed.SystemHookFamily
import com.flowglass.engine.trigger.TriggerComponent
import com.flowglass.engine.trigger.TriggerRule
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemHookSubscriptionsTest {
    private fun rule(id: String, vararg triggers: Pair<String, Map<String, String>>) = TriggerRule(
        id = id,
        cmdId = id.hashCode().toLong(),
        name = id,
        enabled = true,
        triggers = triggers.map { (type, params) ->
            TriggerComponent(type, type.substringAfterLast('.'), params.mapValues { JsonPrimitive(it.value) })
        },
    )

    @Test
    fun onlyFamiliesUsedByEnabledRulesAreRequested() {
        val rules = listOf(
            rule("foreground", "trigger.app_foreground" to mapOf("state" to "foreground", "package" to "com.a")),
            rule("process", "trigger.app_process" to mapOf("event" to "started", "package" to "com.b@10")),
            rule("crash", "trigger.app_crash" to mapOf("event" to "crash", "package" to "com.c")),
        )
        assertEquals(
            SystemHookFamily.FOREGROUND.mask or SystemHookFamily.PROCESS.mask or SystemHookFamily.CRASH.mask,
            SystemHookSubscriptions.requiredFamilies(rules),
        )
        assertEquals(
            setOf("com.b@10", "com.c"),
            SystemHookSubscriptions.watchedPackages(rules),
        )
        assertEquals(
            SystemHookFamily.FOREGROUND.mask,
            SystemHookSubscriptions.requiredFamilies(
                rules.map { if (it.id == "process" || it.id == "crash") it.copy(enabled = false) else it },
            ),
        )
    }

    @Test
    fun keyguardUsesTheKeyguardFamilyWithoutAPackageAllowlist() {
        val rules = listOf(rule("keyguard", "trigger.keyguard" to mapOf("state" to "locked")))
        assertEquals(SystemHookFamily.KEYGUARD.mask, SystemHookSubscriptions.requiredFamilies(rules))
        assertEquals(emptySet<String>(), SystemHookSubscriptions.watchedPackages(rules))
    }

    @Test
    fun emptyRulesRequestNothingAndInvalidPackagesAreRejected() {
        assertEquals(0, SystemHookSubscriptions.requiredFamilies(emptyList()))
        assertEquals(emptySet<String>(), SystemHookSubscriptions.watchedPackages(emptyList()))
        assertEquals(
            emptySet<String>(),
            SystemHookSubscriptions.watchedPackages(
                listOf(rule("bad", "trigger.app_process" to mapOf("event" to "started", "package" to "not a package"))),
            ),
        )
    }
}
