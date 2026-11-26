package com.flowglass.demo.xposed.a11y

import com.flowglass.demo.system.a11y.A11yKeepAlivePolicy

/** Everything the worker needs from the framework, so the worker itself is plain Kotlin. */
internal interface A11yConnectionOps {
    fun componentOf(connection: Any): String?
    fun isAlive(connection: Any): Boolean
    fun decide(component: String, connection: Any, attempts: Int): A11yKeepAlivePolicy.Result
    fun bind(connection: Any): Boolean
    fun onRepaired(component: String)
}
