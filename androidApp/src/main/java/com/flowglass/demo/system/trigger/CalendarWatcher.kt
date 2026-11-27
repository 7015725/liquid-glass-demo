package com.flowglass.demo.system.trigger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource

/** Observes calendar event changes; it never reads event titles, attendees or descriptions. */
internal class CalendarWatcher(
    private val context: Context,
    private val emit: (TriggerEvent) -> Unit,
) {
    private var observer: ContentObserver? = null

    fun start() {
        if (observer != null) return
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALENDAR not granted; calendar trigger inactive")
            return
        }
        val next = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                emit(
                    TriggerEvent(
                        kind = TriggerKinds.CALENDAR_CHANGED,
                        source = TriggerSource.CALENDAR,
                        atEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI,
                true,
                next,
            )
            observer = next
        }.onFailure { Log.w(TAG, "cannot observe calendar: ${it.message}") }
    }

    fun stop() {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    private companion object {
        const val TAG = "FlowCalendarTrigger"
    }
}
