package com.flowglass.demo.xposed

/** Where the event object is carried by a candidate method. */
internal enum class HookEventSource {
    /** The first argument is the ActivityRecord (ActivityTaskManagerService/Task/TaskFragment). */
    FIRST_ARGUMENT_ACTIVITY,

    /** Called on the ActivityRecord itself; the first argument is the target state. */
    THIS_ACTIVITY_STATE,

    /** The second argument is an ApplicationInfo (ActivityManagerService#startProcessLocked). */
    SECOND_ARGUMENT_APPLICATION,

    /** The first argument is a boolean (KeyguardController#setKeyguardShown). */
    FIRST_ARGUMENT_BOOLEAN,

    /** The first argument is a MediaSessionRecordImpl, and the third is a PlaybackState. */
    FIRST_ARGUMENT_MEDIA_SESSION_WITH_PLAYBACK_STATE,

    /** The first argument is a MediaSessionRecordImpl (onSessionDied). */
    FIRST_ARGUMENT_MEDIA_SESSION,

    /** The first argument is a ServiceRecord (ActiveServices#setServiceForegroundInnerLocked). */
    FIRST_ARGUMENT_SERVICE_RECORD,

    /** The first argument is a ProcessRecord (AnrHelper#appNotResponding). */
    FIRST_ARGUMENT_PROCESS,

    /** The second argument is a ProcessRecord (ActivityManagerService#handleApplicationCrashInner). */
    SECOND_ARGUMENT_PROCESS,

    /**
     * A process-start method whose identity can be read from an ApplicationInfo or a
     * ProcessRecord depending on the overload. Android 10 moved the real startup into
     * ProcessList#startProcessLocked, which has both String+ApplicationInfo and
     * ProcessRecord/HostingRecord overloads.
     */
    PROCESS_START_ARGUMENTS,

    /** The first argument is a ProcessRecord (ActivityManagerService#cleanUpApplicationRecordLocked). */
    CLEANUP_PROCESS_RECORD,

    /**
     * The first argument is a ProcessRecord and the second is the `restarting` flag
     * (ActivityManagerService#handleAppDiedLocked).
     */
    PROCESS_DIED_RECORD,

    /** The first argument is a ClipData (ClipboardService#setPrimaryClip). */
    FIRST_ARGUMENT_CLIP,
}

internal data class HookCandidate(
    val label: String,
    val className: String,
    val methodName: String,
    val source: HookEventSource,
    val family: SystemHookFamily = SystemHookFamily.FOREGROUND,
    val kind: String = SystemHookProtocol.EVENT_FOREGROUND,
)

/**
 * AOSP methods that are called when the top resumed activity changes. The set is intentionally
 * version-aware; methods that do not exist on a ROM are reported as "hooks=0" instead of being
 * assumed to work.
 *
 * Sources checked (AOSP tags android-12.0.0_r1, android-13.0.0_r1, android-14.0.0_r1,
 * android-15.0.0_r1):
 *  - 12: ActivityTaskManagerService#setResumedActivityUncheckLocked, Task#setResumedActivity
 *  - 13: ActivityTaskManagerService#setResumedActivityUncheckLocked, TaskFragment#setResumedActivity
 *  - 14/15: ActivityTaskManagerService#setLastResumedActivityUncheckLocked,
 *    TaskFragment#setResumedActivity (Task extends TaskFragment since 13)
 *  - 12-15: ActivityRecord#setState(ActivityState/State, String), filtered to RESUMED
 *
 * realStartActivityLocked and onActivityAdded are deliberately absent: background starts also
 * hit them, so they are not proof that the activity is the top resumed one.
 */
