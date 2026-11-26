package com.flowglass.demo.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.flowglass.demo.xposed.method.MethodHookTargetBootstrap

/** LSPosed entry: marks the module as active inside our own process. */
class XposedEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") {
            // Defensive and read-only: every callback catches its own failures and the hook is
            // disabled until the app sends the explicit state broadcast.
            SystemHook.install(LegacyHookBridge(), lpparam.classLoader)
            com.flowglass.demo.xposed.a11y.A11yKeepAliveHook
                .install(LegacyHookBridge(), lpparam.classLoader)
            return
        }
        if (lpparam.packageName == "com.flowglass.demo") {
            val cls = ModuleStatus::class.java.name
            XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "isActive", XC_MethodReplacement.returnConstant(true))
            XposedHelpers.findAndHookMethod(
                cls, lpparam.classLoader, "frameworkVersion",
                XC_MethodReplacement.returnConstant(XposedBridge.getXposedVersion())
            )
            return
        }
        if (lpparam.packageName == "com.android.systemui") return
        // Target-app method hooks: install the Application#attach bootstrap before app code runs.
        MethodHookTargetBootstrap.installOnApplicationAttach(
            LegacyHookBridge(),
            lpparam.classLoader,
            lpparam.packageName,
        )
    }
}
