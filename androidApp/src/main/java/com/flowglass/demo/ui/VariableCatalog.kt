package com.flowglass.demo.ui

import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.BuiltInVariables
import com.flowglass.engine.ParameterCodec
import com.flowglass.engine.VariableName
import com.flowglass.engine.VariableScope
import com.flowglass.engine.VariableType
import com.flowglass.engine.methodhook.AppMethodCall
import com.flowglass.engine.methodhook.MethodExtraction
import com.flowglass.engine.methodhook.MethodExtractionSource
import com.flowglass.engine.methodhook.MethodHookNames

/**
 * One row offered by the variable picker and the management page.
 */
data class VariableOption(
    val name: String,
    val scope: VariableScope,
    val type: VariableType,
    val note: String,
    val value: String,
    val references: Int,
    val ownerId: Long? = null,
    val ownerTitle: String = "",
    val legacyKey: String? = null,
)

object VariableCatalog {
    fun forEditor(state: AppState, cmdId: Long): List<VariableOption> = buildList {
        addAll(builtins(state))
        state.localsOf(cmdId).forEach { local ->
            add(
                VariableOption(
                    name = local.name,
                    scope = VariableScope.LOCAL,
                    type = typeOf(local.type),
                    note = local.note,
                    value = displayValue(local.initial, typeOf(local.type)),
                    references = references(state, local.name, VariableScope.LOCAL, null),
                    ownerId = cmdId,
                    ownerTitle = state.find(cmdId)?.title.orEmpty(),
                ),
            )
        }
        addAll(globals(state))
    }.distinctBy { it.name to it.scope to it.ownerId }

    fun forManager(state: AppState): List<VariableOption> = buildList {
        addAll(builtins(state))
        state.engineRules.forEach { rule ->
            val owner = state.allCommands().firstOrNull { state.ruleIdFor(it.id) == rule.id }
            rule.locals.forEach { local ->
                add(
                    VariableOption(
                        name = local.name,
                        scope = VariableScope.LOCAL,
                        type = typeOf(local.type),
                        note = local.note,
                        value = displayValue(local.initial, typeOf(local.type)),
                        references = references(state, local.name, VariableScope.LOCAL, owner?.id),
                        ownerId = owner?.id,
                        ownerTitle = owner?.title.orEmpty(),
                    ),
                )
            }
        }
        addAll(globals(state))
    }.distinctBy { it.name to it.scope to it.ownerId }

    fun validateName(
        state: AppState,
        name: String,
        ownerId: Long?,
        scope: VariableScope,
        ignoreOwner: Long? = null,
        ignoreName: String? = null,
    ): String? {
        VariableName.error(name)?.let { return it }
        if (BuiltInVariables.byName.containsKey(name)) return "名字已被占用：$name 是内置变量"
        if (methodCallExtractions(state).any { it.extraction.name == name }) {
            return "名字已被占用：$name 是应用方法调用提供的内置变量"
        }
        val clash = forManager(state).firstOrNull {
            it.name == name && it.scope != VariableScope.BUILTIN &&
                !(it.name == ignoreName && it.scope == scope && it.ownerId == ignoreOwner)
        }
        return if (clash != null) "名字已被占用：$name" else null
    }

    fun references(state: AppState, name: String, scope: VariableScope, ownerId: Long?): Int {
        val tokens = listOf("{${name}}")
        return state.allCommands().sumOf { cmd ->
            if (scope == VariableScope.LOCAL && ownerId != null && cmd.id != ownerId) 0
            else (cmd.triggers + cmd.conditions + cmd.actions).allItems().sumOf { item ->
                tokens.count { item.parameter.contains(it) }
            }
        }
    }

    fun typeLabel(type: VariableType): String = when (type) {
        VariableType.INT -> "整数"
        VariableType.BOOLEAN -> "真假"
        VariableType.TEXT -> "文本"
        VariableType.INT_LIST -> "整数列表"
        VariableType.BOOLEAN_LIST -> "真假列表"
        VariableType.TEXT_LIST -> "文本列表"
    }

    fun scopeLabel(scope: VariableScope): String = when (scope) {
        VariableScope.BUILTIN -> "内置"
        VariableScope.LOCAL -> "局部"
        VariableScope.GLOBAL -> "全局"
    }

    fun token(name: String): String = "{$name}"

    private fun builtins(state: AppState): List<VariableOption> =
        BuiltInVariables.all.map { spec ->
            VariableOption(
                name = spec.name,
                scope = VariableScope.BUILTIN,
                type = spec.type,
                note = spec.description,
                value = "运行时提供",
                references = references(state, spec.name, VariableScope.BUILTIN, null),
                legacyKey = spec.legacyKey,
            )
        } + methodCallExtractions(state).map { (owner, extraction) ->
            val name = MethodHookNames.normalizeExtractionName(extraction.name)
            val where = when (extraction.source) {
                MethodExtractionSource.ARGUMENT -> "参数 ${extraction.argIndex ?: "?"}"
                MethodExtractionSource.RESULT -> "返回值"
            }
            val path = extraction.path.trim()
            VariableOption(
                name = name,
                scope = VariableScope.BUILTIN,
                type = VariableType.TEXT,
                note = buildString {
                    append("应用方法调用「$owner」")
                    append("：$where")
                    if (path.isNotEmpty()) append(" · $path")
                },
                value = "运行时提供",
                references = references(state, name, VariableScope.BUILTIN, null),
                ownerTitle = owner,
            )
        }

    private data class MethodCallExtraction(
        val owner: String,
        val extraction: MethodExtraction,
    )

    private fun methodCallExtractions(state: AppState): List<MethodCallExtraction> {
        val meta = BuiltInComponents.registry().find(AppMethodCall.TYPE_ID) ?: return emptyList()
        return state.allCommands()
            .filter { it.enabled && it.auto }
            .flatMap { command ->
                command.triggers
                    .filter { it.typeId == AppMethodCall.TYPE_ID }
                    .flatMap { trigger ->
                        runCatching { ParameterCodec.parseComponent(meta, trigger.parameter) }
                            .getOrNull()
                            ?.let { params ->
                                AppMethodCall.decodeExtractions(
                                    (params[AppMethodCall.KEY_EXTRACTIONS]
                                        as? kotlinx.serialization.json.JsonPrimitive)
                                        ?.takeIf { it.isString }?.content.orEmpty(),
                                )
                            }
                            .orEmpty()
                            .map { extraction ->
                                MethodCallExtraction(
                                    owner = command.title,
                                    extraction = extraction,
                                )
                            }
                    }
            }
    }

    private fun globals(state: AppState): List<VariableOption> = state.engineGlobals.map { (name, value) ->
        val meta = state.engineGlobalMeta[name]
        VariableOption(
            name = name,
            scope = VariableScope.GLOBAL,
            type = typeOf(meta?.type),
            note = meta?.note.orEmpty(),
            value = displayValue(value, typeOf(meta?.type)),
            references = references(state, name, VariableScope.GLOBAL, null),
        )
    }

    private fun typeOf(raw: String?): VariableType =
        VariableType.fromStorage(raw) ?: VariableType.TEXT

    private fun displayValue(value: kotlinx.serialization.json.JsonElement, type: VariableType): String =
        runCatching {
            com.flowglass.engine.VariableValues.uiText(
                com.flowglass.engine.VariableValues.fromJson(value, type),
            )
        }.getOrElse { value.toString().removeSurrounding("\"") }
}
