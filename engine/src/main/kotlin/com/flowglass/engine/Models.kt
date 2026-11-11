package com.flowglass.engine

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class Rule(
    val id: String = UUID.randomUUID().toString(), val name: String = "",
    val description: String = "", val type: String = "auto", val enabled: Boolean = false,
    val runMode: String = "drop", val triggerMatch: MatchSpec = MatchSpec(),
    val triggers: List<Component> = emptyList(), val conditionMatch: MatchSpec = MatchSpec(),
    val conditions: List<Component> = emptyList(), val locals: List<Variable> = emptyList(),
    val actions: List<Component> = emptyList(), val pinned: Boolean = false,
    val schemaVersion: Int = 1, val debounceMillis: Long = 0
)
@Serializable data class Component(
    val id: String = UUID.randomUUID().toString(), val type: String,
    @Serializable(with = LosslessParamsSerializer::class) val params: Map<String, JsonElement> = emptyMap(), val timeoutSeconds: Int = 30,
    val onError: String = "stop", val retryCount: Int = 0,
    val children: List<Component> = emptyList(), val elseActions: List<Component> = emptyList()
)
@Serializable data class MatchSpec(val mode: String = "any", val windowSeconds: Int = 60,
    val atLeast: Int? = null, val tree: MatchNode? = null)
@Serializable data class MatchNode(val op: String = "leaf", val ref: String? = null,
    val children: List<MatchNode> = emptyList())
@Serializable data class Variable(val name: String, val type: String = "string",
    @Serializable(with = LosslessJsonElementSerializer::class) val initial: JsonElement = JsonPrimitive(""), val resetEachRun: Boolean = false)
enum class Kind { TRIGGER, CONDITION, ACTION }
data class ParamSpec(val key: String, val label: String, val kind: String = "text",
    val required: Boolean = true, val defaultValue: JsonElement = JsonPrimitive(""),
    val choices: List<String> = emptyList(), val hint: String = "")
data class ComponentMeta(val typeId: String, val name: String, val category: String,
    val kind: Kind, val params: List<ParamSpec> = emptyList(), val permissions: List<String> = emptyList(),
    val minTier: String = "NORMAL", val outputs: List<String> = emptyList(), val description: String = "")
data class ValidationIssue(val path: String, val message: String)
@Serializable data class RuleBundle(val formatVersion: Int = 1, val rules: List<Rule>,
    val globals: List<Variable> = emptyList())
