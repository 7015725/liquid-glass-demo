package com.flowglass.demo.system.trigger

import com.flowglass.demo.system.SystemHookSettings
import com.flowglass.demo.xposed.SystemHookFamily
import com.flowglass.engine.trigger.TriggerRule
import com.flowglass.engine.trigger.TriggerTypes

/**
 * Translates the enabled automatic rules into the smallest hook subscription. A family is only
 * requested while a rule listens to it; process/crash additionally send a package allowlist so
 * system-server does not have to build events for unrelated apps.
 */
internal object SystemHookSubscriptions {
    fun requiredFamilies(rules: List<TriggerRule>): Int =
        rules.asSequence()
            .filter { it.enabled }
            .flatMap { it.triggers.asSequence() }
            .mapNotNull { familyOf(it.typeId) }
            .fold(0) { mask, family -> mask or family.mask }

    fun watchedPackages(rules: List<TriggerRule>): Set<String> =
        SystemHookSettings.normalizeWatched(
            rules.asSequence()
                .filter { it.enabled }
                .flatMap { it.triggers.asSequence() }
                .filter {
                    it.typeId == TriggerTypes.APP_PROCESS ||
                        it.typeId == TriggerTypes.APP_CRASH ||
                        it.typeId == TriggerTypes.MEDIA ||
                        it.typeId == TriggerTypes.FOREGROUND_SERVICE
                }
                .mapNotNull { it.params["package"]?.toString()?.trim('"') }
                .filter { it.isNotBlank() }
                .toList()
        )

    private fun familyOf(typeId: String): SystemHookFamily? = when (typeId) {
        TriggerTypes.APP_FOREGROUND -> SystemHookFamily.FOREGROUND
        TriggerTypes.APP_PROCESS -> SystemHookFamily.PROCESS
        TriggerTypes.APP_CRASH -> SystemHookFamily.CRASH
        TriggerTypes.KEYGUARD -> SystemHookFamily.KEYGUARD
        TriggerTypes.MEDIA -> SystemHookFamily.MEDIA
        TriggerTypes.FOREGROUND_SERVICE -> SystemHookFamily.FOREGROUND_SERVICE
        TriggerTypes.CLIPBOARD_CHANGED -> SystemHookFamily.CLIPBOARD
        else -> null
    }
}