internal object SystemHookCandidates {
    val resumed = listOf(
        HookCandidate(
            label = "atms.setResumedActivityUncheckLocked",
            className = "com.android.server.wm.ActivityTaskManagerService",
            methodName = "setResumedActivityUncheckLocked",
            source = HookEventSource.FIRST_ARGUMENT_ACTIVITY,
        ),
        HookCandidate(
            label = "atms.setLastResumedActivityUncheckLocked",
            className = "com.android.server.wm.ActivityTaskManagerService",
            methodName = "setLastResumedActivityUncheckLocked",
            source = HookEventSource.FIRST_ARGUMENT_ACTIVITY,
        ),
        HookCandidate(
            label = "task.setResumedActivity",
            className = "com.android.server.wm.Task",
            methodName = "setResumedActivity",
            source = HookEventSource.FIRST_ARGUMENT_ACTIVITY,
        ),
        HookCandidate(
            label = "taskFragment.setResumedActivity",
            className = "com.android.server.wm.TaskFragment",
            methodName = "setResumedActivity",
            source = HookEventSource.FIRST_ARGUMENT_ACTIVITY,
        ),
        HookCandidate(
            label = "activityRecord.setState",
            className = "com.android.server.wm.ActivityRecord",
            methodName = "setState",
            source = HookEventSource.THIS_ACTIVITY_STATE,
        ),
    )

    /**
     * Process start/end and app crash/ANR candidates. startProcessLocked is observed after it
     * returns a ProcessRecord; cleanUpApplicationRecordLocked is the actual process-cleanup path.
     * AnrHelper#appNotResponding receives the ProcessRecord on Android 12 through 15.
     */
    val process = listOf(
        HookCandidate(
            label = "ams.startProcessLocked",
            className = "com.android.server.am.ActivityManagerService",
            methodName = "startProcessLocked",
            source = HookEventSource.SECOND_ARGUMENT_APPLICATION,
            family = SystemHookFamily.PROCESS,
            kind = SystemHookProtocol.EVENT_PROCESS_STARTED,
        ),
        // Android 10/11 moved the actual process creation from ActivityManagerService into
        // ProcessList. The ActivityManagerService overload above is a thin compatibility wrapper
        // on those releases and is not on every activity-start path, so hooking only AMS silently
        // misses launches (observed on Lenovo L79031 / Android 10).
        HookCandidate(
            label = "processList.startProcessLocked",
            className = "com.android.server.am.ProcessList",
            methodName = "startProcessLocked",
            source = HookEventSource.PROCESS_START_ARGUMENTS,
            family = SystemHookFamily.PROCESS,
            kind = SystemHookProtocol.EVENT_PROCESS_STARTED,
        ),
        HookCandidate(
            label = "ams.cleanUpApplicationRecordLocked",
            className = "com.android.server.am.ActivityManagerService",
            methodName = "cleanUpApplicationRecordLocked",
            source = HookEventSource.CLEANUP_PROCESS_RECORD,
            family = SystemHookFamily.PROCESS,
            kind = SystemHookProtocol.EVENT_PROCESS_ENDED,
        ),
        // On Android 10/11 a process can be removed through this path even when the cleanup
        // method is called with a different argument layout. It is an additional read-only
        // detection point; the 750 ms per-package dedupe collapses the overlapping callbacks.
        HookCandidate(
            label = "ams.handleAppDiedLocked",
            className = "com.android.server.am.ActivityManagerService",
            methodName = "handleAppDiedLocked",
            source = HookEventSource.PROCESS_DIED_RECORD,
            family = SystemHookFamily.PROCESS,
            kind = SystemHookProtocol.EVENT_PROCESS_ENDED,
        ),
        HookCandidate(
            label = "ams.handleApplicationCrashInner",
            className = "com.android.server.am.ActivityManagerService",
            methodName = "handleApplicationCrashInner",
            source = HookEventSource.SECOND_ARGUMENT_PROCESS,
            family = SystemHookFamily.CRASH,
            kind = SystemHookProtocol.EVENT_APP_CRASH,
        ),
        HookCandidate(
            label = "anrHelper.appNotResponding",
            className = "com.android.server.am.AnrHelper",
            methodName = "appNotResponding",
            source = HookEventSource.FIRST_ARGUMENT_PROCESS,
            family = SystemHookFamily.CRASH,
            kind = SystemHookProtocol.EVENT_APP_ANR,
        ),
    )

