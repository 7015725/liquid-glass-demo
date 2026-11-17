package com.flowglass.demo.xposed.method

import android.content.Context
import com.flowglass.demo.xposed.ModernModuleStatus
import com.flowglass.engine.methodhook.MethodHookRuntimeConfig
import com.flowglass.engine.methodhook.MethodHookRuntimeHook
import com.flowglass.engine.methodhook.MethodHookRuntimeJson
import com.flowglass.engine.methodhook.MethodHookValidation
import java.io.File

/**
 * Writes the versioned runtime snapshot into two framework-owned places:
 * - classic: the module's normal SharedPreferences file, made readable by LSPosed when the
 *   `xposedsharedprefs` metadata is present;
 * - modern: XposedService.getRemotePreferences, so the target process reads the same group via
 *   XposedInterface.getRemotePreferences.
 *
 * A failed publish leaves the previous snapshot intact; it never writes partial JSON.
 */
internal object MethodHookConfigPublisher {
    fun publish(context: Context) {
        val appContext = context.applicationContext ?: context
        val store = MethodHookStore(File(appContext.filesDir, "method-hooks.json"))
        val loaded = store.load()
        if (loaded.error != null) return
        val secrets = MethodHookSecrets(appContext)
        val runtime = loaded.hooks
            .filter { it.enabled && MethodHookValidation.isValid(it) }
            .map { MethodHookRuntimeHook(spec = it, token = secrets.ensureToken(it.id)) }
        val json = MethodHookRuntimeJson.encode(MethodHookRuntimeConfig(hooks = runtime))

        runCatching {
            appContext.getSharedPreferences(MethodHookPrefs.NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(MethodHookRuntimeConfig.PREF_KEY, json)
                .commit()
        }
        runCatching {
            ModernModuleStatus.service()
                ?.getRemotePreferences(MethodHookPrefs.NAME)
                ?.edit()
                ?.putString(MethodHookRuntimeConfig.PREF_KEY, json)
                ?.apply()
        }
    }
}
