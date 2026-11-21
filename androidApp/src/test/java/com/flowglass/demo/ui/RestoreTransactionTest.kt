package com.flowglass.demo.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreTransactionTest {
    private fun document(title: String): PersistedAppState = AppState().also {
        it.autoCmds.add(Cmd(id = 7L, auto = true, ic = Ic.Bolt, title = title, desc = "", actions = emptyList(), enabled = false))
    }.snapshot()

    @Test
    fun savesRequestedWhilePublishingCannotOverwriteTheRestoredDocument() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "commands.json")
        file.writeText("{ broken", Charsets.UTF_8)
        val runtime = AppRuntime.of(context)
        runtime.resetForTest()
        val candidate = document("恢复后规则")
        val stale = document("旧规则")
        var publishingWindowBlocked = false

        // The app process may have loaded an empty store before this test wrote the corrupt file;
        // re-read it so the runtime really is in the blocked-after-failed-read state.
        runtime.load()
        val preserved = runBlocking {
            runtime.commitRestore(candidate) {
                // The document is durable here but not live yet. A lifecycle flush landing now must
                // not queue the old snapshot behind the repair task.
                publishingWindowBlocked = !runtime.canSaveForTest()
                runtime.requestSave { stale }
            }
        }

        runBlocking { runtime.drainForTest() }

        assertEquals(
            "窗口期内排队的旧快照不得覆盖恢复结果",
            AppStateJson.encode(candidate),
            file.readText(Charsets.UTF_8),
        )
        assertTrue("发布窗口内普通保存必须仍被阻断", publishingWindowBlocked)
        assertNotNull("损坏原档必须留下修复前副本", preserved)
        assertEquals("{ broken", preserved!!.readText(Charsets.UTF_8))
    }

    @Test
    fun savesRequestedWhilePublishingCannotOverwriteTheRestoredDocumentOnAHealthyStore() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "commands.json")
        val stale = document("旧规则")
        file.writeText(AppStateJson.encode(stale), Charsets.UTF_8)
        val runtime = AppRuntime.of(context)
        runtime.resetForTest()
        val candidate = document("恢复后规则")
        runtime.load()
        assertTrue("健康存档加载后应允许保存", runtime.canSaveForTest())
        var publishingWindowBlocked = false

        runBlocking {
            runtime.commitRestore(candidate) {
                publishingWindowBlocked = !runtime.canSaveForTest()
                runtime.requestSave { stale }
            }
        }
        runBlocking { runtime.drainForTest() }

        assertEquals(
            "健康存档下窗口期排队的旧快照不得覆盖恢复结果",
            AppStateJson.encode(candidate),
            file.readText(Charsets.UTF_8),
        )
        assertTrue("发布窗口内普通保存必须仍被阻断", publishingWindowBlocked)
        assertTrue("事务完成后必须恢复保存", runtime.canSaveForTest())
    }
}
