package com.flowglass.demo.xposed

/**
 * The only framework surface the hook runtime is allowed to use.
 *
 * Entry adapters translate the classic de.robv API, libxposed API 101 and (future) API 100
 * callbacks into this small interface. The runtime and candidate tables must not import a
 * framework type directly; this keeps the read-only checks and event queue testable and prevents
 * an entry-specific callback shape from leaking into the hook logic.
 *
 * There are deliberately no methods for replacing a result, changing arguments or skipping the
 * original method. A bridge therefore cannot accidentally turn an observer into a writer.
 */
internal interface HookBridge {
    val apiLevel: Int

    fun log(message: String)

    fun findClass(name: String, loader: ClassLoader): Class<*>

    /**
     * Hooks every method with this name on [clazz] and returns the number installed. Missing
     * methods must return 0 rather than throw; callers use that count for the loaded heartbeat.
     */
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: HookCallback): Int

    fun getObjectField(instance: Any?, name: String): Any?

    fun callMethod(instance: Any?, name: String, vararg args: Any?): Any?

    fun callStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any?
}

/**
 * After-the-fact observer. [args] is a defensive copy of the original argument array; changing it
 * cannot affect the target method. [result] is the original result and must never be assigned.
 */
internal fun interface HookCallback {
    fun afterCall(thisObject: Any?, args: Array<Any?>, result: Any?)
}
