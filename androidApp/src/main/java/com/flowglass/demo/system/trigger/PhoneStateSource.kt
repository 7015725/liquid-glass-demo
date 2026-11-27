package com.flowglass.demo.system.trigger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource

/**
 * Phone call state and the missed-call edge. A missed call is inferred only from
 * ringing -> idle without an off-hook transition; call-log contents are never read.
 */
internal class PhoneStateSource(
    private val context: Context,
    private val emit: (TriggerEvent) -> Unit,
) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: TelephonyCallback? = null
    private var listener: PhoneStateListener? = null
    @Volatile private var ringing = false
    private var firstState = true

    fun start() {
        if (callback != null || listener != null) return
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE not granted; phone triggers inactive")
            return
        }
        main.post {
            if (callback != null || listener != null) return@post
            firstState = true
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) {
                    val next = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) = handle(state)
                    }
                    telephony.registerTelephonyCallback(context.mainExecutor, next)
                    callback = next
                } else {
                    @Suppress("DEPRECATION")
                    val next = object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state)
                    }
                    @Suppress("DEPRECATION")
                    telephony.listen(next, PhoneStateListener.LISTEN_CALL_STATE)
                    listener = next
                }
            }.onFailure { Log.w(TAG, "cannot register phone state listener: ${it.message}") }
        }
    }

    fun stop() {
        main.post {
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) {
                    callback?.let(telephony::unregisterTelephonyCallback)
                } else {
                    @Suppress("DEPRECATION")
                    listener?.let { telephony.listen(it, PhoneStateListener.LISTEN_NONE) }
                }
            }
            callback = null
            listener = null
            ringing = false
            firstState = true
        }
    }

    private fun handle(state: Int) {
        val name = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            TelephonyManager.CALL_STATE_IDLE -> "idle"
            else -> return
        }
        if (firstState) {
            firstState = false
            ringing = name == "ringing"
            return
        }
        emit(
            TriggerEvent(
                kind = TriggerKinds.CALL_STATE,
                source = TriggerSource.PHONE,
                atEpochMs = System.currentTimeMillis(),
                data = mapOf("call.state" to name),
            ),
        )
        when (name) {
            "ringing" -> ringing = true
            "offhook" -> ringing = false
            "idle" -> if (ringing) {
                ringing = false
                emit(
                    TriggerEvent(
                        kind = TriggerKinds.MISSED_CALL,
                        source = TriggerSource.PHONE,
                        atEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private companion object {
        const val TAG = "FlowPhoneTrigger"
    }
}
