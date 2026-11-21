package com.flowglass.demo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommandStoreRepairTest {
    private fun candidate(title: String = "修复后规则"): PersistedAppState = AppState().also {
        it.autoCmds.add(Cmd(id = 91L, auto = true, ic = Ic.Bolt, title = title, desc = "测试规则", actions = emptyList(), enabled = false))
    }.snapshot()

    private fun storeFile(): File = File(RuntimeEnvironment.getApplication().filesDir, "commands.json")

    @Test
    fun repairWritesTheCandidateButKeepsSavingBlockedUntilCompleted() {
        val file = storeFile()
        file.writeText("{ broken", Charsets.UTF_8)
        val store = CommandStore(file)
        assertNotNull("损坏存档必须先被识别为读取失败", store.load().error)

        val repaired = candidate()
        val preserved = store.repair(repaired)

        assertNotNull("修复前必须保留原始字节副本", preserved)
        assertEquals("{ broken", preserved!!.readText(Charsets.UTF_8))
        assertFalse("候选尚未发布前，普通保存必须保持关闭", store.canSave)
        try {
            store.save(candidate("并发旧快照"))
            fail("窗口期的普通保存必须被拒绝")
        } catch (_: IllegalStateException) {
        }
        assertEquals(AppStateJson.encode(repaired), file.readText(Charsets.UTF_8))

        store.completeRepair()

        assertTrue("发布完成后必须重新允许保存", store.canSave)
        val reloaded = CommandStore(file).load()
        assertNull(reloaded.error)
        assertEquals("修复后规则", reloaded.saved!!.autoCmds.single().title)
    }

    @Test
    fun repairPreservesTheAtomicFileBackupWhenTheBaseIsMissing() {
        val file = storeFile()
        File(file.path + ".bak").writeText("{ broken-bak", Charsets.UTF_8)
        val store = CommandStore(file)
        assertNotNull(store.load().error)

        val preserved = store.repair(candidate())

        assertNotNull(preserved)
        assertEquals("{ broken-bak", preserved!!.readText(Charsets.UTF_8))
    }

    @Test
    fun failedRepairKeepsTheOriginalAndStaysBlocked() {
        val file = storeFile()
        assertTrue(file.mkdirs())
        File(file, "child").writeText("keep")
        val store = CommandStore(file)
        assertNotNull(store.load().error)

        try {
            store.repair(candidate())
            fail("原始字节无法读取时修复必须失败")
        } catch (_: Exception) {
        }

        assertTrue("失败后原路径不得被替换", file.isDirectory)
        assertEquals("keep", File(file, "child").readText())
        assertFalse("失败后普通保存必须保持关闭", store.canSave)
    }

    @Test
    fun saveWithAStaleEpochIsRejectedAfterARestoreTransaction() {
        val file = storeFile()
        val stale = candidate("旧规则")
        file.writeText(AppStateJson.encode(stale), Charsets.UTF_8)
        val store = CommandStore(file)
        assertNull(store.load().error)
        val token = store.currentEpoch()
        val live = candidate("恢复后规则")

        store.beginRepair()
        store.repair(live)
        store.completeRepair()

        assertFalse("恢复事务开始前捕获的保存必须被拒绝", store.save(stale, token))
        assertEquals(AppStateJson.encode(live), file.readText(Charsets.UTF_8))
    }
}
