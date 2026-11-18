package com.flowglass.engine.methodhook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a declared extraction reads its value from. Only declared reads are supported. */
@Serializable
enum class MethodExtractionSource { ARGUMENT, RESULT }

/** One user-named value copied from a method argument or the return value. */
@Serializable
data class MethodExtraction(
    /** User-visible variable name; Chinese names are allowed. */
    val name: String,
    val source: MethodExtractionSource,
    /** Zero-based argument index; required for ARGUMENT, rejected for RESULT. */
    val argIndex: Int? = null,
    /** Dot-separated fields, empty for the argument/result object itself. */
    val path: String = "",
)

/** The persisted definition of one read-only target-app method hook. */
@Serializable
data class MethodHookSpec(
    val id: String,
    /**
     * Runtime configs are collected only from enabled commands, so this is always true for a
     * published hook. It stays in the wire model so a cached target process can fail closed.
     */
    val enabled: Boolean = true,
    val packageName: String,
    val userId: Int = 0,
    val className: String,
    val methodName: String,
    val paramTypes: List<String> = emptyList(),
    val extractions: List<MethodExtraction> = emptyList(),
    val maxCallsPerMinute: Int = DEFAULT_MAX_CALLS_PER_MINUTE,
    val burst: Int = DEFAULT_BURST,
) {
    val targetKey: String get() = if (userId == 0) packageName else "$packageName@$userId"

    companion object {
        const val DEFAULT_MAX_CALLS_PER_MINUTE = 60
        const val DEFAULT_BURST = 10
    }
}

/**
 * Trigger parameters are the only persisted source of an app-method listener. There is
 * deliberately no second hook file and no hook id in the editor: the id is derived from the
 * command + trigger item so the App, runtime config and status registry agree without exposing
 * an internal identifier to the user.
 */
object AppMethodCall {
    const val TYPE_ID = "trigger.app_method_call"
    const val KEY_PACKAGE = "package"
    const val KEY_CLASS = "className"
    const val KEY_METHOD = "methodName"
    const val KEY_PARAM_TYPES = "paramTypes"
    const val KEY_EXTRACTIONS = "extractions"

    fun hookId(commandId: Long, itemId: Long): String = "call-$commandId-$itemId"

