package com.flowglass.demo.system.exec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * A launchable activity. [userId] is the Android profile id; the stable storage form is
 * `package@userId`, while the current profile keeps the legacy bare package name.
 */
data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val userId: Int = 0,
    val profileLabel: String = "",
) {
    val id: String get() = if (userId == 0) packageName else "$packageName@$userId"
}

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
        while (true) {
            currentCoroutineContext().ensureActive()
            val version = generation.get()
            val apps = queryLauncherApps(appContext)
                .ifEmpty { queryPackageManager(appContext) }
                .distinctBy { it.packageName to it.userId }
                .sortedWith(compareBy<LaunchableApp> { it.label.lowercase(Locale.ROOT) }
                    .thenBy { it.userId }.thenBy { it.packageName })
            // A broadcast can arrive on Main while the IO query is in progress.
            if (generation.get() == version) {
                cache = Cache(SystemClock.elapsedRealtime(), version, apps)
                return apps
            }
        }
    }

    private fun queryLauncherApps(appContext: Context): List<LaunchableApp> {
        val launcher = appContext.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val profiles = runCatching { launcher.profiles }.getOrDefault(emptyList())
        if (profiles.isEmpty()) return emptyList()
        val pm = appContext.packageManager
        return profiles.flatMap { profile ->
            val userId = profile.hashCode()
            val profileLabel = profileLabel(profile)
            runCatching { launcher.getActivityList(null, profile) }.getOrElse { emptyList() }
                .mapNotNull { info ->
                    val application = runCatching { info.applicationInfo }.getOrNull() ?: return@mapNotNull null
                    if (!application.enabled) return@mapNotNull null
                    val label = runCatching { info.label?.toString() }.getOrNull().orEmpty()
                        .ifBlank { info.componentName.packageName }
                    val icon = runCatching { info.getIcon(0) }.getOrNull()
                        ?: runCatching { pm.getApplicationIcon(info.componentName.packageName) }.getOrNull()
                        ?: pm.defaultActivityIcon
                    LaunchableApp(
                        packageName = info.componentName.packageName,
                        label = label,
                        icon = icon,
                        userId = userId,
                        profileLabel = profileLabel,
                    )
                }
        }
    }

    private fun queryPackageManager(appContext: Context): List<LaunchableApp> {
        val pm = appContext.packageManager
        return pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            if (!activity.enabled || !activity.applicationInfo.enabled) return@mapNotNull null
            LaunchableApp(
                packageName = activity.packageName,
                label = info.loadLabel(pm).toString().ifBlank { activity.packageName },
                icon = info.loadIcon(pm) ?: pm.defaultActivityIcon,
                userId = 0,
                profileLabel = "当前用户",
            )
        }
    }

    private fun profileLabel(profile: UserHandle): String = when {
        profile == Process.myUserHandle() -> "当前用户"
        profile.hashCode() == 10 -> "工作资料"
        else -> "用户 ${profile.hashCode()}"
    }

    /** Parses the persisted form; a bare package means the current profile (legacy behavior). */
    fun parseLaunchTarget(requested: String): Pair<String, Int?> {
        val value = requested.trim()
        require(value.isNotEmpty()) { "参数格式不正确：期望 应用包名或应用名称" }
        val at = value.lastIndexOf('@')
        if (at <= 0) return value to null
        val packageName = value.substring(0, at)
        val userId = value.substring(at + 1).toIntOrNull()
            ?: throw IllegalArgumentException("参数格式不正确：分身应用应写成 package@userId")
        require(packageName.isNotBlank()) { "参数格式不正确：缺少应用包名" }
        return packageName to userId
    }

    suspend fun label(context: Context, target: String): String = withContext(Dispatchers.IO) {
        val (packageName, _) = runCatching { parseLaunchTarget(target) }.getOrElse { return@withContext target }
        val apps = load(context)
        apps.firstOrNull { it.id == target || (it.packageName == packageName && it.userId == 0) }?.label
            ?: apps.firstOrNull { it.packageName == packageName }?.label
            ?: packageName
    }

    /**
     * Launches the target. A profile target never silently falls back to the same package in the
     * current profile: if enumeration or the launch fails, the caller gets the actionable error.
     */
    suspend fun launch(context: Context, requested: String, allowRootFallback: Boolean = true): String {
        val (packageName, userId) = parseLaunchTarget(requested)
        if (userId == null) {
            val intent = withContext(Dispatchers.IO) {
                context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: run {
                        val apps = load(context)
                        val app = apps.firstOrNull {
                            it.packageName == packageName || it.label.equals(packageName, ignoreCase = true)
                        } ?: apps.firstOrNull { it.label.contains(packageName, ignoreCase = true) }
                        app?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) }
                    }
            }
            checkNotNull(intent) { "未找到可启动应用：$requested" }
            withContext(Dispatchers.Main.immediate) {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return "已启动应用：$requested"
        }

        val direct = runCatching { launchWithLauncher(context, packageName, userId, requested) }
        if (direct.isSuccess) return direct.getOrThrow()
        if (!allowRootFallback) {
            throw IllegalStateException(
                "普通层启动分身失败：${direct.exceptionOrNull()?.message ?: "未知原因"}",
            )
        }
        val root = runCatching { RootApps.launch(context, packageName, userId) }
        if (root.isSuccess) return "已启动应用：$requested（Root）"
        throw IllegalStateException(
            "LauncherApps 启动失败：${direct.exceptionOrNull()?.message ?: "未知原因"}；" +
                "Root 启动失败：${root.exceptionOrNull()?.message ?: "未知原因"}",
        )
    }

    /** Root-layer launch, using an already selected grant instead of probing Root again. */
    suspend fun launchRoot(context: Context, requested: String, root: com.flowglass.demo.system.RootInfo) {
        val (packageName, userId) = parseLaunchTarget(requested)
        val result = RootApps.launch(context, packageName, userId ?: 0, root)
        check(result.isSuccess) { result.exceptionOrNull()?.message ?: "Root 启动失败" }
    }

    private suspend fun launchWithLauncher(
        context: Context,
        packageName: String,
        userId: Int,
        requested: String,
    ): String {
        val launcher = checkNotNull(context.getSystemService(LauncherApps::class.java)) { "启动器服务不可用" }
        val profile = runCatching { launcher.profiles }.getOrElse {
            throw IllegalStateException("无法枚举用户资料：${it.message ?: "权限不足"}")
        }.firstOrNull { it.hashCode() == userId }
            ?: throw IllegalStateException("找不到用户资料 $userId；请重新选择分身应用")
        val activity = runCatching { launcher.getActivityList(packageName, profile) }.getOrElse {
            throw IllegalStateException("无法读取分身应用：${it.message ?: "权限不足"}")
        }.firstOrNull()
            ?: throw IllegalStateException("用户资料 $userId 中没有可启动应用 $packageName")
        runCatching { launcher.startMainActivity(activity.componentName, profile, null, null) }
            .getOrElse { throw IllegalStateException("启动分身应用失败：${it.message ?: "系统拒绝"}") }
        return "已启动应用：$requested（${profileLabel(profile)}）"
    }
}
