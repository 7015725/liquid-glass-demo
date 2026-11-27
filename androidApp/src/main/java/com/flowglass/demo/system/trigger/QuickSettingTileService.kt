package com.flowglass.demo.system.trigger

import android.service.quicksettings.TileService
import com.flowglass.engine.trigger.TriggerEvent
import com.flowglass.engine.trigger.TriggerKinds
import com.flowglass.engine.trigger.TriggerSource

/** The tile is a user-owned entry point; it never reads or changes other apps' tiles. */
class QuickSettingTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "流光"
            state = android.service.quicksettings.Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val event = TriggerEvent(
            kind = TriggerKinds.QUICK_SETTING,
            source = TriggerSource.TILE,
            atEpochMs = System.currentTimeMillis(),
        )
        if (!TriggerService.deliverIfRunning(event)) {
            TriggerService.start(this, TriggerService.ACTION_TILE)
        }
    }
}
