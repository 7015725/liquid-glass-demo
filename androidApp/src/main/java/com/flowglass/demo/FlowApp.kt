package com.flowglass.demo

import android.app.Application
import com.flowglass.demo.system.Root

/**
 * Installs the root-grant cache for every entry point, not just the Activity: a background
 * service or a boot receiver can execute a rule before any screen exists.
 */
class FlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Root.install(this)
    }
}
