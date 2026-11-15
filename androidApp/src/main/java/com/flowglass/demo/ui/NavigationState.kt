package com.flowglass.demo.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver

/**
 * Navigation state that must survive Activity recreation and process death.
 *
 * Only ids are persisted. Commands and action items are looked up again from the persisted
 * [AppState] after restoration, so a stale id can never resurrect an object that was deleted.
 */
internal data class NavSnapshot(
    val tab: Int = 0,
    val editing: Long = 0L,
    val actionCmd: Long = 0L,
    val action: Long = 0L,
    val settingsPage: Int = 0,
)

internal val NavSnapshotSaver: Saver<NavSnapshot, Any> = mapSaver(
    save = { nav ->
        mapOf(
            "tab" to nav.tab,
            "editing" to nav.editing,
            "actionCmd" to nav.actionCmd,
            "action" to nav.action,
            "settingsPage" to nav.settingsPage,
        )
    },
    restore = { values ->
        NavSnapshot(
            tab = (values["tab"] as? Number)?.toInt() ?: 0,
            editing = (values["editing"] as? Number)?.toLong() ?: 0L,
            actionCmd = (values["actionCmd"] as? Number)?.toLong() ?: 0L,
            action = (values["action"] as? Number)?.toLong() ?: 0L,
            settingsPage = (values["settingsPage"] as? Number)?.toInt() ?: 0,
        )
    },
)

/**
 * Reopens the saved editor/action page, but only while the id still exists in [state].
 * Existing in-memory navigation wins: a configuration change already keeps [AppState] alive.
 */
internal fun restoreNavigation(state: AppState, saved: NavSnapshot): NavSnapshot {
    val processRestore = state.editing == null
    val savedEditing = saved.editing.takeIf { it > 0L && state.find(it) != null }
    if (state.editing == null) savedEditing?.let(state::openEditor)

    val actionCmd = state.actionEditingCmd
        ?.takeIf { state.find(it) != null }
        ?: if (processRestore) saved.actionCmd.takeIf { it > 0L && state.find(it) != null } else null
        ?: state.editing
    val action = state.actionEditing
        ?.takeIf { id -> actionCmd != null && state.itemById(actionCmd, id) != null }
        ?: if (processRestore) saved.action.takeIf { id -> actionCmd != null && id > 0L && state.itemById(actionCmd, id) != null } else null
    if (actionCmd != null && action != null) {
        state.openEditor(actionCmd)
        state.actionEditingCmd = actionCmd
        state.actionEditing = action
    }

    val savedPage = saved.settingsPage.takeIf { it in SettingsPage.KeepAlive..SettingsPage.Appearance }
    if (state.settingsPage == SettingsPage.Main && savedPage != null) state.settingsPage = savedPage

    return saved.copy(
        tab = saved.tab.takeIf { it in 0..3 } ?: 0,
        editing = state.editing ?: 0L,
        actionCmd = actionCmd ?: 0L,
        action = action ?: 0L,
        settingsPage = state.settingsPage,
    )
}
