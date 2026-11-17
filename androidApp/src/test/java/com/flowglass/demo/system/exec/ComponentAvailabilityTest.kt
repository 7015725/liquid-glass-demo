package com.flowglass.demo.system.exec

import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ExecutionLayer
import com.flowglass.engine.LayerSupport
import com.flowglass.engine.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentAvailabilityTest {
    private val xposedOnly = listOf(LayerSupport(ExecutionLayer.XPOSED))
    private val rootOnly = listOf(LayerSupport(ExecutionLayer.ROOT))
    private val normalOnly = listOf(LayerSupport(ExecutionLayer.NORMAL))
    private val mixed = listOf(LayerSupport(ExecutionLayer.XPOSED), LayerSupport(ExecutionLayer.ROOT))

    private val systemTrigger = meta("trigger.app_process", "应用进程启动/结束", xposedOnly, Tier.XPOSED, ComponentKind.TRIGGER)
    private val methodHook = meta("trigger.method_hook", "方法钩子", xposedOnly, Tier.XPOSED, ComponentKind.TRIGGER)
    private val rootAction = meta("action.force_stop", "强制停止应用", rootOnly, Tier.ROOT)
    private val normalAction = meta("action.http_request", "HTTP 请求", normalOnly)

    private fun meta(
        typeId: String,
        name: String,
        supports: List<LayerSupport>,
        minTier: Tier = Tier.NORMAL,
        kind: ComponentKind = ComponentKind.ACTION,
    ) = ComponentMeta(typeId, name, "应用", kind, supports = supports, minTier = minTier)

    private fun snapshot(root: LayerState, lsposed: LayerState) = LayerStatusSnapshot(
        normal = LayerState.READY,
        root = root,
        lsposed = lsposed,
    )

    private fun locked(reason: String, layers: Set<ExecutionLayer>) =
        ComponentAvailability.Locked(layers, reason)

    @Test
    fun everyRootAndLsposedCombinationHasOnePolicy() {
        for (root in listOf(LayerState.READY, LayerState.UNAVAILABLE)) {
            for (lsposed in listOf(LayerState.UNAVAILABLE, LayerState.DEGRADED, LayerState.READY)) {
                val snap = snapshot(root, lsposed)
                val at = "root=$root lsposed=$lsposed"

                val expectedSystem = when (lsposed) {
                    LayerState.READY -> ComponentAvailability.Available
                    LayerState.DEGRADED -> ComponentAvailability.AvailableWithHint("系统钩子未就绪")
                    LayerState.UNAVAILABLE -> locked("需 LSPosed", setOf(ExecutionLayer.XPOSED))
                }
                val expectedMethod = if (lsposed == LayerState.UNAVAILABLE) {
                    locked("需 LSPosed", setOf(ExecutionLayer.XPOSED))
                } else {
                    ComponentAvailability.Available
                }
                val expectedRoot = if (root == LayerState.READY) {
                    ComponentAvailability.Available
                } else {
                    locked("需 Root", setOf(ExecutionLayer.ROOT))
                }

                assertEquals("$at: 系统事件", expectedSystem, componentAvailability(systemTrigger, snap))
                assertEquals("$at: 方法钩子", expectedMethod, componentAvailability(methodHook, snap))
                assertEquals("$at: Root 动作", expectedRoot, componentAvailability(rootAction, snap))
                assertEquals("$at: 普通动作", ComponentAvailability.Available, componentAvailability(normalAction, snap))
            }
        }
    }

    @Test
    fun methodHookIsAvailableWithOnlyLsposedAndNoRoot() {
        assertEquals(
            ComponentAvailability.Available,
            componentAvailability(methodHook, snapshot(LayerState.UNAVAILABLE, LayerState.DEGRADED)),
        )
    }

    @Test
    fun systemTriggerWithOnlyRootStaysLockedForLsposed() {
        val actual = componentAvailability(systemTrigger, snapshot(LayerState.READY, LayerState.UNAVAILABLE))
        assertEquals(locked("需 LSPosed", setOf(ExecutionLayer.XPOSED)), actual)
    }

    @Test
    fun rootOnlyActionIsAvailableWithOnlyRootAndNoLsposed() {
        assertEquals(
            ComponentAvailability.Available,
            componentAvailability(rootAction, snapshot(LayerState.READY, LayerState.UNAVAILABLE)),
        )
    }

    @Test
    fun mixedComponentNeedsAnyOneReadyLayer() {
        val mixedMeta = meta("trigger.mixed", "混合层", mixed)
        assertEquals(
            ComponentAvailability.Available,
            componentAvailability(mixedMeta, snapshot(LayerState.READY, LayerState.UNAVAILABLE)),
        )
        assertEquals(
            ComponentAvailability.Available,
            componentAvailability(mixedMeta, snapshot(LayerState.UNAVAILABLE, LayerState.READY)),
        )
        assertEquals(
            locked("需 LSPosed 或 Root", setOf(ExecutionLayer.XPOSED, ExecutionLayer.ROOT)),
            componentAvailability(mixedMeta, snapshot(LayerState.UNAVAILABLE, LayerState.UNAVAILABLE)),
        )
    }

    @Test
    fun degradedRootCannotPickRootOnlyAction() {
        val actual = componentAvailability(rootAction, snapshot(LayerState.DEGRADED, LayerState.READY))
        assertEquals(locked("需 Root", setOf(ExecutionLayer.ROOT)), actual)
    }

    @Test
    fun everyXposedTierComponentDeclaresTheXposedLayer() {
        BuiltInComponents.registry().all()
            .filter { it.minTier == Tier.XPOSED }
            .forEach { meta ->
                assertTrue(
                    "${meta.typeId} 的 minTier 是 XPOSED，但 supports 没有声明 LSPosed 层",
                    meta.supports.any { it.layer == ExecutionLayer.XPOSED },
                )
            }
    }
}
