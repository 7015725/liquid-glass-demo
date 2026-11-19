package com.flowglass.demo.ui

/**
 * The parameter page edits a private copy. Nothing in the command changes until [AppState.saveComponentDraft]
 * runs, so backing out of a new component leaves the task list untouched.
 */
data class ComponentDraft(
    val cmdId: Long,
    val section: CommandSection,
    val item: CommandItem,
    val original: CommandItem?,
    val parentId: Long? = null,
    val branch: Int? = null,
    val category: Int? = null,
) {
    val isNew: Boolean get() = original == null
    val dirty: Boolean get() = item.parameter != original?.parameter || item.outputs != original?.outputs
}
