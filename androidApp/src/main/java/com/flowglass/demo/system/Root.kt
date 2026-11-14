package com.flowglass.demo.system

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.flowglass.demo.system.exec.ProcessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class RootInfo(
    val suFound: Boolean,
    val granted: Boolean,
    val uidLine: String,
    val manager: String,
    val suPath: String,
)

/**
 * Remembers the last successful grant so a reopened app shows the same state instead of asking
 * the root manager again. It is only a cache: `refresh`, a denied execution or a missing `su`
 * drop it, and nothing is ever assumed without one of those signals.
 */
internal interface RootGrantStore {
    fun load(): RootInfo?
    fun save(info: RootInfo)
    fun clear()

    object None : RootGrantStore {
        override fun load(): RootInfo? = null
        override fun save(info: RootInfo) = Unit
        override fun clear() = Unit
    }
}

internal class PrefsRootGrantStore(private val prefs: SharedPreferences) : RootGrantStore {
    override fun load(): RootInfo? {
        if (!prefs.getBoolean(KEY_GRANTED, false)) return null
        val suPath = prefs.getString(KEY_SU_PATH, null) ?: return null
        return RootInfo(
            suFound = true,
            granted = true,
            uidLine = prefs.getString(KEY_UID, "uid=0(root)") ?: "uid=0(root)",
            manager = prefs.getString(KEY_MANAGER, "—") ?: "—",
            suPath = suPath,
        )
    }

    override fun save(info: RootInfo) {
        prefs.edit {
            putBoolean(KEY_GRANTED, info.granted)
            putString(KEY_UID, info.uidLine)
            putString(KEY_MANAGER, info.manager)
            putString(KEY_SU_PATH, info.suPath)
        }
    }

    override fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val KEY_GRANTED = "granted"
        const val KEY_UID = "uid_line"
        const val KEY_MANAGER = "manager"
        const val KEY_SU_PATH = "su_path"
    }
}

object Root {
    @Volatile private var access = RootAccess(::findSu, ::execute)
    private val paths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/system_ext/bin/su", "/vendor/bin/su",
    )

    private suspend fun execute(arguments: List<String>, timeoutMs: Long): ProcessResult =
        if (arguments.size == 3 && arguments[1] == "-c") {
            com.flowglass.demo.system.exec.BoundedProcess.runRoot(arguments[0], arguments[2], timeoutMs)
        } else {
            com.flowglass.demo.system.exec.BoundedProcess.run(arguments, timeoutMs)
        }

    /** Called once from the Activity: the grant survives process death from here on. */
    fun install(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("root_state", Context.MODE_PRIVATE)
        access = RootAccess(::findSu, ::execute).apply { store = PrefsRootGrantStore(prefs) }
    }

    internal suspend fun runProcess(arguments: List<String>, timeoutMs: Long = 30_000) = access.runProcess(arguments, timeoutMs)
    suspend fun check(request: Boolean, refresh: Boolean = false): RootInfo = access.check(request, refresh)

    fun findSu(): String? = paths.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
}

/** Execution seam keeps authorization policy independently testable without su. */
internal class RootAccess(
    private val locateSu: () -> String?,
    private val execute: suspend (List<String>, Long) -> ProcessResult,
) {
    var store: RootGrantStore = RootGrantStore.None
    private val mutex = Mutex()
    private var cached: RootInfo? = null

    suspend fun runProcess(arguments: List<String>, timeoutMs: Long = 30_000): ProcessResult {
        val authorization = mutex.withLock { cached }
        try {
            return execute(arguments, timeoutMs)
        } catch (denied: com.flowglass.demo.system.exec.RootPermissionDeniedException) {
            mutex.withLock {
                // An older in-flight failure must not evict a newer grant.
                if (cached === authorization && cached?.suPath == arguments.firstOrNull()) {
                    cached = null
                    store.clear()
                }
            }
            throw denied // Never retry a potentially side-effecting command.
        }
    }

    private suspend fun run(vararg cmd: String, timeoutSec: Long = 12): String? = try {
        val result = execute(cmd.toList(), timeoutSec * 1000)
        if (!result.timedOut && result.exitCode == 0) result.output.trim() else null
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /**
     * Without [request] only probes, and reuses a remembered grant: `su -v` never shows a prompt.
     * With it runs `su -c id`, which is the only reliable signal and may show the manager's prompt.
     */
    suspend fun check(request: Boolean, refresh: Boolean = false): RootInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            val remembered = if (refresh) null else cached ?: runCatching { store.load() }.getOrNull()
            // A grant proven earlier (this process or the persisted copy) is reused without
            // touching su: `su -v`/`su -c id` may surface the manager's prompt.
            if (remembered?.granted == true && suAvailable(remembered)) {
                cached = remembered
                return@withLock remembered
            }

            val located = locateSu()
            val rememberedPath = remembered?.suPath?.takeIf { it != "su" && it != "未找到" && File(it).exists() }
            val binary = located ?: rememberedPath ?: "su"
            val version = run(binary, "-v", timeoutSec = 4)
            val suFound = located != null || version != null
            val id = if (request && suFound) run(binary, "-c", "id") else null
            val manager = when {
                version == null -> if (suFound) "未知" else "—"
                version.contains("MAGISK", true) -> "Magisk ${version.substringBefore(':')}"
                version.contains("KernelSU", true) -> "KernelSU ${version.substringBefore(':')}"
                version.contains("APatch", true) -> "APatch ${version.substringBefore(':')}"
                else -> version.take(24)
            }
            val freshGrant = id?.let { UID_ROOT.containsMatchIn(it) } == true
            val info = RootInfo(
                suFound = suFound,
                granted = freshGrant,
                uidLine = id?.substringBefore(' ') ?: "—",
                manager = manager,
                suPath = located ?: if (version != null) binary else "未找到",
            )
            if (info.granted) {
                cached = info
                store.save(info)
            } else if (request || refresh || !suFound || remembered?.granted == true) {
                // A real check proves revocation; a vanished su invalidates the remembered
                // grant; a passive probe with nothing remembered leaves the store alone.
                cached = null
                store.clear()
            }
            info
        }
    }

    /** Cheap existence check so a stale remembered path cannot keep claiming a grant. */
    private fun suAvailable(info: RootInfo): Boolean =
        locateSu() != null ||
            (info.suPath != "su" && info.suPath != "未找到" && runCatching { File(info.suPath).exists() }.getOrDefault(false))

    private companion object {
        val UID_ROOT = Regex("""(?:^|\s)uid=0(?:\(|\s|$)""")
    }
}
