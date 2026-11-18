package com.flowglass.demo.xposed.method

import android.content.Context
import com.flowglass.demo.ui.AppRuntime
import com.flowglass.demo.ui.PersistedAppState
import com.flowglass.demo.ui.snapshot
import com.flowglass.demo.xposed.ModernModuleStatus
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ParameterCodec
import com.flowglass.engine.methodhook.AppMethodCall
import com.flowglass.engine.methodhook.MethodHookRuntimeConfig
import com.flowglass.engine.methodhook.MethodHookRuntimeHook
import com.flowglass.engine.methodhook.MethodHookRuntimeJson
import com.flowglass.engine.methodhook.MethodHookValidation
import java.io.File

/**
 * Builds the target-process snapshot from the single source of truth: enabled commands whose
 * trigger is `trigger.app_method_call`. There is no separate hook store and no per-hook enabled
 * flag in the UI; disabling/deleting the command removes its runtime hook on the next publish.
 *
 * A failed publish leaves the previous snapshot intact; it never writes partial JSON.
 */
internal object MethodHookConfigPublisher {
    fun publish(context: Context) {
        val appContext = context.applicationContext ?: context
        val state = runCatching {
            AppRuntime.of(appContext).state ?: AppRuntime.of(appContext).load()
        }.getOrNull()
        publish(appContext, state)
    }

    fun publish(context: Context, state: com.flowglass.demo.ui.AppState?) {
        val snapshot = state?.snapshot() ?: return
        publish(context, snapshot)
    }

    fun publish(context: Context, snapshot: PersistedAppState) {
        val appContext = context.applicationContext ?: context
        // The pre-release hook file was a second source of truth. It is no longer read; remove
        // both spellings so a stale file cannot be mistaken for live configuration.
        runCatching {
            File(appContext.filesDir, "method-hooks.json").delete()
            File(appContext.filesDir, "method_hooks.json").delete()
        }
        val secrets = MethodHookSecrets(appContext)
        val runtime = collect(snapshot).map { spec ->
            MethodHookRuntimeHook(spec = spec, token = secrets.ensureToken(spec.id))
        }
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

    /** Collects only enabled automatic commands. A one-tap command can never own a trigger. */
    fun collect(snapshot: PersistedAppState): List<com.flowglass.engine.methodhook.MethodHookSpec> {
        val registry = BuiltInComponents.registry()
        return snapshot.autoCmds
            .asSequence()
            .filter { it.enabled }
            .flatMap { command -> command.triggers.asSequence().map { component -> command to component } }
            .filter { (_, component) -> component.typeId == AppMethodCall.TYPE_ID }
            .mapNotNull { (command, component) ->
                val meta = registry.find(AppMethodCall.TYPE_ID) ?: return@mapNotNull null
                val params = runCatching { ParameterCodec.parseComponent(meta, component.parameter) }
                    .getOrNull() ?: return@mapNotNull null
                val spec = AppMethodCall.spec(AppMethodCall.hookId(command.id, component.id), params)
                    ?: return@mapNotNull null
                if (MethodHookValidation.errors(spec).isNotEmpty()) null else spec
            }
            .toList()
    }

    /** Cached runtime snapshot used by the exported Binder service and target bootstrap. */
    fun readRuntime(context: Context): MethodHookRuntimeConfig? {
        val raw = context.getSharedPreferences(MethodHookPrefs.NAME, Context.MODE_PRIVATE)
            .getString(MethodHookRuntimeConfig.PREF_KEY, null) ?: return null
        return runCatching { MethodHookRuntimeJson.decode(raw) }.getOrNull()
    }
}
