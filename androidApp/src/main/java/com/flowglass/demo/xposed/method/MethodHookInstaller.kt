package com.flowglass.demo.xposed.method

import com.flowglass.engine.methodhook.MethodHookNames
import com.flowglass.engine.methodhook.MethodHookRuntimeConfig
import com.flowglass.engine.methodhook.MethodHookRuntimeJson
import com.flowglass.engine.methodhook.MethodHookRuntimeHook
import com.flowglass.engine.methodhook.MethodHookSpec
import com.flowglass.engine.methodhook.MethodHookStatus
import com.flowglass.engine.methodhook.MethodHookVariables
import com.flowglass.engine.methodhook.MethodHookResolver
import com.flowglass.engine.methodhook.MethodResolution
import com.flowglass.engine.methodhook.MethodSignature
import com.flowglass.demo.xposed.HookBridge
import com.flowglass.demo.xposed.HookCallback
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** The one preference group shared by the App writer and both target-process readers. */
object MethodHookPrefs {
    const val NAME = "flowglass_method_hooks"
}

internal fun interface MethodHookEmitter {
    fun emit(
        spec: MethodHookSpec,
        type: String,
        variables: Map<String, String>,
        status: String?,
        detail: String?,
    )
}

/**
 * Runs inside a target-app process. It resolves only the methods declared by the configured class
 * and refuses to install when the constraints do not leave exactly one overload. All user data is
 * read after the original method returns, and every failure is contained here.
 */
internal object MethodHookInstaller {
    fun install(
        bridge: HookBridge,
        classLoader: ClassLoader,
        packageName: String,
        userId: Int,
        processName: String,
        config: MethodHookRuntimeConfig,
        emitter: MethodHookEmitter,
    ): List<String> {
        val reports = mutableListOf<String>()
        val hooks = config.hooks.filter {
            it.spec.enabled && it.spec.packageName == packageName && it.spec.userId == userId
        }
        for (runtime in hooks) {
            reports += installOne(bridge, classLoader, processName, runtime, emitter)
        }
        return reports
    }

    private fun installOne(
        bridge: HookBridge,
        classLoader: ClassLoader,
        processName: String,
        runtime: MethodHookRuntimeHook,
        emitter: MethodHookEmitter,
    ): String {
        val spec = runtime.spec
        val validation = com.flowglass.engine.methodhook.MethodHookValidation.errors(spec)
        if (validation.isNotEmpty()) {
            emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.FAILED, validation.first())
            return "failed"
        }
        val target = try {
            bridge.findClass(spec.className, classLoader)
        } catch (failure: Throwable) {
            emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.FAILED, "找不到目标类")
            return "failed"
        }

        val methods = target.declaredMethods
            .filter { it.name == spec.methodName }
            .map { ResolvedMethod(it, MethodSignature(it.parameterTypes.map(::typeName))) }
        val resolution = MethodHookResolver.resolve(
            methods.map { it.signature },
            paramTypes = spec.paramTypes,
        )
        // One limiter per configured trigger, shared by every resolved overload. Otherwise an
        // empty parameter filter could multiply the advertised 60/min limit.
        val limiter = MethodHookRateLimiter(spec.maxCallsPerMinute, spec.burst)
        return when (resolution) {
            MethodResolution.NotFound -> {
                emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.NOT_FOUND, "没有匹配的方法")
                "not_found"
            }
            is MethodResolution.Ambiguous -> {
                val detail = "匹配到 ${resolution.candidates.size} 个重载，请补充参数类型"
                emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.AMBIGUOUS, detail)
                "ambiguous"
            }
            is MethodResolution.Unique -> {
                val match = methods.firstOrNull { it.signature == resolution.signature } ?: return "failed"
                if (Modifier.isAbstract(match.method.modifiers) || Modifier.isNative(match.method.modifiers)) {
                    emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.FAILED, "目标方法是 abstract/native")
                    return "failed"
                }
                if (installResolved(bridge, spec, processName, match, emitter, limiter)) "installed" else "failed"
            }
            is MethodResolution.All -> {
                // Empty parameter types deliberately observe every same-name overload. The
                // user does not have to know which overload their app calls.
                val usable = resolution.signatures.mapNotNull { signature ->
                    methods.firstOrNull { it.signature == signature }
                }.filterNot { Modifier.isAbstract(it.method.modifiers) || Modifier.isNative(it.method.modifiers) }
                if (usable.isEmpty()) {
                    emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.FAILED, "没有可安装的同名方法")
                    return "failed"
                }
                var installedAny = false
                for (method in usable) {
                    if (installResolved(bridge, spec, processName, method, emitter, limiter, emitStatus = false)) {
                        installedAny = true
                    }
                }
                if (installedAny) {
                    emitter.emit(
                        spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.INSTALLED,
                        usable.joinToString("、") { it.signature.display(spec.methodName) },
                    )
                    "installed"
                } else {
                    emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.FAILED, "框架拒绝安装")
                    "failed"
                }
            }
        }
    }

    private fun installResolved(
        bridge: HookBridge,
        spec: MethodHookSpec,
        processName: String,
        match: ResolvedMethod,
        emitter: MethodHookEmitter,
        limiter: MethodHookRateLimiter,
        emitStatus: Boolean = true,
    ): Boolean {
        val callback = HookCallback { _, args, result ->
            if (!limiter.tryAcquire()) {
                // One status packet per burst of suppressed calls, not one binder write per call:
                // a hot target method must not turn our limiter into a broadcast storm.
                val suppressed = limiter.suppressed()
                if (suppressed == 1L) {
                    android.util.Log.i(
                        "FlowGlass/MethodHook",
                        "限流：${spec.className}#${spec.methodName} 已抑制事件",
                    )
                    emitter.emit(spec, TYPE_RATE, emptyMap(), MethodHookStatus.RATE_LIMITED, "限流中")
                }
                return@HookCallback
            }
            val variables = linkedMapOf<String, String>()
            variables[MethodHookVariables.APP] = spec.packageName
            variables[MethodHookVariables.USER] = spec.userId.toString()
            variables[MethodHookVariables.PROCESS] = processName
            variables[MethodHookVariables.METHOD] = "${spec.className}#${spec.methodName}"
            variables[MethodHookVariables.SIGNATURE] = match.signature.display(spec.methodName)
            for (extraction in spec.extractions) {
                val source = when (extraction.source) {
                    com.flowglass.engine.methodhook.MethodExtractionSource.ARGUMENT ->
                        args.getOrNull(extraction.argIndex ?: -1)
                    com.flowglass.engine.methodhook.MethodExtractionSource.RESULT -> result
                }
                variables[MethodHookNames.normalizeExtractionName(extraction.name)] =
                    MethodValueReader.read(source, extraction.path).orEmpty()
            }
            emitter.emit(spec, TYPE_EVENT, variables, null, null)
        }
        val hooked = try {
            bridge.hookMethod(match.method, callback)
        } catch (_: Throwable) {
            false
        }
        if (emitStatus) {
            if (hooked) {
                emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.INSTALLED,
                    match.signature.display(spec.methodName))
            } else {
                emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.FAILED, "框架拒绝安装")
            }
        }
        return hooked
    }

    private data class ResolvedMethod(
        val method: java.lang.reflect.Method,
        val signature: MethodSignature,
    )

    private fun typeName(type: Class<*>): String =
        if (type.isArray) typeName(type.componentType ?: return "java.lang.Object") + "[]" else type.name

    const val TYPE_INSTALL = "install"
    const val TYPE_EVENT = "event"
    const val TYPE_RATE = "rate"
}
