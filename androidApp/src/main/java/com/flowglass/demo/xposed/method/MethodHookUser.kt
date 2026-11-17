package com.flowglass.demo.xposed.method

import android.os.Process
import android.os.UserHandle

/**
 * The public SDK exposes UserHandle but not its numeric id. UserHandle.hashCode() is the platform's
 * stable identifier used for this comparison; it is only used to match a configured clone id, not
 * for authorization of a human account.
 */
internal object MethodHookUser {
    fun idOfCurrentUser(): Int = Process.myUserHandle().hashCode()

    fun idOfUid(uid: Int): Int = UserHandle.getUserHandleForUid(uid).hashCode()
}
