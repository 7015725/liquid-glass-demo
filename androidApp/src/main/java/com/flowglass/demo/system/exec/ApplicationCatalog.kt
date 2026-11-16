package com.flowglass.demo.system.exec

import android.content.Context
import com.flowglass.demo.system.Root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The UI-facing application catalog. Normal LauncherApps results stay cacheable for the whole
 * app; root results are cached separately so a page re-entry never silently re-runs a root
 * command (or reopens a root prompt).
 */
internal object ApplicationCatalog {
    private data class Cache(val createdAt: Long, val apps: List<LaunchableApp>)

    private val rootMutex = Mutex()
    @Volatile private var rootCache: Cache? = null
    private val rootTtlMs = 60_000L

    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")

    suspend fun normal(context: Context): Result<List<LaunchableApp>> = withContext(Dispatchers.IO) {
        runCatching { LaunchableApps.load(context) }
    }

    /**
     * A remembered grant is checked without asking the root manager. The automatic caller only
     * reaches this after [hasRootGrant] returns true; [requestPermission] is reserved for the
     * explicit scan button.
     */
    suspend fun hasRootGrant(): Boolean = runCatching {
        Root.check(request = false).granted
    }.getOrDefault(false)

    suspend fun root(
        context: Context,
        requestPermission: Boolean,
        force: Boolean = false,
    ): Result<List<LaunchableApp>> = rootMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && !requestPermission) {
            rootCache?.takeIf { now - it.createdAt in 0 until rootTtlMs }?.let {
                return@withLock Result.success(it.apps)
            }
        }
        val result = RootApps.scan(context, requestPermission)
        result.onSuccess { apps ->
            rootCache = Cache(now, apps)
        }
        result
    }

    fun invalidateRoot() {
        rootCache = null
    }

    suspend fun label(context: Context, target: String): String = withContext(Dispatchers.IO) {
        rootCache?.apps?.firstOrNull { it.id == target }?.label
            ?: LaunchableApps.label(context, target)
    }

    /**
     * Normalizes a manual target. The current profile keeps the legacy bare package; other
     * profiles use `package@userId`. A current-profile-only picker rejects another profile
     * instead of silently rewriting it.
     */
    fun normalizeTarget(raw: String, scope: com.flowglass.engine.ApplicationScope): Result<String> = runCatching {
        val value = raw.trim()
        require(value.isNotEmpty()) { "请输入应用包名" }
        val (packageName, userId) = LaunchableApps.parseLaunchTarget(value)
        require(packagePattern.matches(packageName)) { "包名格式不正确" }
        if (scope == com.flowglass.engine.ApplicationScope.CURRENT_PROFILE) {
            require(userId == null || userId == 0) { "这个参数只能选择当前资料的应用" }
        }
        if (userId == null || userId == 0) packageName else "$packageName@$userId"
    }
}

internal enum class ApplicationFilter {
    ALL,
    MAIN,
    CLONES,
}

/** The automatic path is allowed only with a remembered grant and a reachable root binary. */
internal fun shouldAutoScanRoot(
    scope: com.flowglass.engine.ApplicationScope,
    rootAvailable: Boolean,
    rememberedGrant: Boolean,
): Boolean =
    scope == com.flowglass.engine.ApplicationScope.ALL_PROFILES && rootAvailable && rememberedGrant

internal data class ApplicationGroup(val title: String, val apps: List<LaunchableApp>)

/** Pure query/grouping helpers so the page stays testable without a live PackageManager. */
internal fun filterApplications(
    apps: List<LaunchableApp>,
    query: String,
    filter: ApplicationFilter,
): List<LaunchableApp> {
    val needle = query.trim()
    return apps
        .filter { app ->
            when (filter) {
                ApplicationFilter.ALL -> true
                ApplicationFilter.MAIN -> app.userId == 0
                ApplicationFilter.CLONES -> app.userId != 0
            }
        }
        .filter { app ->
            needle.isEmpty() ||
                app.label.contains(needle, ignoreCase = true) ||
                app.packageName.contains(needle, ignoreCase = true) ||
                app.id.contains(needle, ignoreCase = true) ||
                app.profileLabel.contains(needle, ignoreCase = true)
        }
        .sortedWith(compareBy<LaunchableApp> { groupRank(it) }
            .thenBy { it.label.lowercase(Locale.ROOT) }
            .thenBy { it.userId }
            .thenBy { it.packageName })
}

internal fun groupApplications(apps: List<LaunchableApp>): List<ApplicationGroup> =
    apps.groupBy { app ->
        when {
            app.userId == 0 -> "当前资料"
            isWorkProfile(app) -> "工作资料"
            else -> "分身 · ${profileName(app)}"
        }
    }.entries
        .sortedWith(compareBy<Map.Entry<String, List<LaunchableApp>>> { groupTitleRank(it.key) }
            .thenBy { it.key })
        .map { (title, values) -> ApplicationGroup(title, values) }

/** A current-profile-only picker never even receives another profile's entries. */
internal fun applicationsForScope(
    apps: List<LaunchableApp>,
    scope: com.flowglass.engine.ApplicationScope,
): List<LaunchableApp> =
    if (scope == com.flowglass.engine.ApplicationScope.CURRENT_PROFILE) apps.filter { it.userId == 0 }
    else apps

internal fun isSelectedApplication(app: LaunchableApp, current: String): Boolean =
    current == app.id || (app.userId == 0 && current == app.packageName)

internal fun profileName(app: LaunchableApp): String =
    app.profileLabel.removePrefix("Root · ").ifBlank { "用户 ${app.userId}" }

private fun isWorkProfile(app: LaunchableApp): Boolean =
    app.userId == 10 || app.profileLabel.contains("work", ignoreCase = true) ||
        app.profileLabel.contains("工作")

private fun groupRank(app: LaunchableApp): Int = when {
    app.userId == 0 -> 0
    isWorkProfile(app) -> 1
    else -> 2
}

private fun groupTitleRank(title: String): Int = when {
    title == "当前资料" -> 0
    title == "工作资料" -> 1
    else -> 2
}
