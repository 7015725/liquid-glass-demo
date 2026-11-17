package com.flowglass.demo.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * libxposed API 101 adapter. It is intentionally thin: the runtime still sees only
 * [HookBridge], and the callback is an after-the-fact observer. `chain.proceed()` is called
 * exactly once, with the original arguments; the returned value is passed through untouched.
 *
 * The framework's PROTECTIVE exception mode is a second line of defence on top of the runtime's
 * own HookFailureGuard. No method in this class exposes setArg/returnEarly/replace.
 */
internal class Api101HookBridge(
    private val module: XposedModule,
    private val loader: ClassLoader,
) : HookBridge {
    override val apiLevel: Int
        get() = runCatching { module.apiVersion }.getOrDefault(101)

    override fun log(message: String) {
        runCatching { module.log(Log.INFO, TAG, message) }
    }

    override fun findClass(name: String, loader: ClassLoader): Class<*> =
        Class.forName(name, false, loader)

    override fun hookAllMethods(clazz: Class<*>, methodName: String, callback: HookCallback): Int {
        var installed = 0
        for (method in clazz.declaredMethods) {
            if (method.name != methodName) continue
            if (Modifier.isAbstract(method.modifiers) || Modifier.isNative(method.modifiers)) continue
            try {
                module.hook(method)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            callback.afterCall(
                                thisObject = chain.thisObject,
                                args = chain.args.toTypedArray(),
                                result = result,
                            )
                        } catch (failure: Throwable) {
                            log("callback failed: ${failure.javaClass.simpleName}: ${failure.message}")
                        }
                        result
                    }
                installed++
            } catch (failure: Throwable) {
                log("hook ${clazz.name}#${method.name} failed: ${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
        return installed
    }

    override fun getObjectField(instance: Any?, name: String): Any? {
        requireNotNull(instance) { "field receiver is null" }
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field: Field? = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(instance)
            }
            type = type.superclass
        }
        throw NoSuchFieldException("${instance.javaClass.name}.$name")
    }

    override fun callMethod(instance: Any?, name: String, vararg args: Any?): Any? {
        requireNotNull(instance) { "method receiver is null" }
        val method = findMethod(instance.javaClass, name, args, static = false)
            ?: throw NoSuchMethodException("${instance.javaClass.name}.$name/${args.size}")
        method.isAccessible = true
        return method.invoke(instance, *args)
    }

    override fun callStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any? {
        val method = findMethod(clazz, name, args, static = true)
            ?: throw NoSuchMethodException("${clazz.name}.$name/${args.size}")
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    private fun findMethod(
        clazz: Class<*>,
        name: String,
        args: Array<out Any?>,
        static: Boolean,
    ): Method? {
        var type: Class<*>? = clazz
        while (type != null) {
            type.declaredMethods
                .asSequence()
                .filter { it.name == name }
                .filter { Modifier.isStatic(it.modifiers) == static }
                .filter { it.parameterCount == args.size }
                .filter { method ->
                    method.parameterTypes.indices.all { index ->
                        isAssignable(method.parameterTypes[index], args[index])
                    }
                }
                .firstOrNull()
                ?.let { return it }
            type = type.superclass
        }
        return null
    }

    private fun isAssignable(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        if (!type.isPrimitive) return type.isAssignableFrom(value.javaClass)
        return when (type) {
            java.lang.Boolean.TYPE -> value is Boolean
            java.lang.Byte.TYPE -> value is Byte
            java.lang.Character.TYPE -> value is Char
            java.lang.Short.TYPE -> value is Short
            java.lang.Integer.TYPE -> value is Int
            java.lang.Long.TYPE -> value is Long
            java.lang.Float.TYPE -> value is Float
            java.lang.Double.TYPE -> value is Double
            else -> false
        }
    }

    private companion object {
        const val TAG = "FlowGlass/SystemHook"
    }
}
