package com.flowglass.demo.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** LSPosed entry: marks the module as active inside our own process. */
class XposedEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.flowglass.demo") return
        val cls = ModuleStatus::class.java.name
        XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "isActive", XC_MethodReplacement.returnConstant(true))
        XposedHelpers.findAndHookMethod(
            cls, lpparam.classLoader, "frameworkVersion",
            XC_MethodReplacement.returnConstant(XposedBridge.getXposedVersion())
        )
    }
}
