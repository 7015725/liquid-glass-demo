package com.flowglass.demo.system.exec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable)
object LaunchableApps {
    private data class Cache(val createdAt: Long, val generation: Long, val apps: List<LaunchableApp>)
    private val loadMutex = Mutex()
    private val generation = AtomicLong()
    private var cache: Cache? = null
    private var receiverContext: Context? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { generation.incrementAndGet() }
    }

    // Only called with loadMutex held. Never retain/register an Activity context.
    private fun register(appContext: Context) {
        if (receiverContext === appContext) return
        try { receiverContext?.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {
            // A replaced application context may already have released its receivers.
        }
        receiverContext = null
        cache = null
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else appContext.registerReceiver(receiver, filter)
        receiverContext = appContext
    }

    suspend fun load(context: Context): List<LaunchableApp> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            val appContext = context.applicationContext
            register(appContext)
            val version = generation.get()
            cache?.takeIf { it.generation == version && SystemClock.elapsedRealtime() - it.createdAt in 0 until 60_000 }
                ?.let { return@withLock it.apps }
            refresh(appContext)
        }
    }

    private suspend fun refresh(appContext: Context): List<LaunchableApp> {
        val pm = appContext.packageManager
        while (true) {
            currentCoroutineContext().ensureActive()
            val version = generation.get()
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapNotNull { info ->
                    val activity = info.activityInfo ?: return@mapNotNull null
                    if (!activity.enabled || !activity.applicationInfo.enabled) return@mapNotNull null
                    LaunchableApp(activity.packageName, info.loadLabel(pm).toString().ifBlank { activity.packageName },
                        info.loadIcon(pm) ?: pm.defaultActivityIcon)
                }.distinctBy { it.packageName }
                .sortedWith(compareBy<LaunchableApp> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
            // A broadcast can arrive on Main while the IO query is in progress.
            if (generation.get() == version) {
                cache = Cache(SystemClock.elapsedRealtime(), version, apps)
                return apps
            }
        }
    }
    suspend fun label(context: Context, packageName: String): String = withContext(Dispatchers.IO) {
        val pm = context.applicationContext.packageManager
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().ifBlank { packageName }
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { packageName }
    }
}
