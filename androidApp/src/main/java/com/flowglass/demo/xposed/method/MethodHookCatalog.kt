package com.flowglass.demo.xposed.method

import android.content.Context
import com.flowglass.engine.methodhook.MethodHookNames
import com.flowglass.engine.methodhook.MethodHookSpec
import java.io.File

/**
 * App-process view of the method-hook definitions. The target process reads the framework
 * configuration directly; the editor and variable catalogue only need the definitions, not the
 * per-hook tokens. This cache is refreshed after the app starts and after every save/delete so
 * the variable picker and name validation see the same list the trigger picker does.
 */
internal object MethodHookCatalog {
    @Volatile
    private var cached: List<MethodHookSpec> = emptyList()

    fun all(): List<MethodHookSpec> = cached

    fun refresh(context: Context) {
        val appContext = context.applicationContext ?: context
        cached = runCatching {
            MethodHookStore(File(appContext.filesDir, FILE_NAME)).load().hooks
        }.getOrDefault(emptyList())
    }

    /** Extraction names that are registered as built-in variables of the method-hook trigger. */
    fun variableNames(): Set<String> = cached
        .flatMap { hook -> hook.extractions }
        .map { MethodHookNames.normalizeExtractionName(it.name) }
        .filter { it.isNotBlank() }
        .toSet()

    fun displayName(hook: MethodHookSpec): String =
        "${hook.packageName}@${hook.userId} · ${hook.className}#${hook.methodName}"

    private const val FILE_NAME = "method-hooks.json"
}