    /**
     * Keyguard transitions. `setKeyguardShown(true)` is the lock edge; `false` is the unlock
     * edge. keyguardGoingAway is deliberately not treated as unlocked: it is a transition while
     * the keyguard may still be showing.
     */
    /**
     * Clipboard writes. Android 10/11 and 12+ have different argument layouts, so the payload
     * reader finds ClipData/package/user by type instead of fixed indexes.
     *
     * AOSP signatures checked 2026-11-27: android-11 setPrimaryClip(ClipData, String callingPackage,
     * int userId); android-14/15 setPrimaryClip(ClipData, String callingPackage,
     * String attributionTag, int userId, int deviceId).
     */
    val clipboard = listOf(
        HookCandidate(
            label = "clipboard.setPrimaryClip",
            className = "com.android.server.clipboard.ClipboardService",
            methodName = "setPrimaryClip",
            source = HookEventSource.FIRST_ARGUMENT_CLIP,
            family = SystemHookFamily.CLIPBOARD,
            kind = SystemHookProtocol.EVENT_CLIPBOARD_CHANGED,
        ),
    )

    val keyguard = listOf(
        HookCandidate(
            label = "keyguard.setKeyguardShown",
            className = "com.android.server.wm.KeyguardController",
            methodName = "setKeyguardShown",
            source = HookEventSource.FIRST_ARGUMENT_BOOLEAN,
            family = SystemHookFamily.KEYGUARD,
            kind = SystemHookProtocol.EVENT_KEYGUARD_LOCKED,
        ),
    )

    /**
     * Media-session playback transitions. AOSP 12–14 pass only (record, shouldUpdatePriority),
     * so the playback state is read from the record; newer releases may pass a PlaybackState
     * argument directly, which is preferred when present. onSessionDied is the explicit stop
     * edge. Both are filtered by package/user before they enter the queue.
     */
    val media = listOf(
        HookCandidate(
            label = "media.onSessionPlaybackStateChanged",
            className = "com.android.server.media.MediaSessionService",
            methodName = "onSessionPlaybackStateChanged",
            source = HookEventSource.FIRST_ARGUMENT_MEDIA_SESSION_WITH_PLAYBACK_STATE,
            family = SystemHookFamily.MEDIA,
            kind = SystemHookProtocol.EVENT_MEDIA_PLAYING,
        ),
        HookCandidate(
            label = "media.onSessionDied",
            className = "com.android.server.media.MediaSessionService",
            methodName = "onSessionDied",
            source = HookEventSource.FIRST_ARGUMENT_MEDIA_SESSION,
            family = SystemHookFamily.MEDIA,
            kind = SystemHookProtocol.EVENT_MEDIA_STOPPED,
        ),
    )

    /**
     * Foreground-service enter/exit. Argument #2 is the notification id; zero means the service
     * is leaving foreground mode. The notification itself is never read.
     */
    val foregroundService = listOf(
        HookCandidate(
            label = "services.setServiceForegroundInnerLocked",
            className = "com.android.server.am.ActiveServices",
            methodName = "setServiceForegroundInnerLocked",
            source = HookEventSource.FIRST_ARGUMENT_SERVICE_RECORD,
            family = SystemHookFamily.FOREGROUND_SERVICE,
            kind = SystemHookProtocol.EVENT_FOREGROUND_SERVICE_STARTED,
        ),
    )
}

internal data class HookIdentity(val packageName: String, val userId: Int) {
    val key: String get() = if (userId == 0) packageName else "$packageName@$userId"
}

/**
 * Validates values read from ActivityRecord/ProcessRecord fields. Missing values are rejected
 * rather than guessed; the caller logs and skips the event.
 */
internal object HookIdentityReader {
    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")

    fun of(packageName: String?, userId: Int?): HookIdentity? {
        val name = packageName?.takeIf { it.isNotEmpty() } ?: return null
        if (!PACKAGE_PATTERN.matches(name)) return null
        val user = userId?.takeIf { it in 0..999_999 } ?: return null
        return HookIdentity(name, user)
    }

    /** Parse the protected config's `package` or `package@userId` form. */
    fun fromKey(raw: String?): HookIdentity? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val at = value.lastIndexOf('@')
        return if (at < 0) {
            of(value, 0)
        } else {
            val user = value.substring(at + 1).toIntOrNull() ?: return null
            of(value.substring(0, at), user)
        }
    }

    fun isValidPackage(packageName: String): Boolean = PACKAGE_PATTERN.matches(packageName)
}