    fun targetKey(raw: String): Pair<String, Int>? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val at = value.lastIndexOf('@')
        if (at < 0) return value to 0
        val user = value.substring(at + 1).toIntOrNull() ?: return null
        val packageName = value.substring(0, at)
        return if (packageName.isNotEmpty()) packageName to user else null
    }

    fun parseParamTypes(raw: String): List<String> =
        raw.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    fun encodeParamTypes(types: List<String>): String = types.joinToString(",")

    fun decodeExtractions(raw: String): List<MethodExtraction> {
        if (raw.isBlank()) return emptyList()
        return MethodHookJson.format.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(MethodExtraction.serializer()),
            raw,
        )
    }

    fun encodeExtractions(extractions: List<MethodExtraction>): String =
        MethodHookJson.format.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(MethodExtraction.serializer()),
            extractions,
        )

    fun spec(
        id: String,
        params: Map<String, kotlinx.serialization.json.JsonElement>,
    ): MethodHookSpec? {
        fun text(key: String): String =
            (params[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content.orEmpty().trim()
        val target = targetKey(text(KEY_PACKAGE)) ?: return null
        val className = text(KEY_CLASS)
        val methodName = text(KEY_METHOD)
        if (className.isEmpty() || methodName.isEmpty()) return null
        return MethodHookSpec(
            id = id,
            packageName = target.first,
            userId = target.second,
            className = className,
            methodName = methodName,
            paramTypes = parseParamTypes(text(KEY_PARAM_TYPES)),
            extractions = runCatching { decodeExtractions(text(KEY_EXTRACTIONS)) }
                .getOrDefault(emptyList()),
        )
    }

    fun validateParams(
        params: Map<String, kotlinx.serialization.json.JsonElement>,
        existingNames: Set<String> = emptySet(),
    ): List<String> {
        fun text(key: String): String =
            (params[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content.orEmpty().trim()
        val errors = mutableListOf<String>()
        val target = targetKey(text(KEY_PACKAGE))
        if (target == null) errors += "目标应用不合法"
        else {
            if (!MethodHookNames.isValidPackage(target.first)) errors += "目标应用包名不合法"
            if (target.second !in 0..999_999) errors += "目标应用用户 ID 不合法"
        }
        if (!MethodHookNames.isValidClass(text(KEY_CLASS))) errors += "类名不合法"
        if (!MethodHookNames.isValidMethod(text(KEY_METHOD))) errors += "方法名不合法"
        val types = parseParamTypes(text(KEY_PARAM_TYPES))
        if (types.size > MethodHookNames.MAX_PARAMS) errors += "参数类型过多"
        if (types.any { !MethodHookNames.isValidType(it) }) {
            errors += "参数类型必须使用全限定名、基本类型或数组写法"
        }
        val extractions = runCatching { decodeExtractions(text(KEY_EXTRACTIONS)) }
            .getOrElse {
                return errors + "提取数据格式无效"
            }
        val spec = MethodHookSpec(
            id = "validation",
            packageName = target?.first ?: "invalid",
            userId = target?.second ?: 0,
            className = text(KEY_CLASS).ifEmpty { "Invalid" },
            methodName = text(KEY_METHOD).ifEmpty { "invalid" },
            paramTypes = types,
            extractions = extractions,
        )
        errors += MethodHookValidation.errors(spec, existingNames)
        return errors.distinct()
    }

    fun extractionNames(
        params: Map<String, kotlinx.serialization.json.JsonElement>,
    ): Set<String> =
        runCatching { decodeExtractions(
            (params[KEY_EXTRACTIONS] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content.orEmpty()
        ) }.getOrDefault(emptyList())
            .map { MethodHookNames.normalizeExtractionName(it.name) }
            .filter { it.isNotBlank() }
            .toSet()

    /** Runtime event data used by the trigger matcher; fixed UI names still come from variables. */
    fun eventData(
        packageName: String,
        userId: Int,
        className: String,
        methodName: String,
        signature: String,
    ): Map<String, String> = mapOf(
        "app" to if (userId == 0) packageName else "$packageName@$userId",
        "app.user" to userId.toString(),
        "method.class" to className,
        "method.name" to methodName,
        "method.signature" to signature,
    )
}


/**
 * Target-process copy of one hook. The token is the bearer secret shared through the framework's
 * remote preferences; it never enters the App's commands document or its logs.
 */
@Serializable
data class MethodHookRuntimeHook(
    val spec: MethodHookSpec,
    val token: String,
)

@Serializable
data class MethodHookRuntimeConfig(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val hooks: List<MethodHookRuntimeHook> = emptyList(),
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
        const val PREF_KEY = "runtime"
    }
}

object MethodHookRuntimeJson {
    val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(config: MethodHookRuntimeConfig): String =
        format.encodeToString(MethodHookRuntimeConfig.serializer(), config)

    fun decode(text: String): MethodHookRuntimeConfig {
        val config = format.decodeFromString(MethodHookRuntimeConfig.serializer(), text)
        require(config.formatVersion == MethodHookRuntimeConfig.CURRENT_FORMAT_VERSION) {
            "不支持的应用方法调用格式版本 ${config.formatVersion}"
        }
        return config
    }
}

object MethodHookJson {
    val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
}

/**
 * Fixed metadata variables supplied by every app-method-call trigger. User extraction names
 * must not collide with these.
 */
object MethodHookVariables {
    const val APP = "调用应用"
    const val USER = "调用用户"
    const val PROCESS = "调用进程"
    const val METHOD = "调用方法"
    const val SIGNATURE = "调用签名"

    val reserved: Set<String> = linkedSetOf(APP, USER, PROCESS, METHOD, SIGNATURE)
}

object MethodHookNames {
    const val MAX_LENGTH = 64
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")
    private val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val methodPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private val typePattern = Regex(
        "(?:[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)(?:\\[\\])*"
    )
    private val pathSegmentPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    fun normalizeExtractionName(raw: String): String = raw.trim()

    fun isValidExtractionName(raw: String): Boolean {
        val name = normalizeExtractionName(raw)
        if (name.length !in 1..MAX_LENGTH) return false
        if (!name[0].isLetter() && name[0] != '_') return false
        return name.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    fun isValidPackage(raw: String): Boolean = packagePattern.matches(raw)

    fun isValidClass(raw: String): Boolean = classPattern.matches(raw)

    fun isValidMethod(raw: String): Boolean = methodPattern.matches(raw)

    fun isValidType(raw: String): Boolean = typePattern.matches(raw.trim())

    fun isValidPath(raw: String): Boolean {
        if (raw.isBlank()) return true
        val parts = raw.split('.')
        if (parts.size > MAX_PATH_DEPTH) return false
        return parts.all { pathSegmentPattern.matches(it) }
    }

    const val MAX_PATH_DEPTH = 4
    const val MAX_PARAMS = 32
    const val MAX_RATE = 600
}

object MethodHookValidation {
    fun errors(spec: MethodHookSpec, existingNames: Set<String> = emptySet()): List<String> = buildList {
        if (spec.id.isBlank()) add("调用标识不能为空")
        if (!MethodHookNames.isValidPackage(spec.packageName)) add("目标包名不合法")
        if (spec.userId !in 0..999_999) add("用户 ID 必须在 0–999999")
        if (!MethodHookNames.isValidClass(spec.className)) add("类名不合法")
        if (!MethodHookNames.isValidMethod(spec.methodName)) add("方法名不合法")
        if (spec.paramTypes.size > MethodHookNames.MAX_PARAMS) {
            add("参数类型过多")
        }
        if (spec.paramTypes.any { !MethodHookNames.isValidType(it) }) {
            add("参数类型必须使用全限定名、基本类型或数组写法")
        }
        if (spec.maxCallsPerMinute !in 1..MethodHookNames.MAX_RATE) {
            add("每分钟调用上限必须在 1–${MethodHookNames.MAX_RATE}")
        }
        if (spec.burst !in 1..spec.maxCallsPerMinute.coerceAtMost(MethodHookNames.MAX_RATE)) {
            add("突发上限必须在 1 与每分钟上限之间")
        }
        val names = mutableSetOf<String>()
        for ((index, extraction) in spec.extractions.withIndex()) {
            val label = "提取项 ${index + 1}"
            val name = MethodHookNames.normalizeExtractionName(extraction.name)
            if (!MethodHookNames.isValidExtractionName(name)) {
                add("$label：名字只能用中文、字母、数字、_ 或 -，长度 1–64")
            } else if (name in MethodHookVariables.reserved) {
                add("$label：名字「$name」是内置变量，请换一个")
            } else if (name in existingNames) {
                add("$label：提取变量「$name」已被其他变量或提取项使用，变量名必须全局唯一")
            } else if (!names.add(name)) {
                add("$label：提取变量「$name」重名")
            }
            when (extraction.source) {
                MethodExtractionSource.ARGUMENT -> {
                    val arg = extraction.argIndex
                    if (arg == null || arg < 0) {
                        add("$label：参数提取必须填写参数序号")
                    }
                }
                MethodExtractionSource.RESULT -> {
                    if (extraction.argIndex != null) {
                        add("$label：返回值提取不能填写参数序号")
                    }
                }
            }
            if (!MethodHookNames.isValidPath(extraction.path)) {
                add("$label：字段路径最多 ${MethodHookNames.MAX_PATH_DEPTH} 层，只允许字段名")
            }
        }
    }

    fun isValid(spec: MethodHookSpec): Boolean = errors(spec).isEmpty()
}
