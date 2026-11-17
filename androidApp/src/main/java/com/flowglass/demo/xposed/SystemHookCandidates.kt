package com.flowglass.demo.xposed

/** Where the event object is carried by a candidate method. */
internal enum class HookEventSource {
    /** The first argument is the ActivityRecord (ActivityTaskManagerService/Task/TaskFragment). */
    FIRST_ARGUMENT_ACTIVITY,

    /** Called on the ActivityRecord itself; the first argument is the target state. */
    THIS_ACTIVITY_STATE,

    /** The second argument is an ApplicationInfo (ActivityManagerService#startProcessLocked). */
    SECOND_ARGUMENT_APPLICATION,

    /** The first argument is a ProcessRecord (AnrHelper#appNotResponding). */
    FIRST_ARGUMENT_PROCESS,

    /** The second argument is a ProcessRecord (ActivityManagerService#handleApplicationCrashInner). */
    SECOND_ARGUMENT_PROCESS,

    /** The first argument is a ProcessRecord (ActivityManagerService#cleanUpApplicationRecordLocked). */
    CLEANUP_PROCESS_RECORD,
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
        HookCandidate(
            label = "ams.cleanUpApplicationRecordLocked",
            className = "com.android.server.am.ActivityManagerService",
            methodName = "cleanUpApplicationRecordLocked",
            source = HookEventSource.CLEANUP_PROCESS_RECORD,
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
