package com.flowglass.engine.methodhook

/** A framework-neutral signature collected from the target class's declared methods. */
data class MethodSignature(val parameterTypes: List<String>) {
    fun display(methodName: String): String =
        "$methodName(${parameterTypes.joinToString(",")})"
}

sealed interface MethodResolution {
    data class Unique(val signature: MethodSignature) : MethodResolution
    /** No parameter-type constraint: every same-name overload is observed, never guessed. */
    data class All(val signatures: List<MethodSignature>) : MethodResolution
    data class Ambiguous(val candidates: List<MethodSignature>) : MethodResolution
    data object NotFound : MethodResolution
}

object MethodHookResolver {
    fun resolve(
        methods: List<MethodSignature>,
        paramTypes: List<String> = emptyList(),
    ): MethodResolution {
        val wantedTypes = paramTypes.map { it.trim() }
        val matching = methods
            .asSequence()
            .filter { wantedTypes.isEmpty() || it.parameterTypes == wantedTypes }
            .distinct()
            .toList()
        return when {
            matching.isEmpty() -> MethodResolution.NotFound
            wantedTypes.isEmpty() && matching.size > 1 -> MethodResolution.All(matching)
            matching.size == 1 -> MethodResolution.Unique(matching.single())
            else -> MethodResolution.Ambiguous(matching)
        }
    }
}
