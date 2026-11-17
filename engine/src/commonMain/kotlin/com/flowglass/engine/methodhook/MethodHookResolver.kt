package com.flowglass.engine.methodhook

/** A framework-neutral signature collected from the target class's declared methods. */
data class MethodSignature(val parameterTypes: List<String>) {
    fun display(methodName: String): String =
        "$methodName(${parameterTypes.joinToString(",")})"
}

sealed interface MethodResolution {
    data class Unique(val signature: MethodSignature) : MethodResolution
    data class Ambiguous(val candidates: List<MethodSignature>) : MethodResolution
    data object NotFound : MethodResolution
}

/**
 * Resolves the user's optional count/type constraints against the methods actually declared by
 * the target class. The app can only validate syntax; this resolution must run inside the target
 * process after the classloader is ready.
 *
 * A broad constraint that leaves several overloads is intentionally an error: the first version
 * never guesses which overload the user meant.
 */
object MethodHookResolver {
    fun resolve(
        methods: List<MethodSignature>,
        paramCount: Int? = null,
        paramTypes: List<String> = emptyList(),
    ): MethodResolution {
        val wantedTypes = paramTypes.map { it.trim() }
        val matching = methods
            .asSequence()
            .filter { paramCount == null || it.parameterTypes.size == paramCount }
            .filter { wantedTypes.isEmpty() || it.parameterTypes == wantedTypes }
            .distinct()
            .toList()
        return when (matching.size) {
            0 -> MethodResolution.NotFound
            1 -> MethodResolution.Unique(matching.single())
            else -> MethodResolution.Ambiguous(matching)
        }
    }
}
