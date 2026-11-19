package com.flowglass.demo.ui

import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.OutputBinding
import com.flowglass.engine.OutputCatalog
import com.flowglass.engine.VariableScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VariableAvailabilityTest {
    private fun item(
        id: Long,
        name: String,
        typeId: String,
        children: List<CommandItem> = emptyList(),
        branches: List<CommandBranch> = emptyList(),
    ): CommandItem {
        val meta = requireNotNull(BuiltInComponents.registry().find(typeId))
        val outputs = OutputCatalog.allOutputs(meta)
            .take(1)
            .map { OutputBinding(it.key, "out_$id") }
        return CommandItem(
            id, name, "", when (typeId) {
                "trigger.notification" -> Ic.Bell
                "condition.screen_state" -> Ic.Phone
                "block.if" -> Ic.Shield
                else -> Ic.Terminal
            }, typeId,
            children = children,
            branches = branches,
            outputs = outputs,
        )
    }

    private val command = Cmd(
        id = 1,
        auto = true,
        ic = Ic.Bolt,
        title = "测试指令",
        desc = "",
        triggers = listOf(item(10, "收到通知", "trigger.notification")),
        conditions = listOf(item(11, "屏幕状态", "condition.screen_state")),
        actions = listOf(
            item(20, "Shell", "action.root_shell"),
            item(
                30, "如果", "block.if",
                children = listOf(
                    item(31, "通知 A", "action.toast"),
                    item(32, "Shell B", "action.root_shell"),
                ),
                branches = listOf(
                    CommandBranch(children = listOf(item(33, "通知 C", "action.toast"))),
                ),
            ),
            item(40, "通知 D", "action.toast"),
        ),
    )

    private fun state(): AppState = AppState().also { it.autoCmds.add(command) }

    private fun owners(context: VariablePickerContext): Set<Long> =
        VariableCatalog.forEditor(state(), 1, context)
            .filter { it.scope == VariableScope.OUTPUT && it.ownerItemId != null }
            .mapNotNull { it.ownerItemId }
            .toSet()

    @Test
    fun editorKeepsAllOutputsWhenNoPositionIsGiven() {
        val available = VariableCatalog.forEditor(state(), 1)
            .filter { it.scope == VariableScope.OUTPUT && it.ownerItemId != null }
            .mapNotNull { it.ownerItemId }
            .toSet()
        assertTrue(available.contains(40L))
    }

    @Test
    fun triggerParametersDoNotOfferTheTriggerOwnOutputs() {
        assertTrue(owners(VariablePickerContext(CommandSection.TRIGGERS, itemId = 10)).isEmpty())
    }

    @Test
    fun rootConditionOnlySeesTriggerOutputs() {
        assertEquals(setOf(10L), owners(VariablePickerContext(CommandSection.CONDITIONS, itemId = 11)))
    }

    @Test
    fun conditionInsideBlockOnlySeesActionsBeforeTheBlock() {
        assertEquals(
            setOf(10L, 20L),
            owners(VariablePickerContext(CommandSection.CONDITIONS, itemId = 12, parentId = 30)),
        )
    }

    @Test
    fun firstActionOnlySeesTriggerOutputs() {
        assertEquals(setOf(10L), owners(VariablePickerContext(CommandSection.ACTIONS, itemId = 20)))
    }

    @Test
    fun laterRootActionSeesEarlierSubtreesButNotItselfOrLaterActions() {
        val available = owners(VariablePickerContext(CommandSection.ACTIONS, itemId = 40))
        assertTrue(available.containsAll(setOf(10L, 20L, 30L, 31L, 32L, 33L)))
        assertTrue(40L !in available)
    }

    @Test
    fun stepInsideThenOnlySeesEarlierThenSteps() {
        assertEquals(
            setOf(10L, 20L, 31L),
            owners(VariablePickerContext(CommandSection.ACTIONS, itemId = 32)),
        )
    }

    @Test
    fun stepInsideElseDoesNotSeeThenBranchOutputs() {
        assertEquals(
            setOf(10L, 20L),
            owners(VariablePickerContext(CommandSection.ACTIONS, itemId = 33)),
        )
    }

    @Test
    fun newStepAtRootSeesEveryExistingRootAction() {
        val available = owners(VariablePickerContext(CommandSection.ACTIONS))
        assertTrue(available.containsAll(setOf(10L, 20L, 30L, 31L, 32L, 33L, 40L)))
    }

    @Test
    fun newStepInsideThenSeesOuterActionsAndExistingThenSteps() {
        assertEquals(
            setOf(10L, 20L, 31L, 32L),
            owners(VariablePickerContext(CommandSection.ACTIONS, itemId = 999, parentId = 30)),
        )
    }

    @Test
    fun newStepInsideElseDoesNotSeeThenBranchOutputs() {
        assertEquals(
            setOf(10L, 20L, 33L),
            owners(VariablePickerContext(CommandSection.ACTIONS, itemId = 999, parentId = 30, branch = 0)),
        )
    }
}
