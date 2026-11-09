package com.flowglass.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.flowglass.demo.liquid.LiquidBottomTab
import com.flowglass.demo.liquid.LiquidBottomTabs
import com.flowglass.demo.liquid.LiquidToggle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
fun FlowGlassApp() {
    val backdrop = rememberLayerBackdrop()
    var tab by remember { mutableIntStateOf(0) }
    var on by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.layerBackdrop(backdrop).fillMaxSize().background(C.Bg).statusBarsPadding().padding(16.dp)
        ) {
            T("流光自动化", 32.sp32())
            Box(Modifier.size(96.dp).clip(PolygonShape(M3EShapes.Clover4)).background(C.Primary))
            LiquidToggle({ on }, { on = it }, backdrop)
        }
        LiquidBottomTabs({ tab }, { tab = it }, backdrop, 4,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)) {
            repeat(4) { i ->
                LiquidBottomTab({ tab = i }) {
                    LineIcon(Ic.entries[i], C.OnSurfaceVar)
                    T("页 $i")
                }
            }
        }
    }
}

private fun Int.sp32() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
