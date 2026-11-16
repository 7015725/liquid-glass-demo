package com.flowglass.demo.system.exec

import com.flowglass.demo.system.RootInfo
import com.flowglass.demo.system.trigger.TriggerLayers
import com.flowglass.engine.BuiltInActions
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ExecutionLayer
import com.flowglass.engine.LayerSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural test for the host side: every declared action/trigger layer must have a handler.
 * It also fails on a handler that is not declared, so metadata and implementations cannot drift.
 */
class LayerImplementationCoverageTest {
    private val rootInfo = RootInfo(
        suFound = true,
        granted = true,
        uidLine = "uid=0(root)",
        manager = "KernelSU",
        suPath = "/system/bin/su",
    )

    private fun meta(
        type: String,
        kind: ComponentKind,
        vararg layers: ExecutionLayer,
    ) = ComponentMeta(
        typeId = type,
        name = type,
        category = "测试",
        kind = kind,
        supports = layers.map { LayerSupport(it) },
    )

    @Test
    fun everyDeclaredActionLayerHasHostImplementation() {
        BuiltInActions.metadata.forEach { meta ->
            assertTrue("${meta.typeId} 没有声明执行层", meta.supports.isNotEmpty())
            meta.supports.forEach { support ->
                assertTrue(
                    "${meta.typeId} 声明了 ${support.layer} 但没有 handler",
                    ActionLayers.hasImplementation(meta.typeId, support.layer),
                )
            }
        }
    }

    @Test
    fun noUndeclaredActionImplementationExists() {
        BuiltInActions.metadata.forEach { meta ->
            ExecutionLayer.entries.forEach { layer ->
                if (ActionLayers.hasImplementation(meta.typeId, layer)) {
                    assertTrue(
                        "${meta.typeId} 有 $layer handler 但没有声明",
                        meta.supports.any { it.layer == layer },
                    )
                }
            }
        }
    }

    @Test
    fun everyTriggerHasANormalProvider() {
        BuiltInComponents.triggers.forEach { meta ->
            assertTrue("${meta.typeId} 没有声明执行层", meta.supports.isNotEmpty())
            meta.supports.forEach { support ->
                assertTrue(
                    "${meta.typeId} 声明了 ${support.layer} 但没有 trigger provider",
                    TriggerLayers.hasImplementation(meta.typeId, support.layer),
                )
            }
        }
    }

    @Test
    fun actionsPreferRootThenNormal() {
        val resolver = LayerResolver {
            LayerStatusSnapshot(
                normal = LayerState.READY,
                root = LayerState.READY,
                rootInfo = rootInfo,
            )
        }
        val action = meta("action.test", ComponentKind.ACTION, ExecutionLayer.ROOT, ExecutionLayer.NORMAL)

        assertEquals(ExecutionLayer.ROOT, resolver.select(action)?.layer)
        assertEquals(rootInfo, resolver.select(action)?.rootInfo)
    }

    @Test
    fun rootOnlyActionFailsWhenRootIsUnavailable() {
        val resolver = LayerResolver {
            LayerStatusSnapshot(normal = LayerState.READY, root = LayerState.UNAVAILABLE)
        }
        val action = meta("action.root_only", ComponentKind.ACTION, ExecutionLayer.ROOT)

        assertNull(resolver.select(action))
        assertTrue(resolver.unavailableReason(action).contains("Root"))
    }

    @Test
    fun normalFallbackIsUsedWhenRootIsNotReady() {
        val resolver = LayerResolver {
            LayerStatusSnapshot(normal = LayerState.READY, root = LayerState.DEGRADED)
        }
        val action = meta("action.test", ComponentKind.ACTION, ExecutionLayer.ROOT, ExecutionLayer.NORMAL)

        assertEquals(ExecutionLayer.NORMAL, resolver.select(action)?.layer)
    }

    @Test
    fun triggersPreferLsposedThenRootThenNormal() {
        val trigger = meta(
            "trigger.test",
            ComponentKind.TRIGGER,
            ExecutionLayer.XPOSED,
            ExecutionLayer.ROOT,
            ExecutionLayer.NORMAL,
        )
        val lsposed = LayerResolver {
            LayerStatusSnapshot(
                normal = LayerState.READY,
                root = LayerState.READY,
                lsposed = LayerState.READY,
                rootInfo = rootInfo,
            )
        }
        val rootOnly = LayerResolver {
            LayerStatusSnapshot(
                normal = LayerState.READY,
                root = LayerState.READY,
                lsposed = LayerState.DEGRADED,
                rootInfo = rootInfo,
            )
        }
        val normalOnly = LayerResolver {
            LayerStatusSnapshot(
                normal = LayerState.READY,
                root = LayerState.UNAVAILABLE,
                lsposed = LayerState.UNAVAILABLE,
            )
        }

        assertEquals(ExecutionLayer.XPOSED, lsposed.select(trigger)?.layer)
        assertEquals(ExecutionLayer.ROOT, rootOnly.select(trigger)?.layer)
        assertEquals(ExecutionLayer.NORMAL, normalOnly.select(trigger)?.layer)
    }

    @Test
    fun undeclaredLayerIsNeverSelected() {
        val resolver = LayerResolver {
            LayerStatusSnapshot(
                normal = LayerState.READY,
                root = LayerState.READY,
                lsposed = LayerState.READY,
                rootInfo = rootInfo,
            )
        }
        val normalOnly = meta("action.normal", ComponentKind.ACTION, ExecutionLayer.NORMAL)

        assertEquals(ExecutionLayer.NORMAL, resolver.select(normalOnly)?.layer)
        assertFalse(resolver.select(normalOnly)?.layer == ExecutionLayer.ROOT)
    }
}
