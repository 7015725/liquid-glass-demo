package com.flowglass.demo.ui

import com.flowglass.demo.system.exec.itemAtPath
import org.junit.Assert.assertEquals
import org.junit.Test

class ItemPathTest {
    @Test fun nested() {
        val child = CommandItem(2, "子", "", Ic.Bolt, "action.delay")
        val branchChild = CommandItem(3, "分支子", "", Ic.Bolt, "action.delay")
        val block = CommandItem(1, "如果", "", Ic.Bolt, "block.if", children = listOf(child),
            branches = listOf(CommandBranch(children = listOf(branchChild))))
        val cmd = Cmd(10, false, Ic.Bolt, "t", "", actions = listOf(block))
        assertEquals(1L, cmd.itemAtPath(listOf(0))?.id)
        assertEquals(2L, cmd.itemAtPath(listOf(0, 0))?.id)
        assertEquals(3L, cmd.itemAtPath(listOf(0, -1, 0))?.id)
    }
}
