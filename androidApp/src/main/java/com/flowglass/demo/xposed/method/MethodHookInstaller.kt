package com.flowglass.demo.xposed.method

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
            paramCount = spec.paramCount,
            paramTypes = spec.paramTypes,
        )
        return when (resolution) {
            MethodResolution.NotFound -> {
                emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.NOT_FOUND, "没有匹配的方法")
                "not_found"
            }
            is MethodResolution.Ambiguous -> {
                val detail = "匹配到 ${resolution.candidates.size} 个重载，请补充参数个数或参数类型"
                emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.AMBIGUOUS, detail)
                "ambiguous"
            }
            is MethodResolution.Unique -> {
                val match = methods.firstOrNull { it.signature == resolution.signature } ?: return "failed"
                if (Modifier.isAbstract(match.method.modifiers) || Modifier.isNative(match.method.modifiers)) {
                    emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.FAILED, "目标方法是 abstract/native")
                    return "failed"
                }
                val limiter = MethodHookRateLimiter(spec.maxCallsPerMinute, spec.burst)
                val callback = HookCallback { _, args, result ->
                    if (!limiter.tryAcquire()) {
                        emitter.emit(spec, TYPE_RATE, emptyMap(), MethodHookStatus.RATE_LIMITED, "限流中")
                        return@HookCallback
                    }
                    val variables = linkedMapOf<String, String>()
                    variables[MethodHookVariables.APP] = spec.packageName
                    variables[MethodHookVariables.USER] = spec.userId.toString()
                    variables[MethodHookVariables.PROCESS] = processName
                    variables[MethodHookVariables.METHOD] = "${spec.className}#${spec.methodName}"
                    variables[MethodHookVariables.SIGNATURE] = resolution.signature.display(spec.methodName)
                    variables[MethodHookVariables.ID] = spec.id
                    for (extraction in spec.extractions) {
                        val source = when (extraction.source) {
                            com.flowglass.engine.methodhook.MethodExtractionSource.ARGUMENT ->
                                args.getOrNull(extraction.argIndex ?: -1)
                            com.flowglass.engine.methodhook.MethodExtractionSource.RESULT -> result
                        }
                        variables[extraction.name] =
                            MethodValueReader.read(source, extraction.path).orEmpty()
                    }
                    emitter.emit(spec, TYPE_EVENT, variables, null, null)
                }
                val hooked = try {
                    bridge.hookMethod(match.method, callback)
                } catch (failure: Throwable) {
                    false
                }
                if (hooked) {
                    emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.INSTALLED, resolution.signature.display(spec.methodName))
                    "installed"
                } else {
                    emitter.emit(spec, TYPE_INSTALL, emptyMap(), MethodHookStatus.FAILED, "框架拒绝安装")
                    "failed"
                }
            }
        }
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
