package com.flowglass.demo.system.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flowglass.demo.system.a11y.A11yKeepAliveSettings
import com.flowglass.demo.xposed.a11y.A11yKeepAliveProtocol

/** Receives the protected status/loaded broadcasts from the system-server hook. */
class A11yKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            A11yKeepAliveProtocol.ACTION_LOADED -> {
                val hooks = intent.getIntExtra(A11yKeepAliveProtocol.EXTRA_HOOKS, -1)
                val supported = intent.getBooleanExtra(A11yKeepAliveProtocol.EXTRA_SUPPORTED, false)
                A11yKeepAliveSettings.markHookLoaded(context, hooks, supported)
                // Answer with the current switch and targets so a freshly started hook is in sync.
                A11yKeepAliveSettings.broadcastConfig(context)
            }

            A11yKeepAliveProtocol.ACTION_STATUS -> {
                val component = intent.getStringExtra(A11yKeepAliveProtocol.EXTRA_COMPONENT) ?: return
                val state = A11yKeepAliveSettings.State.fromWire(
                    intent.getStringExtra(A11yKeepAliveProtocol.EXTRA_STATE),
                ) ?: return
                val reason = intent.getStringExtra(A11yKeepAliveProtocol.EXTRA_REASON).orEmpty()
                val at = intent.getLongExtra(A11yKeepAliveProtocol.EXTRA_AT, System.currentTimeMillis())
                A11yKeepAliveSettings.recordStatus(context, component, state, reason, at)
                if (state == A11yKeepAliveSettings.State.REPAIRED) A11yKeepAliveSettings.bumpRestored(context)
            }
        }
    }
}
