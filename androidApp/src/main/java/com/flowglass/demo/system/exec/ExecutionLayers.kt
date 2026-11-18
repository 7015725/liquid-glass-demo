package com.flowglass.demo.system.exec

import com.flowglass.demo.system.Root
import com.flowglass.demo.system.RootInfo
import com.flowglass.demo.xposed.ModuleStatus
import com.flowglass.engine.ComponentKind
import com.flowglass.engine.ComponentMeta
import com.flowglass.engine.ExecutionLayer
import com.flowglass.engine.LayerSupport
import com.flowglass.engine.trigger.TriggerTypes

/** Availability of one implementation layer. The resolver only selects READY; the picker may
 * allow a DEGRADED LSPosed module with a hint because its module is already active. */
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

/**
 * Whether the component picker should allow a component. [AvailableWithHint] is selectable but
 * tells the user that the selected layer is active yet its system hooks are not ready.
 * [Locked] remembers which layers the user has to activate so the row can open the matching
 * authorization page instead of doing nothing.
 */
sealed interface ComponentAvailability {
    data object Available : ComponentAvailability
    data class AvailableWithHint(val hint: String) : ComponentAvailability
    data class Locked(
        val requiredLayers: Set<ExecutionLayer>,
        val reason: String,
    ) : ComponentAvailability
}

/**
 * Picker-level availability. This deliberately does not compare [com.flowglass.engine.Tier]
 * ordinals: Root and LSPosed are independent capabilities, and a linear tier makes one of them
 * shadow the other. A component is selectable when any layer it declares is usable.
 *
 * NORMAL always stays selectable; missing runtime permissions are reported by the editor. ROOT
 * needs a granted root session. LSPosed is available for method hooks as soon as the module is
 * active (DEGRADED means active without a fresh system-hook heartbeat), while system-event
 * triggers stay selectable in that state with a hint.
 */
fun componentAvailability(
    meta: ComponentMeta,
    snapshot: LayerStatusSnapshot,
): ComponentAvailability = componentAvailability(meta.typeId, meta.supports, snapshot)

fun componentAvailability(
    typeId: String?,
    supports: List<LayerSupport>,
    snapshot: LayerStatusSnapshot,
): ComponentAvailability {
    val layers = supports.map { it.layer }.toSet()
    if (ExecutionLayer.NORMAL in layers) return ComponentAvailability.Available
    if (layers.isEmpty()) {
        return ComponentAvailability.Locked(emptySet(), "没有声明执行层")
    }
    if (layers.any { snapshot.stateOf(it) == LayerState.READY }) {
        return ComponentAvailability.Available
    }

    val lsposed = snapshot.stateOf(ExecutionLayer.XPOSED)
    // Method hooks only require the module to be active; the system-hook heartbeat is not used.
    if (typeId == TriggerTypes.APP_METHOD_CALL && lsposed != LayerState.UNAVAILABLE) {
        return ComponentAvailability.Available
    }
    // A system-event trigger can still be configured while the hook is loaded but not ready.
    if (ExecutionLayer.XPOSED in layers && lsposed == LayerState.DEGRADED) {
        return ComponentAvailability.AvailableWithHint("系统钩子未就绪")
    }

    val required = layers.filter { it != ExecutionLayer.NORMAL }.toSet()
    return ComponentAvailability.Locked(required, requiredLayersLabel(required))
}

private fun requiredLayersLabel(layers: Set<ExecutionLayer>): String {
    val labels = listOf(ExecutionLayer.XPOSED, ExecutionLayer.ROOT, ExecutionLayer.NORMAL)
        .filter { it in layers }
        .joinToString(" 或 ") { it.label() }
    return if (labels.isEmpty()) "没有可用执行层" else "需 $labels"
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

    /**
     * Re-probes Root passively and reads the LSPosed module marker. Never requests Root.
     *
     * [systemHookReady] deliberately has no default: a caller that forgets it would otherwise
     * downgrade an active LSPosed module to DEGRADED. Use
     * [com.flowglass.demo.system.SystemHookSettings.isAlive] as the single readiness check at
     * every call site.
     */
    suspend fun refresh(requestRoot: Boolean, systemHookReady: Boolean): LayerStatusSnapshot {
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
            ComponentKind.CONDITION -> listOf(ExecutionLayer.ROOT, ExecutionLayer.NORMAL)
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

/**
 * The highest usable layer for each execution path. Actions can use Root or Normal; triggers can
 * use LSPosed, Root, or Normal. The headline deliberately reports them separately instead of
 * claiming only the normal layer.
 */
data class LayerHeadline(
    val action: ExecutionLayer?,
    val event: ExecutionLayer?,
) {
    val actionLabel: String = action?.label() ?: "不可用"
    val eventLabel: String = event?.label() ?: "不可用"
    val normalOnly: Boolean = action == ExecutionLayer.NORMAL && event == ExecutionLayer.NORMAL
    val ok: Boolean = action != null || event != null

    val big: String = when {
        normalOnly -> "普通"
        action == null && event == null -> "不可用"
        action == event -> action?.label() ?: "不可用"
        else -> listOfNotNull(action?.label(), event?.label()).joinToString(" / ")
    }

    val unit: String = if (normalOnly) "层" else ""

    val pill: String = if (normalOnly) "普通可用" else "动作：$actionLabel · 事件：$eventLabel"

    companion object {
        fun of(snapshot: LayerStatusSnapshot): LayerHeadline {
            val action = when {
                snapshot.stateOf(ExecutionLayer.ROOT) == LayerState.READY -> ExecutionLayer.ROOT
                snapshot.stateOf(ExecutionLayer.NORMAL) == LayerState.READY -> ExecutionLayer.NORMAL
                else -> null
            }
            val event = when {
                snapshot.stateOf(ExecutionLayer.XPOSED) == LayerState.READY -> ExecutionLayer.XPOSED
                snapshot.stateOf(ExecutionLayer.ROOT) == LayerState.READY -> ExecutionLayer.ROOT
                snapshot.stateOf(ExecutionLayer.NORMAL) == LayerState.READY -> ExecutionLayer.NORMAL
                else -> null
            }
            return LayerHeadline(action, event)
        }
    }
}

fun LayerState.label(): String = when (this) {
    LayerState.READY -> "可用"
    LayerState.DEGRADED -> "不可用（未就绪）"
    LayerState.UNAVAILABLE -> "不可用"
}
