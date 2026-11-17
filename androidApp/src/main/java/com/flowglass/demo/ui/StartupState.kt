package com.flowglass.demo.ui

import com.flowglass.demo.system.SysTick
import com.flowglass.demo.system.trigger.TriggerStatus

/**
 * Forces the object-level Compose state holders to be initialized on the Android main thread.
 *
 * [C], [SysTick], [TriggerStatus] and [PermissionGate] own `mutableStateOf` fields. Kotlin initializes
 * objects lazily on the first access; if that access happens in a service, receiver or IO
 * coroutine, the state object is born in a worker snapshot and the first composition cannot
 * read it. Application.onCreate always runs on main, so touching the holders here makes the
 * construction order deterministic.
 */
internal fun primeComposeStateOnMainThread() {
    // Property reads are the side effects that trigger the singleton initializers.
    if (C.dark) Unit
    if (SysTick.tick != 0) Unit
    if (TriggerStatus.serviceActive) Unit
    if (PermissionGate.pending != null) Unit
}
