package com.flowglass.demo.system.exec

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable)
object LaunchableApps {
    suspend fun load(context: Context): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                if (!activity.enabled || !activity.applicationInfo.enabled) return@mapNotNull null
                LaunchableApp(activity.packageName, info.loadLabel(pm).toString().ifBlank { activity.packageName },
                    info.loadIcon(pm) ?: pm.defaultActivityIcon)
            }.distinctBy { it.packageName }
            .sortedWith(compareBy<LaunchableApp> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
    }
    suspend fun label(context: Context, packageName: String): String =
        load(context).firstOrNull { it.packageName == packageName }?.label ?: packageName
}
