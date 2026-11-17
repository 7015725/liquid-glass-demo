package com.flowglass.demo.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Classic de.robv adapter. It is the only file in the system-hook runtime that knows the legacy
 * callback classes. The adapter never mutates MethodHookParam: arguments are copied before the
 * observer sees them and the result is passed through read-only.
 */
internal class LegacyHookBridge : HookBridge {
    override val apiLevel: Int
        get() = XposedBridge.getXposedVersion()

    override fun log(message: String) {
        runCatching { XposedBridge.log("FlowGlass/SystemHook: $message") }
    }

    override fun findClass(name: String, loader: ClassLoader): Class<*> =
        XposedHelpers.findClass(name, loader)

    override fun hookAllMethods(clazz: Class<*>, methodName: String, callback: HookCallback): Int {
        val methods = XposedBridge.hookAllMethods(
            clazz,
            methodName,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if (param == null) return
                    try {
                        callback.afterCall(
                            thisObject = param.thisObject,
                            args = param.args?.copyOf() ?: emptyArray(),
                            result = param.result,
                        )
                    } catch (failure: Throwable) {
                        // A callback failure must never escape into the target method (here,
                        // system_server). The runtime normally guards its own callbacks too;
                        // this is the adapter's independent last line of defence.
                        log("callback failed: ${failure.javaClass.simpleName}: ${failure.message}")
                    }
                }
            },
        )
        return methods.size
    }

    override fun getObjectField(instance: Any?, name: String): Any? =
        XposedHelpers.getObjectField(instance, name)

    override fun callMethod(instance: Any?, name: String, vararg args: Any?): Any? =
        XposedHelpers.callMethod(instance, name, *args)

    override fun callStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        XposedHelpers.callStaticMethod(clazz, name, *args)
}
