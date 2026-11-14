package com.flowglass.demo.system.exec

import android.content.Context

object ExecutionRuntime {
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, failure ->
        android.util.Log.e("FlowGlassExecution", "未捕获的执行异常", failure)
    }
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default + exceptionHandler,
    )
    private var application: Context? = null
    private var sharedEngine: ExecutionEngine? = null

    @Synchronized
    fun engine(context: Context): ExecutionEngine {
        val app = context.applicationContext
        if (app !== application || sharedEngine == null) {
            application = app
            sharedEngine = ExecutionEngine(app)
        }
        return checkNotNull(sharedEngine)
    }
}
