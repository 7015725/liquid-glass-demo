package com.flowglass.demo.ui

/**
 * Where a variable is being inserted. [itemId] is null while adding a component; in that case the
 * item will be appended to the container identified by [parentId] and [branch].
 */
data class VariablePickerContext(
    val section: CommandSection,
    val itemId: Long? = null,
    val parentId: Long? = null,
    val branch: Int? = null,
)

/**
 * Resolves which component instances have already produced their outputs at the current editing
 * position. The picker must not offer an output from an action that runs later, and a condition
 * never sees action outputs because it is evaluated before the first action.
 */
internal object VariableAvailability {
    fun outputOwnerIds(cmd: Cmd, context: VariablePickerContext): Set<Long> {
        val triggerIds = cmd.triggers.mapTo(mutableSetOf()) { it.id }
        return when (context.section) {
            CommandSection.TRIGGERS -> emptySet()
            CommandSection.CONDITIONS -> {
                val parentId = context.parentId ?: return triggerIds
                val before = priorForParent(cmd.actions, parentId).orEmpty()
                triggerIds + before.flatMap(::subtreeIds)
            }
            CommandSection.ACTIONS -> {
                val prior = priorForAction(cmd.actions, context.itemId)
                    ?: priorForNewAction(cmd, context)
                triggerIds + prior.flatMap(::subtreeIds)
            }
        }
    }

    /**
     * Walks the action tree in execution order and returns everything before [targetId] on the
     * path to it. Ancestor block outputs are deliberately excluded: the block has not completed
     * while one of its own conditions or steps is being edited.
     */
    private fun priorForAction(items: List<CommandItem>, targetId: Long?): List<CommandItem>? {
        if (targetId == null) return null
        for ((index, item) in items.withIndex()) {
            if (item.id == targetId) return items.take(index)
            priorForAction(item.children, targetId)?.let { return items.take(index) + it }
            for (branch in item.branches) {
                priorForAction(branch.children, targetId)?.let { return items.take(index) + it }
            }
        }
        return null
    }

    private fun priorForNewAction(cmd: Cmd, context: VariablePickerContext): List<CommandItem> {
        val parentId = context.parentId ?: return cmd.actions
        val parent = cmd.actions.findItem(parentId) ?: return emptyList()
        val outer = priorForParent(cmd.actions, parentId).orEmpty()
        val container = if (context.branch == null) {
            parent.children
        } else {
            parent.branches.getOrNull(context.branch)?.children.orEmpty()
        }
        return outer + container
    }

    /** Returns the prior siblings on the path to [parentId], including outer containers. */
    private fun priorForParent(items: List<CommandItem>, parentId: Long): List<CommandItem>? {
        for ((index, item) in items.withIndex()) {
            if (item.id == parentId) return items.take(index)
            priorForParent(item.children, parentId)?.let { return items.take(index) + it }
            for (branch in item.branches) {
                priorForParent(branch.children, parentId)?.let { return items.take(index) + it }
            }
        }
        return null
    }

    private fun subtreeIds(item: CommandItem): List<Long> = buildList {
        add(item.id)
        item.conditions.forEach { addAll(subtreeIds(it)) }
        item.children.forEach { addAll(subtreeIds(it)) }
        item.branches.forEach { branch ->
            branch.conditions.forEach { addAll(subtreeIds(it)) }
            branch.children.forEach { addAll(subtreeIds(it)) }
        }
    }
}
