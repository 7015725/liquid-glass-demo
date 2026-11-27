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
    val blockPath: List<Long> = emptyList(),
    /** True while an unsaved component draft was open; the draft itself has no stable id. */
    val draft: Boolean = false,
)

internal val NavSnapshotSaver: Saver<NavSnapshot, Any> = mapSaver(
    save = { nav ->
        mapOf(
            "tab" to nav.tab,
            "editing" to nav.editing,
            "actionCmd" to nav.actionCmd,
            "action" to nav.action,
            "settingsPage" to nav.settingsPage,
            "blockPath" to nav.blockPath.joinToString(","),
            "draft" to nav.draft,
        )
    },
    restore = { values ->
        NavSnapshot(
            tab = (values["tab"] as? Number)?.toInt() ?: 0,
            editing = (values["editing"] as? Number)?.toLong() ?: 0L,
            actionCmd = (values["actionCmd"] as? Number)?.toLong() ?: 0L,
            action = (values["action"] as? Number)?.toLong() ?: 0L,
            settingsPage = (values["settingsPage"] as? Number)?.toInt() ?: 0,
            blockPath = (values["blockPath"] as? String)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
            draft = values["draft"] as? Boolean ?: false,
        )
    },
)

/**
 * Reopens the saved editor/action page, but only while the id still exists in [state].
 * Existing in-memory navigation wins: a configuration change already keeps [AppState] alive.
 */
internal fun restoreNavigation(state: AppState, saved: NavSnapshot): NavSnapshot {
    val processRestore = state.editing == null
    val liveBlockPath = state.blockPath
    val savedEditing = saved.editing.takeIf { it > 0L && state.find(it) != null }
    if (state.editing == null) savedEditing?.let(state::openEditor)

    val lostDraft = processRestore && saved.draft
    val actionCmd = if (lostDraft) null else state.actionEditingCmd
        ?.takeIf { state.find(it) != null }
        ?: if (processRestore) saved.actionCmd.takeIf { it > 0L && state.find(it) != null } else null
        ?: state.editing
    val action = if (lostDraft) null else state.actionEditing
        ?.takeIf { id -> actionCmd != null && state.itemById(actionCmd, id) != null }
        ?: if (processRestore) saved.action.takeIf { id -> actionCmd != null && id > 0L && state.itemById(actionCmd, id) != null } else null
    if (actionCmd != null && action != null) {
        state.openEditor(actionCmd)
        state.actionEditingCmd = actionCmd
        state.actionEditing = action
    }
    // On Activity recreation AppState is already alive: a path the user opened after the last
    // saved snapshot must win over that stale snapshot. A process restore has no in-memory path,
    // so it falls back to the saved one.
    // A lost draft opened from a block page must not land on that block page: the notice
    // is rendered by the command editor root, so drop the block path and stay at the root.
    state.blockPath = state.validBlockPath(
        if (lostDraft) emptyList() else if (processRestore) saved.blockPath else liveBlockPath,
    )
    if (lostDraft) {
        // A new component draft has no stable id, and an edited saved component silently reopens
        // from disk. Tell the user the draft is gone instead of presenting the saved version as theirs.
        state.editorNotice = "未保存的组件草稿已丢失，已恢复上次保存的版本"
    }

    val savedPage = saved.settingsPage.takeIf { it in SettingsPage.KeepAlive..SettingsPage.Logs }
    if (state.settingsPage == SettingsPage.Main && savedPage != null) state.settingsPage = savedPage

    return saved.copy(
        tab = saved.tab.takeIf { it in 0..3 } ?: 0,
        editing = state.editing ?: 0L,
        actionCmd = actionCmd ?: 0L,
        action = action ?: 0L,
        settingsPage = state.settingsPage,
        blockPath = state.blockPath,
    )
}
