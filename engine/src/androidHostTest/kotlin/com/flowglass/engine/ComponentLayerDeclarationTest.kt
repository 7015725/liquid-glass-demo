package com.flowglass.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural guard: every built-in component must declare how it executes. This is intentionally
 * a loop over the registry rather than a hard-coded count, so a newly added component is checked
 * automatically.
 */
class ComponentLayerDeclarationTest {
    @Test
    fun everyComponentDeclaresAtLeastOneImplementation() {
        BuiltInComponents.all.forEach { meta ->
            assertTrue(meta.supports.isNotEmpty(), "${meta.typeId} 没有声明执行层")
            assertEquals(
                meta.supports.size,
                meta.supports.map { it.layer }.distinct().size,
                "${meta.typeId} 重复声明了执行层",
            )
        }
    }

    @Test
    fun engineInternalComponentsOnlyDeclareNormal() {
        BuiltInComponents.all
            .filter { it.kind == ComponentKind.CONDITION || it.block != null }
            .forEach { meta ->
                assertEquals(
                    listOf(ExecutionLayer.NORMAL),
                    meta.supports.map { it.layer },
                    "${meta.typeId} 是 engine 内部组件，只能声明普通层",
                )
            }
    }

    @Test
    fun everyActionAndTriggerPublishesAnExecutableLayer() {
        // The process/crash triggers are deliberately XPOSED-only: they are system-server events
        // with no normal or Root fallback, and declaring a fake normal implementation would make
        // the editor promise behavior the app cannot deliver.
        val xposedOnlyTriggers = setOf(
            "trigger.app_process", "trigger.app_crash", "trigger.keyguard", "trigger.media",
            "trigger.foreground_service", "trigger.method_hook",
        )
        BuiltInComponents.all
            .filter { it.kind == ComponentKind.ACTION && it.block == null || it.kind == ComponentKind.TRIGGER }
            .forEach { meta ->
                assertTrue(
                    meta.supports.any { it.layer != ExecutionLayer.XPOSED } || meta.typeId in xposedOnlyTriggers,
                    "${meta.typeId} 只有 LSPosed 事件层，缺少可执行层",
                )
            }
    }

    @Test
    fun rootOnlyShellHasNoNormalFallback() {
        val shell = BuiltInComponents.registry().find("action.root_shell")!!
        assertEquals(listOf(ExecutionLayer.ROOT), shell.supports.map { it.layer })
        assertFalse(shell.supports.any { it.layer == ExecutionLayer.NORMAL })
    }

    @Test
    fun foregroundTriggerDeclaresForegroundApplicationCapability() {
        val foreground = BuiltInComponents.registry().find("trigger.app_foreground")!!
        assertEquals(ApplicationCapability.FOREGROUND_EVENTS, foreground.applicationCapability)
        assertTrue(foreground.supports.any { it.layer == ExecutionLayer.NORMAL })
    }
}
