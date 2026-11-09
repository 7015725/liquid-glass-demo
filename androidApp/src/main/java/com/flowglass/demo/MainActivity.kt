package com.flowglass.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.flowglass.demo.system.KeepAlive
import com.flowglass.demo.system.SysTick
import com.flowglass.demo.ui.FlowGlassApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { FlowGlassApp() }
    }

    override fun onResume() {
        super.onResume()
        KeepAlive.ensure(this)
        SysTick.tick++
    }
}
