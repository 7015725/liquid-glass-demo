package com.flowglass.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.flowglass.demo.system.KeepAlive
import com.flowglass.demo.system.SysTick
import com.flowglass.demo.system.trigger.TriggerService
import com.flowglass.demo.ui.AppearanceSettings
import com.flowglass.demo.ui.C
import com.flowglass.demo.ui.FlowGlassApp
import com.flowglass.demo.ui.isSystemDark
import com.flowglass.demo.ui.resolveDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Seed the palette before the first frame to avoid a light flash on a dark launch.
        C.dark = AppearanceSettings.read(this).resolveDark(isSystemDark())
        setContent { FlowGlassApp() }
    }

    override fun onResume() {
        super.onResume()
        KeepAlive.ensure(this)
        TriggerService.ensureStarted(this, allowLocationUpgrade = true)
        SysTick.tick++
    }
}
