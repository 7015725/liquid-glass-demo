package com.flowglass.demo.system.exec

import com.flowglass.demo.system.Root
import com.flowglass.demo.system.RootInfo
import com.flowglass.demo.xposed.ModuleStatus
import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ExecutionLayer
import com.flowglass.engine.LayerSupport

/** Availability of one implementation layer. A degraded layer is never selected. */
enum class LayerState {
    READY,
    DEGRADED,
    UNAVAILABLE,
}

/**
 * A snapshot of the three execution layers. This is intentionally Android-free data so the
 * resolver and its policy tests do not need a device or an emulator.
 */
data class LayerStatusSnapshot(
    val normal: LayerState = LayerState.READY,
    val root: LayerState = LayerState.UNAVAILABLE,
    val lsposed: LayerState = LayerState.UNAVAILABLE,
    val rootInfo: RootInfo? = null,
    val details: Map<ExecutionLayer, String> = emptyMap(),
) {
    fun stateOf(layer: ExecutionLayer): LayerState = when (layer) {
        ExecutionLayer.NORMAL -> normal
        ExecutionLayer.ROOT -> root
        ExecutionLayer.XPOSED -> lsposed
    }
}

data class LayerSelection(
    val layer: ExecutionLayer,
    val support: LayerSupport,
    val rootInfo: RootInfo? = null,
)

/**
 * Process-wide passive layer status. Root probing is cached by [Root.check]; the automatic path
 * only calls it with `request = false`, so opening settings never triggers a root prompt.
 */
object LayerStatus {
    @Volatile private var snapshot = LayerStatusSnapshot()

    fun current(): LayerStatusSnapshot = snapshot

    fun updateRoot(info: RootInfo?) {
        val state = when {
            info?.granted == true -> LayerState.READY
            info?.suFound == true -> LayerState.DEGRADED
            else -> LayerState.UNAVAILABLE
        }
        snapshot = snapshot.copy(
            root = state,
            rootInfo = info,
            details = snapshot.details + (ExecutionLayer.ROOT to rootDetail(info, state)),
        )
    }

    fun updateLsposed(active: Boolean, systemHookReady: Boolean) {
        val state = when {
            active && systemHookReady -> LayerState.READY
            active -> LayerState.DEGRADED
            else -> LayerState.UNAVAILABLE
        }
        val detail = when (state) {
            LayerState.READY -> "系统前台钩子已就绪"
            LayerState.DEGRADED -> "模块已激活，系统前台钩子未接入"
            LayerState.UNAVAILABLE -> "未检测到 LSPosed"
        }
        snapshot = snapshot.copy(
            lsposed = state,
            details = snapshot.details + (ExecutionLayer.XPOSED to detail),
        )
    }

    /** Re-probes Root passively and reads the LSPosed module marker. Never requests Root. */
    suspend fun refresh(requestRoot: Boolean = false, systemHookReady: Boolean = false): LayerStatusSnapshot {
        updateRoot(Root.check(request = requestRoot))
        val active = runCatching { ModuleStatus.isActive() }.getOrDefault(false)
        updateLsposed(active, systemHookReady = systemHookReady)
        return snapshot
    }

    private fun rootDetail(info: RootInfo?, state: LayerState): String = when (state) {
        LayerState.READY -> "已授权（${info?.manager ?: "Root"}）；执行时建立会话"
        LayerState.DEGRADED -> "已检测到 su，但尚未授权"
        LayerState.UNAVAILABLE -> "未检测到 su"
    }
}

/**
 * Chooses the first usable implementation declared by a component. Action handlers must not
 * contain their own root checks: they receive the selected layer from this resolver.
 */
class LayerResolver(
    private val statusProvider: () -> LayerStatusSnapshot = LayerStatus::current,
) {
    fun select(meta: ComponentMeta, kind: ComponentKind = meta.kind): LayerSelection? {
        val priority = when (kind) {
            ComponentKind.ACTION -> listOf(ExecutionLayer.ROOT, ExecutionLayer.NORMAL)
            ComponentKind.TRIGGER -> listOf(ExecutionLayer.XPOSED, ExecutionLayer.ROOT, ExecutionLayer.NORMAL)
            ComponentKind.CONDITION -> listOf(ExecutionLayer.NORMAL)
        }
        val status = statusProvider()
        return priority.firstNotNullOfOrNull { layer ->
            val support = meta.supports.firstOrNull { it.layer == layer } ?: return@firstNotNullOfOrNull null
            if (status.stateOf(layer) != LayerState.READY) return@firstNotNullOfOrNull null
            LayerSelection(
                layer = layer,
                support = support,
                rootInfo = if (layer == ExecutionLayer.ROOT) status.rootInfo else null,
            )
        }
    }

    /** Human-readable reasons for the last failed selection, used by the rule/status UI. */
    fun unavailableReason(meta: ComponentMeta, kind: ComponentKind = meta.kind): String {
        val status = statusProvider()
        return meta.supports.joinToString("；") { support ->
            val state = status.stateOf(support.layer)
            "${support.layer.label()}：${status.details[support.layer] ?: state.label()}"
        }.ifBlank { "没有声明执行层" }
    }
}

fun ExecutionLayer.label(): String = when (this) {
    ExecutionLayer.NORMAL -> "普通"
    ExecutionLayer.ROOT -> "Root"
    ExecutionLayer.XPOSED -> "LSPosed"
}

fun LayerState.label(): String = when (this) {
    LayerState.READY -> "可用"
    LayerState.DEGRADED -> "不可用（未就绪）"
    LayerState.UNAVAILABLE -> "不可用"
}
