package com.flowglass.demo.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** alpha7: one-level block pages, breadcrumb path and block-scoped validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BlockPageModelTest {
    private fun blockOption(type: String) =
        CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == type }

    private fun stepOption() =
        CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.toast" }

    private fun nestedState(): Triple<AppState, Long, List<Long>> {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, blockOption("block.loop"))
        val outer = requireNotNull(state.find(cmd.id)).actions.single().id
        state.addChildItem(cmd.id, outer, null, blockOption("block.if"))
        val middle = requireNotNull(state.find(cmd.id)).actions.findActionNode(outer)!!.children.single().id
        state.addChildItem(cmd.id, middle, null, blockOption("block.loop"))
        val inner = requireNotNull(state.find(cmd.id)).actions.findActionNode(middle)!!.children.single().id
        state.addChildItem(cmd.id, inner, null, stepOption())
        state.openEditor(cmd.id)
        return Triple(state, cmd.id, listOf(outer, middle, inner))
    }

    @Test
    fun entersOnlyDirectChildBlocksAndBacksThroughBreadcrumbPath() {
        val (state, cmdId, ids) = nestedState()
        val (outer, middle, inner) = ids
        assertTrue(state.canOpenBlock(outer))
        assertFalse("不能从根页直接打开第 3 层", state.canOpenBlock(inner))

        state.openBlock(outer)
        assertEquals(listOf(outer), state.blockPath)
        assertTrue(state.canOpenBlock(middle))
        assertFalse(state.canOpenBlock(inner))
        state.openBlock(middle)
        state.openBlock(inner)
        assertEquals(ids, state.blockPath)

        state.gotoBlock(0)
        assertEquals(listOf(outer), state.blockPath)
        state.backBlock()
        assertTrue(state.blockPath.isEmpty())
        state.gotoBlock(-1)
        assertTrue(state.blockPath.isEmpty())
        assertEquals(cmdId, state.editing)
    }

    @Test
    fun invalidBlockPathIsTruncatedAtTheFirstBadLevel() {
        val (state, _, ids) = nestedState()
        val (outer, middle, inner) = ids
        assertEquals("缺少父级的路径应回根页", emptyList<Long>(), state.validBlockPath(listOf(middle, inner)))
        assertEquals("中间跳级应截断", listOf(outer), state.validBlockPath(listOf(outer, inner)))
        assertEquals("完整的直接路径有效", ids, state.validBlockPath(ids))
    }

    @Test
    fun navSnapshotSaverAndRestoreKeepTheBlockPath() {
        val (state, cmdId, ids) = nestedState()
        val snapshot = NavSnapshot(tab = 1, editing = cmdId, blockPath = ids.take(2))
        val saved = requireNotNull(with(NavSnapshotSaver) {
            object : SaverScope {
                override fun canBeSaved(value: Any) = true
            }.save(snapshot)
        })
        assertEquals(snapshot, NavSnapshotSaver.restore(saved))

        state.closeEditor()
        val restored = restoreNavigation(state, snapshot)
        assertEquals(cmdId, state.editing)
        assertEquals(ids.take(2), state.blockPath)
        assertEquals(ids.take(2), restored.blockPath)
    }

    @Test
    fun liveBlockPathWinsOverAStaleSavedSnapshot() {
        val (state, cmdId, ids) = nestedState()
        state.openBlock(ids[0])
        state.openBlock(ids[1])
        // A snapshot saved just before the user opened this page still points at the root.
        val stale = NavSnapshot(tab = 1, editing = cmdId, blockPath = emptyList())
        restoreNavigation(state, stale)
        assertEquals(listOf(ids[0], ids[1]), state.blockPath)
    }

    @Test
    fun blockPreviewUsesDirectStepsAndBranchSteps() {
        val (state, cmdId, _) = nestedState()
        val cmd = requireNotNull(state.find(cmdId))
        val outer = cmd.actions.single()
        val preview = ActionSummary.blockPreview(outer)
        assertTrue("预览应列出内部步骤名，实际 $preview", preview.contains("如果") || preview.contains("还有"))

        val emptyIf = CommandItem(
            id = 900, name = "如果", parameter = "", ic = Ic.Puzzle, typeId = "block.if",
        )
        assertEquals("还没有步骤", ActionSummary.blockPreview(emptyIf))
        val withBranches = emptyIf.copy(
            branches = listOf(CommandBranch(children = listOf(
                CommandItem(901, "Toast 提示", "", Ic.Bell, "action.toast"),
                CommandItem(902, "延时等待", "", Ic.Timer, "action.delay"),
                CommandItem(903, "振动", "", Ic.Bolt, "action.vibrate"),
            ))),
        )
        assertTrue(ActionSummary.blockPreview(withBranches).endsWith("等 3 步"))
    }

    @Test
    fun blockValidationIssuesAreScopedToTheBlockSubtree() {
        val (state, cmdId, ids) = nestedState()
        val cmd = requireNotNull(state.find(cmdId))
        val outer = cmd.actions.single()
        val outerIssues = state.blockValidationIssues(cmd, outer.id)
        val innerIssues = state.blockValidationIssues(cmd, ids[1])
        assertTrue("空的内层块应被统计", outerIssues.any { it.contains("缺少条件") })
        assertTrue("父块应包含嵌套问题", outerIssues.size >= innerIssues.size)
        assertEquals("根层块之外不应计入", emptyList<String>(), state.blockValidationIssues(cmd, 999_999))
    }
}
