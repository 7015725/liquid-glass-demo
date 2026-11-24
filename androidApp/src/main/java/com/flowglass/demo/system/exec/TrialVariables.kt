package com.flowglass.demo.system.exec

import com.flowglass.demo.ui.VariableOption
import com.flowglass.engine.ScriptVariableAccess
import com.flowglass.engine.VariableScope
import com.flowglass.engine.VariableValues

/**
 * The variable snapshot an editor trial run sees: saved locals and globals, no outputs or
 * builtins (those only exist while a rule runs). Writes stay in this map and are discarded with
 * the preview — a trial run must never persist anything.
 */
class TrialVariables private constructor(
    private val current: MutableMap<String, Any?>,
    private val scopes: Map<String, VariableScope>,
) : ScriptVariableAccess {
    override fun read(name: String): Any? = current[keyOf(name)]

    override fun write(name: String, value: Any?) {
        current[keyOf(name)] = value
    }

    override fun names(): Set<String> = current.keys.toSet()

    /** Map shaped like the interpreter's run scope, including the explicit prefixes. */
    fun forTemplate(): Map<String, Any?> = buildMap {
        current.forEach { (name, value) ->
            put(name, value)
            when (scopes[name]) {
                VariableScope.LOCAL -> put("rule.$name", value)
                VariableScope.GLOBAL -> put("global.$name", value)
                else -> Unit
            }
        }
    }

    private fun keyOf(name: String): String = when {
        name.startsWith("rule.") || name.startsWith("global.") || name.startsWith("run.") ->
            name.substringAfter('.')
        else -> name
    }

    companion object {
        fun from(options: List<VariableOption>): TrialVariables {
            val current = linkedMapOf<String, Any?>()
            val scopes = linkedMapOf<String, VariableScope>()
            for (option in options) {
                if (option.scope != VariableScope.LOCAL && option.scope != VariableScope.GLOBAL) continue
                val value = runCatching { VariableValues.parse(option.value, option.type) }.getOrNull()
                    ?: continue
                current[option.name] = value
                scopes[option.name] = option.scope
            }
            return TrialVariables(current, scopes)
        }
    }
}
