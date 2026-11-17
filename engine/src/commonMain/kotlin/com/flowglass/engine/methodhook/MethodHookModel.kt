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
    val enabled: Boolean = true,
    val packageName: String,
    val userId: Int = 0,
    val className: String,
    val methodName: String,
    val paramCount: Int? = null,
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

/** The on-disk container. A separate file keeps method hooks out of the existing commands schema. */
@Serializable
data class MethodHookFile(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val hooks: List<MethodHookSpec> = emptyList(),
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
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
            "不支持的方法钩子运行时格式版本 ${config.formatVersion}"
        }
        return config
    }
}

object MethodHookJson {
    val format = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(file: MethodHookFile): String = format.encodeToString(MethodHookFile.serializer(), file)

    fun decode(text: String): MethodHookFile {
        val file = format.decodeFromString(MethodHookFile.serializer(), text)
        require(file.formatVersion == MethodHookFile.CURRENT_FORMAT_VERSION) {
            "不支持的方法钩子格式版本 ${file.formatVersion}"
        }
        return file
    }
}

/**
 * Fixed metadata variables supplied by every method-hook trigger. User extraction names must not
 * collide with these; the variable catalogue will register them as built-in event variables.
 */
object MethodHookVariables {
    const val APP = "钩子应用"
    const val USER = "钩子用户"
    const val PROCESS = "钩子进程"
    const val METHOD = "钩子方法"
    const val SIGNATURE = "钩子签名"
    const val ID = "钩子标识"

    val reserved: Set<String> = linkedSetOf(APP, USER, PROCESS, METHOD, SIGNATURE, ID)
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
        if (spec.id.isBlank()) add("钩子标识不能为空")
        if (!MethodHookNames.isValidPackage(spec.packageName)) add("目标包名不合法")
        if (spec.userId !in 0..999_999) add("用户 ID 必须在 0–999999")
        if (!MethodHookNames.isValidClass(spec.className)) add("类名不合法")
        if (!MethodHookNames.isValidMethod(spec.methodName)) add("方法名不合法")
        if (spec.paramCount != null && spec.paramCount !in 0..MethodHookNames.MAX_PARAMS) {
            add("参数个数必须在 0–${MethodHookNames.MAX_PARAMS}")
        }
        if (spec.paramTypes.size > MethodHookNames.MAX_PARAMS) {
            add("参数类型过多")
        }
        if (spec.paramTypes.any { !MethodHookNames.isValidType(it) }) {
            add("参数类型必须使用全限定名、基本类型或数组写法")
        }
        if (spec.paramTypes.isNotEmpty() && spec.paramCount != null &&
            spec.paramTypes.size != spec.paramCount
        ) {
            add("参数个数与参数类型数量不一致")
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
                add("$label：名字「$name」是钩子内置变量，请换一个")
            } else if (name in existingNames) {
                add("$label：提取变量「$name」已被其他钩子使用，变量名必须全局唯一")
            } else if (!names.add(name)) {
                add("$label：提取变量「$name」重名")
            }
            when (extraction.source) {
                MethodExtractionSource.ARGUMENT -> {
                    val arg = extraction.argIndex
                    if (arg == null || arg < 0) {
                        add("$label：参数提取必须填写参数序号")
                    } else if (spec.paramCount != null && arg >= spec.paramCount) {
                        add("$label：参数序号超出已声明的参数个数")
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
