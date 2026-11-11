package com.flowglass.engine

import kotlinx.serialization.json.*

object RuleValidator {
    fun validate(rule: Rule): List<ValidationIssue> = buildList {
        fun error(path: String, message: String) { add(ValidationIssue(path, message)) }
        val structural = EngineLimits.structure(rule)
        if (structural.isNotEmpty()) { addAll(structural); return@buildList }
        if (rule.locals.size > EngineLimits.MAX_VARIABLES) { error("locals", "局部变量数量超过 ${EngineLimits.MAX_VARIABLES}"); return@buildList }
        if (rule.id.isBlank()) error("id", "规则 ID 不能为空")
        if (rule.name.isBlank()) error("name", "名称不能为空")
        if (rule.schemaVersion != 1) error("schemaVersion", "未知规则版本：${rule.schemaVersion}")
        if (rule.type !in setOf("auto", "manual")) error("type", "规则类型必须为 auto 或 manual")
        if (rule.runMode !in setOf("drop", "queue", "restart", "parallel")) error("runMode", "未知重复触发策略")
        if (rule.debounceMillis < 0) error("debounceMillis", "防抖毫秒不能为负数")
        if (rule.actions.isEmpty()) error("actions", "至少添加一个动作")
        if (rule.type == "auto" && rule.triggers.isEmpty()) error("triggers", "自动指令至少需要一个触发器")
        if (rule.type == "manual") {
            if (rule.triggers.isNotEmpty()) error("triggers", "一键指令不能包含触发器")
            if (rule.conditions.isNotEmpty()) error("conditions", "一键指令不能包含条件")
        }
        val localNames = mutableSetOf<String>()
        rule.locals.forEachIndexed { index, variable ->
            if (!localNames.add(variable.name)) error("locals[$index].name", "局部变量重名：${variable.name}")
            validateVariable(variable).forEach { error("locals[$index].${it.path}", it.message) }
        }
        validateMatch(rule.triggerMatch, rule.triggers.map { it.id }.toSet()).forEach { error("triggerMatch.${it.path}", it.message) }
        validateMatch(rule.conditionMatch, rule.conditions.map { it.id }.toSet()).forEach { error("conditionMatch.${it.path}", it.message) }
        val ids = mutableSetOf<String>()
        fun components(list: List<Component>, kind: Kind, path: String) {
            list.forEachIndexed { index, c ->
                val p = "$path[$index]"
                if (c.id.isBlank() || !ids.add(c.id)) error("$p.id", "组件 ID 为空或重复：${c.id}")
                val meta = ComponentRegistry.find(c.type)
                if (meta == null || meta.kind != kind) error("$p.type", "未知组件或组件放置类型错误：${c.type}")
                if (meta != null) validateParams(c, meta, rule.locals).forEach { error("$p.params.${it.path}", it.message) }
                if (c.timeoutSeconds <= 0) error("$p.timeoutSeconds", "超时秒数必须大于0")
                if (c.onError !in setOf("stop", "continue", "retry")) error("$p.onError", "未知失败策略")
                if (c.retryCount < 0 || (c.onError == "retry" && c.retryCount == 0)) error("$p.retryCount", "重试策略需正重试次数，次数不能为负")
                if (c.children.isNotEmpty() && c.type !in setOf("action.if", "action.loop", "action.list_foreach")) error("$p.children", "此组件不支持子动作")
                if (c.elseActions.isNotEmpty() && c.type != "action.if") error("$p.elseActions", "只有 if 支持否则分支")
                components(c.children, Kind.ACTION, "$p.children")
                components(c.elseActions, Kind.ACTION, "$p.elseActions")
            }
        }
        components(rule.triggers, Kind.TRIGGER, "triggers")
        components(rule.conditions, Kind.CONDITION, "conditions")
        components(rule.actions, Kind.ACTION, "actions")
        if (isEmpty()) {
            try { RuleCodec.encodeRule(rule) }
            catch (e: IllegalArgumentException) { error("rule", e.message ?: "规则大小或结构超过上限") }
        }
    }
    private fun validateMatch(spec: MatchSpec, ids: Set<String>): List<ValidationIssue> = buildList {
        fun error(path: String, message: String) { add(ValidationIssue(path, message)) }
        if (spec.mode !in setOf("any", "all", "custom")) error("mode", "未知匹配模式")
        if (spec.windowSeconds <= 0) error("windowSeconds", "事件窗口必须大于0秒")
        if (spec.mode != "custom" && (spec.atLeast != null || spec.tree != null)) error("mode", "非自定义模式不能保留阈值或匹配树")
        if (spec.mode == "custom") {
            if ((spec.atLeast == null) == (spec.tree == null)) error("mode", "自定义模式需且只能选择至少N项或匹配树之一")
            if (spec.atLeast != null && spec.atLeast !in 1..ids.size) error("atLeast", "阈值应在1到组件数量之间")
        }
        var count = 0
        fun node(n: MatchNode, p: String, depth: Int = 1) {
            if (++count > EngineLimits.MAX_MATCH_NODES || depth > EngineLimits.MAX_DEPTH) {
                error(p, "匹配树节点或嵌套深度超过上限"); return
            }
            when (n.op) {
                "leaf" -> {
                    if (n.ref == null || n.ref !in ids) error("$p.ref", "引用的组件不存在（使用组件 ID 而非位置）")
                    if (n.children.isNotEmpty()) error("$p.children", "叶子节点不能有子节点")
                }
                "and", "or", "not" -> {
                    if (n.ref != null) error("$p.ref", "分组节点不能携带组件引用")
                    if (n.op == "not" && n.children.size != 1) error("$p.children", "非节点必须恰好有一个子节点")
                    if (n.op != "not" && n.children.isEmpty()) error("$p.children", "与/或分组不能为空")
                }
                else -> error("$p.op", "未知匹配树操作：${n.op}")
            }
            if (n.children.size > EngineLimits.MAX_MATCH_NODES) { error("$p.children", "匹配树节点过多"); return }
            n.children.forEachIndexed { index, child -> if (count <= EngineLimits.MAX_MATCH_NODES) node(child, "$p.children[$index]", depth + 1) }
        }
        spec.tree?.let { node(it, "tree") }
    }
    private fun validateParams(c: Component, meta: ComponentMeta, locals: List<Variable>): List<ValidationIssue> = buildList {
        fun error(key: String, message: String) { add(ValidationIssue(key, message)) }
        val specs = meta.params.associateBy { it.key }
        c.params.keys.filter { it !in specs }.forEach { error(it, "未知参数，不能静默丢弃") }
        meta.params.forEach { spec ->
            val value = c.params[spec.key]
            val primitive = value as? JsonPrimitive
            val required = spec.required && !(c.type == "condition.variable" && spec.key == "value" && c.params["op"] == JsonPrimitive("empty"))
            if (value == null) { if (required) error(spec.key, "必填参数：${spec.label}"); return@forEach }
            val valid = when (spec.kind) {
                "text", "code" -> primitive?.isString == true && (!required || primitive.content.isNotBlank())
                "time" -> primitive?.isString == true && Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(primitive.content)
                "choice" -> primitive?.isString == true && primitive.content in spec.choices
                "long" -> strictLong(value) != null
                "double" -> primitive != null && !primitive.isString && primitive.doubleOrNull?.isFinite() == true
                "bool" -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
                "json" -> true
                else -> false
            }
            if (!valid) { error(spec.key, "参数类型、必填内容或选项不合法：${spec.label}"); return@forEach }
            val n = strictLong(value)
            if (spec.key in setOf("count", "seconds", "radiusMeters") && n != null && n <= 0) error(spec.key, "必须是正整数")
            if (spec.key in setOf("millis", "index") && n != null && n < 0) error(spec.key, "不能为负数")
            if (spec.key == "level" && n != null && n !in 0..100) error(spec.key, "电量必须在0到100之间")
            if (spec.key == "latitude" && primitive!!.doubleOrNull!! !in -90.0..90.0) error(spec.key, "纬度必须在-90到90之间")
            if (spec.key == "longitude" && primitive!!.doubleOrNull!! !in -180.0..180.0) error(spec.key, "经度必须在-180到180之间")
            if (spec.key == "threshold" && primitive!!.doubleOrNull!! <= 0) error(spec.key, "阈值必须大于0")
        }
        if (c.type == "action.wait_event" && ComponentRegistry.find((c.params["eventType"] as? JsonPrimitive)?.content ?: "")?.kind != Kind.TRIGGER) error("eventType", "必须引用已知触发器 typeId")
        val referenceKey = when {
            "target" in specs -> "target"
            "source" in specs -> "source"
            c.type in setOf("condition.variable", "trigger.global_variable_changed") -> "name"
            else -> null
        }
        if (referenceKey != null) {
            val raw = (c.params[referenceKey] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val reference = parseReference(raw)
            val writable = referenceKey == "target"
            if (reference == null || (writable && reference.scope !in setOf("local", "global")) ||
                (c.type == "trigger.global_variable_changed" && reference.scope != "global")) {
                error(referenceKey, "变量引用需带正确作用域；内部变量只读")
            } else if (reference.scope == "local") {
                val local = locals.find { it.name == reference.name }
                if (local == null) error(referenceKey, "局部变量未声明：${reference.name}")
                else if (!reference.hasPath) {
                    val expected = when {
                        c.type.startsWith("action.list_") -> "list"
                        c.type.startsWith("action.dict_") -> "dict"
                        else -> null
                    }
                    if (expected != null && local.type != expected) error(referenceKey, "操作需要 $expected 变量")
                    if (c.type == "action.variable_set" && c.params["value"] != null) {
                        val v = c.params.getValue("value")
                        // Runtime variable substitution is intentionally not evaluated while authoring.
                        if (!(v is JsonPrimitive && v.isString && v.content.startsWith("{") && v.content.endsWith("}") && parseReference(v.content.substring(1, v.content.length - 1)) != null)) {
                            validateVariable(local.copy(initial = v)).filter { it.path == "initial" }.forEach { error("value", it.message) }
                        }
                    }
                }
            }
        }
    }
    private data class Reference(val scope: String, val name: String, val hasPath: Boolean)

    // Scan once without recursion: valid and near-matching imported paths can be very long.
    private fun parseReference(raw: String): Reference? {
        val scopeEnd = raw.indexOf('.')
        if (scopeEnd < 0) return null
        val scope = raw.substring(0, scopeEnd)
        if (scope !in setOf("local", "global", "event", "shell", "javascript", "list", "dict", "loop")) return null
        var pos = scopeEnd + 1
        fun readIdentifier(): Boolean {
            if (pos == raw.length) return false
            val first = raw.codePointAt(pos)
            if (first != '_'.code && !Character.isLetter(first)) return false
            pos += Character.charCount(first)
            while (pos < raw.length) {
                val next = raw.codePointAt(pos)
                if (!isIdentifierPart(next)) break
                pos += Character.charCount(next)
            }
            return true
        }
        val nameStart = pos
        if (!readIdentifier()) return null
        val nameEnd = pos
        while (pos < raw.length) {
            when (raw[pos++]) {
                '.' -> if (!readIdentifier()) return null
                '[' -> {
                    val indexStart = pos
                    while (pos < raw.length && raw[pos] in '0'..'9') pos++
                    if (pos == indexStart || pos == raw.length || raw[pos++] != ']') return null
                }
                else -> return null
            }
        }
        return Reference(scope, raw.substring(nameStart, nameEnd), nameEnd < raw.length)
    }

    private fun isIdentifierPart(codePoint: Int): Boolean =
        codePoint == '_'.code || Character.isLetter(codePoint) || when (Character.getType(codePoint)) {
            // Match Unicode \p{N}, not just ASCII digits or Unicode decimal digits.
            Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }
    internal val identifier = Regex("""[\p{L}_][\p{L}\p{N}_]*""")
    internal fun strictLong(value: JsonElement?): Long? = (value as? JsonPrimitive)
        ?.takeIf { !it.isString && Regex("-?(0|[1-9][0-9]*)").matches(it.content) }?.content?.toLongOrNull()

    fun validateVariable(variable: Variable): List<ValidationIssue> = buildList {
        if (!identifier.matches(variable.name)) add(ValidationIssue("name", "变量名必须是未带作用域的标识符（字母、数字、下划线，不能以数字开头）"))
        EngineLimits.valueIssue(variable.initial)?.let { add(ValidationIssue("initial", it)); return@buildList }
        val value = variable.initial
        val primitive = value as? JsonPrimitive
        val valid = when (variable.type) {
            "string" -> primitive?.isString == true
            "int" -> strictLong(value) != null
            "decimal" -> primitive != null && !primitive.isString && primitive.doubleOrNull?.isFinite() == true
            "bool" -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
            "list" -> value is JsonArray
            "dict" -> value is JsonObject
            "null" -> value === JsonNull
            else -> { add(ValidationIssue("type", "未知变量类型：${variable.type}")); true }
        }
        if (!valid) add(ValidationIssue("initial", "初始值与 ${variable.type} 类型不符或数值超出范围；整数必须为64位整数"))
    }
}
