package com.flowglass.demo.ui

import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import java.io.FileOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [SafetyBackupUiTest.BlockingAtomicFile::class])
class SafetyBackupUiTest {
    @Implements(AtomicFile::class)
    class BlockingAtomicFile {
        @RealObject
        private lateinit var realObject: AtomicFile

        companion object {
            var entered: CountDownLatch? = null
            var release: CountDownLatch? = null
            var onMainThread: Boolean? = null
        }

        @Implementation
        fun startWrite(): FileOutputStream {
            onMainThread = Looper.myLooper() == Looper.getMainLooper()
            entered?.countDown()
            release?.await(2, TimeUnit.SECONDS)
            return Shadow.directlyOn(realObject, AtomicFile::class.java, "startWrite")
        }
    }

    @Test
    fun confirmRestoreKeepsMainThreadResponsiveWhileSafetyBackupIoIsBlocked() {
        val context = RuntimeEnvironment.getApplication()
        val state = AppState().also {
            it.autoCmds.add(command(901L, "恢复前规则", auto = true))
            it.settingsPage = SettingsPage.Main
        }
        val target = AppState().also { it.oneTaps.add(command(902L, "恢复后规则", auto = false)) }
        val targetImport = parseImport(exportBackupJson(target, ThemeMode.LIGHT), state)
        val safety = restoreBackup(context, state, targetImport)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()

        try {
            activity.setContent { SettingsHost(state, state.settingsPage, ThemeMode.SYSTEM) }
            pump(activity)
            clickText(activity.window.decorView, activity, "备份与恢复")
            clickText(activity.window.decorView, activity, "选择恢复前自动副本")
            awaitDialogText(activity, "恢复前自动副本")
            val picker = requireNotNull(ShadowDialog.getLatestDialog())
            val pickerDecor = requireNotNull(picker.window).decorView
            clickText(pickerDecor, activity, safety.name)
            awaitDialogText(activity, "恢复备份")
            val preview = requireNotNull(ShadowDialog.getLatestDialog())
            val previewDecor = requireNotNull(preview.window).decorView
            val before = AppStateJson.encode(state.snapshot())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            BlockingAtomicFile.entered = entered
            BlockingAtomicFile.release = release
            BlockingAtomicFile.onMainThread = null
            Thread {
                if (entered.await(2, TimeUnit.SECONDS)) {
                    Thread.sleep(700)
                    release.countDown()
                }
            }.start()

            val started = System.nanoTime()
            clickText(previewDecor, activity, "确认恢复")
            val dispatchElapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue("确认恢复不能等待被阻塞的磁盘写入，耗时=${dispatchElapsedMs}ms", dispatchElapsedMs < 500)
            assertTrue("确认恢复必须确实进入受控 AtomicFile 写入", entered.await(2, TimeUnit.SECONDS))
            assertEquals("安全副本成功前不得发布恢复后的 state", before, AppStateJson.encode(state.snapshot()))
            assertEquals("安全副本写入与回读不得在 Compose 主线程执行", false, BlockingAtomicFile.onMainThread)

            release.countDown()
            awaitStateTitle(activity, state, "恢复前规则")
            assertTrue(state.oneTaps.isEmpty())
        } finally {
            BlockingAtomicFile.entered = null
            BlockingAtomicFile.release = null
            BlockingAtomicFile.onMainThread = null
            activity.setContent { }
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun cancelDuringBlockedSafetyBackupDoesNotPublishRestore() {
        val context = RuntimeEnvironment.getApplication()
        val state = AppState().also {
            it.autoCmds.add(command(911L, "取消前规则", auto = true))
            it.settingsPage = SettingsPage.Main
        }
        val target = AppState().also { it.oneTaps.add(command(912L, "取消后规则", auto = false)) }
        val targetImport = parseImport(exportBackupJson(target, ThemeMode.LIGHT), state)
        val safety = restoreBackup(context, state, targetImport)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()

        try {
            activity.setContent { SettingsHost(state, state.settingsPage, ThemeMode.SYSTEM) }
            pump(activity)
            clickText(activity.window.decorView, activity, "备份与恢复")
            clickText(activity.window.decorView, activity, "选择恢复前自动副本")
            awaitDialogText(activity, "恢复前自动副本")
            val picker = requireNotNull(ShadowDialog.getLatestDialog())
            clickText(requireNotNull(picker.window).decorView, activity, safety.name)
            awaitDialogText(activity, "恢复备份")
            val preview = requireNotNull(ShadowDialog.getLatestDialog())
            val previewDecor = requireNotNull(preview.window).decorView
            val before = AppStateJson.encode(state.snapshot())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            BlockingAtomicFile.entered = entered
            BlockingAtomicFile.release = release
            BlockingAtomicFile.onMainThread = null

            clickText(previewDecor, activity, "确认恢复")
            assertTrue("必须确实进入受控 AtomicFile 写入", entered.await(2, TimeUnit.SECONDS))
            clickText(previewDecor, activity, "取消")
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline) {
                pump(activity)
                Thread.sleep(10)
            }

            assertEquals("取消恢复后不得在后台发布恢复结果", before, AppStateJson.encode(state.snapshot()))
            assertEquals("取消恢复后应保留确认前的数据", "取消后规则", state.oneTaps.single().title)
            assertTrue(state.autoCmds.isEmpty())
        } finally {
            BlockingAtomicFile.entered = null
            BlockingAtomicFile.release = null
            BlockingAtomicFile.onMainThread = null
            activity.setContent { }
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun settingsPickerSelectsPrivateSafetyCopyAndOpensExistingRestorePreview() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val state = AppState().also {
            it.autoCmds.add(command(701L, "恢复前规则", auto = true))
            it.settingsPage = SettingsPage.Main
        }
        val target = AppState().also { it.oneTaps.add(command(801L, "恢复后规则", auto = false)) }
        val targetImport = parseImport(exportBackupJson(target, ThemeMode.LIGHT), state)
        val safety = restoreBackup(context, state, targetImport)
        val liveStateBeforeSelection = AppStateJson.encode(state.snapshot())
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()

        try {
            activity.setContent { SettingsHost(state, state.settingsPage, ThemeMode.SYSTEM) }
            pump(activity)
            assertTrue("设置首页必须包含备份入口", texts(activity.window.decorView).contains("备份与恢复"))
            clickText(activity.window.decorView, activity, "备份与恢复")
            assertTrue(
                "备份设置页必须实际提供安全副本入口",
                texts(activity.window.decorView).contains("选择恢复前自动副本"),
            )
            clickText(activity.window.decorView, activity, "选择恢复前自动副本")
            awaitDialogText(activity, "恢复前自动副本")

            val picker = requireNotNull(ShadowDialog.getLatestDialog()) { "安全副本选择列表未打开" }
            val pickerDecor = requireNotNull(picker.window).decorView
            assertTrue(texts(pickerDecor).contains("恢复前自动副本"))
            assertTrue(texts(pickerDecor).contains(safety.name))
            clickText(pickerDecor, activity, safety.name)
            awaitDialogText(activity, "恢复备份")

            val activityTextsAfterSelect = texts(activity.window.decorView)
            val preview = requireNotNull(ShadowDialog.getLatestDialog()) { "没有进入恢复预览；页面文本：$activityTextsAfterSelect" }
            val previewDecor = requireNotNull(preview.window).decorView
            assertTrue(
                "预览标题未出现，实际对话框文本：${texts(previewDecor)}；页面文本：$activityTextsAfterSelect；dialogShowing=${preview.isShowing}",
                texts(previewDecor).contains("恢复备份"),
            )
            assertTrue(texts(previewDecor).any { it.contains("恢复前规则") })
            assertTrue(texts(previewDecor).contains("确认恢复"))
            assertEquals("选中副本时必须保持预览前的当前数据", liveStateBeforeSelection,
                AppStateJson.encode(state.snapshot()))
            clickText(previewDecor, activity, "确认恢复")
            awaitStateTitle(activity, state, "恢复前规则")
            assertEquals("恢复确认后应应用所选安全副本", "恢复前规则", state.autoCmds.single().title)
            assertTrue(state.oneTaps.isEmpty())
        } finally {
            activity.setContent { }
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun command(id: Long, title: String, auto: Boolean) = Cmd(
        id = id,
        auto = auto,
        ic = if (auto) Ic.Bolt else Ic.Tap,
        title = title,
        desc = "测试规则",
        actions = emptyList(),
        enabled = false,
    )

    private fun clickText(root: View, activity: ComponentActivity, text: String) {
        val target = viewRoots(root).firstNotNullOfOrNull { viewRoot ->
            val node = descendants(viewRoot.semanticsOwner.unmergedRootSemanticsNode).firstOrNull { candidate ->
                candidate.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            }
            node?.let { viewRoot to it }
        } ?: error("找不到可点文字：$text")
        val targetView = target.first as? View ?: error("Compose view root is not an Android View")
        val center = target.second.boundsInRoot.center
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, eventTime: Long) = MotionEvent.obtain(
            downTime, eventTime, action, center.x, center.y, 0,
        )
        val down = event(MotionEvent.ACTION_DOWN, downTime)
        targetView.dispatchTouchEvent(down)
        down.recycle()
        val up = event(MotionEvent.ACTION_UP, downTime + 40)
        targetView.dispatchTouchEvent(up)
        up.recycle()
        pump(activity)
    }

    private fun awaitStateTitle(activity: ComponentActivity, state: AppState, expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            pump(activity)
            if (state.autoCmds.singleOrNull()?.title == expected) return
            Thread.sleep(10)
        }
        error("等待恢复状态失败：${state.autoCmds.map { it.title }}")
    }

    private fun awaitDialogText(activity: ComponentActivity, expected: String) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val decor = ShadowDialog.getLatestDialog()?.window?.decorView
            if (decor != null) {
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                decor.layout(0, 0, 1080, 1920)
                viewRoots(decor).forEach { it.measureAndLayoutForTest() }
                if (texts(decor).contains(expected)) return
            }
            Thread.sleep(10)
        }
        error("等待 $expected 失败；页面文本=${texts(activity.window.decorView)}；对话框文本=${ShadowDialog.getLatestDialog()?.window?.decorView?.let(::texts)}")
    }

    private fun pump(activity: ComponentActivity) {
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val roots = listOfNotNull(activity.window.decorView, ShadowDialog.getLatestDialog()?.window?.decorView)
            roots.forEach { decor ->
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                )
                decor.layout(0, 0, 1080, 1920)
                viewRoots(decor).forEach { it.measureAndLayoutForTest() }
            }
        }
    }

    private fun texts(view: View): List<String> = nodes(view).flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private fun nodes(view: View): List<SemanticsNode> =
        viewRoots(view).flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private fun viewRoots(view: View): List<ViewRootForTest> =
        (if (view is ViewRootForTest) listOf(view) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { viewRoots(view.getChildAt(it)) } else emptyList())
}
