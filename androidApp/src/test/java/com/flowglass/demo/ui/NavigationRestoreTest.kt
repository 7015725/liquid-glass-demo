package com.flowglass.demo.ui

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationRestoreTest {
    @Test fun snapshotSaverRoundTripsOnlyIdsAndTab() {
        val original = NavSnapshot(tab = 2, editing = 41, actionCmd = 41, action = 42, settingsPage = 5)
        val saved = requireNotNull(with(NavSnapshotSaver) {
            object : SaverScope {
                override fun canBeSaved(value: Any) = true
            }.save(original)
        })
        assertEquals(original, NavSnapshotSaver.restore(saved))
    }

    @Test fun restoreNavigationReopensEditorAndNestedActionPage() {
        val state = AppState()
        val command = state.create(auto = true)
        val ifOption = CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "block.if" }
        state.addItem(command.id, CommandSection.ACTIONS, ifOption)
        val parent = requireNotNull(state.find(command.id)).actions.single()
        state.addChildItem(command.id, parent.id, null,
            CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.toast" })
        val child = requireNotNull(state.find(command.id)).actions.single().children.single()
        state.setItemParameter(command.id, CommandSection.ACTIONS, child.id, "preserved")
        val saved = NavSnapshot(
            tab = 1, editing = command.id, actionCmd = command.id,
            action = child.id, settingsPage = SettingsPage.Appearance,
        )

        state.closeEditor()
        state.settingsPage = SettingsPage.Main
        val restored = restoreNavigation(state, saved)

        assertEquals("编辑页 id 应恢复", command.id, state.editing)
        assertEquals("参数页指令 id 应恢复", command.id, state.actionEditingCmd)
        assertEquals("参数页应定位到嵌套子步骤", child.id, state.actionEditing)
        assertEquals("已保存的动作参数不能丢失", "preserved", state.itemById(command.id, child.id)?.parameter)
        assertEquals("Dock 标签页应恢复", 1, restored.tab)
        assertEquals("设置子页应恢复", SettingsPage.Appearance, state.settingsPage)
    }

    @Test fun configurationRestoreDoesNotReviveAnAlreadyClosedActionPage() {
        val state = AppState()
        val command = state.create(auto = true)
        state.addItem(command.id, CommandSection.ACTIONS,
            CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.delay" })
        val action = requireNotNull(state.find(command.id)).actions.single()
        state.openEditor(command.id)
        val saved = NavSnapshot(tab = 1, editing = command.id, actionCmd = command.id, action = action.id)

        // Same process, Activity recreation: the user already closed the action page.
        state.actionEditing = null
        state.actionEditingCmd = null
        restoreNavigation(state, saved)

        assertEquals("编辑页仍在", command.id, state.editing)
        assertNull("已关闭的参数页不能被旧快照复活", state.actionEditing)
    }

    @Test fun restoreNavigationIgnoresIdsThatNoLongerExist() {
        val state = AppState()
        state.create(auto = true)
        val restored = restoreNavigation(state, NavSnapshot(
            tab = 9, editing = 999_999, actionCmd = 999_999, action = 888_888, settingsPage = 99,
        ))

        assertNull("不存在的指令不能被恢复", state.editing)
        assertNull("不存在的动作不能被恢复", state.actionEditing)
        assertEquals("非法标签页应回落到概览", 0, restored.tab)
        assertEquals("非法设置页应忽略", SettingsPage.Main, state.settingsPage)
    }

    @Test fun rememberSaveableRestoresNavigationAfterActivityRecreation() {
        val first = Robolectric.buildActivity(NavProbeActivity::class.java).setup().visible()
        pump(first.get())
        click(first.get(), "change-navigation")
        assertTrue("探针应切换到自动指令标签页", pageTexts(first.get()).contains("tab=1"))

        val saved = Bundle()
        first.saveInstanceState(saved)
        first.pause().stop().destroy()

        val second = Robolectric.buildActivity(NavProbeActivity::class.java)
        second.create(saved).start().resume().visible()
        pump(second.get())

        val texts = pageTexts(second.get())
        assertTrue("标签页应在重建后保留；实际 $texts", texts.contains("tab=1"))
        assertTrue("编辑页 id 应在重建后保留；实际 $texts", texts.contains("edit=41"))
        assertTrue("参数页 id 应在重建后保留；实际 $texts", texts.contains("action=42"))
        assertTrue("块路径应在重建后保留；实际 $texts", texts.contains("block=41,42"))
        second.pause().stop().destroy()
    }

    private fun click(activity: ComponentActivity, label: String) {
        val node = nodes(activity.window.decorView).single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value -> value == label } == true
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        pump(activity, cycles = 8)
    }

    private fun pageTexts(activity: ComponentActivity): List<String> =
        nodes(activity.window.decorView).flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

    private fun pump(activity: ComponentActivity, cycles: Int = 8) {
        repeat(cycles) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val decor = activity.window.decorView
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, 1080, 1920)
            roots(decor).forEach { it.measureAndLayoutForTest() }
        }
    }

    private fun roots(view: View): List<ViewRootForTest> =
        (if (view is ViewRootForTest) listOf(view) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList())

    private fun nodes(view: View): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::descendants)
        return roots(view).flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    }

    @Test fun restoringAnOpenActionPageKeepsTheBlockPath() {
        val state = AppState()
        val command = state.create(auto = true)
        val ifOption = CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "block.if" }
        state.addItem(command.id, CommandSection.ACTIONS, ifOption)
        val parent = requireNotNull(state.find(command.id)).actions.single()
        state.addChildItem(command.id, parent.id, null,
            CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.toast" })
        val child = requireNotNull(state.find(command.id)).actions.single().children.single()
        state.openEditor(command.id)
        state.openBlock(parent.id)
        state.actionEditingCmd = command.id
        state.actionEditing = child.id

        restoreNavigation(state, NavSnapshot(tab = 1, editing = command.id, actionCmd = command.id,
            action = child.id, blockPath = listOf(parent.id)))

        assertEquals(listOf(parent.id), state.blockPath)
    }

    @Test fun aNewDraftLostInProcessDeathIsReported() {
        val state = AppState()
        val command = state.create(auto = true)
        state.startComponentDraft(
            command.id,
            CommandSection.ACTIONS,
            CommandCatalog.options(CommandSection.ACTIONS).first { it.typeId == "action.toast" },
        )
        val restored = AppState(state.snapshot())

        restoreNavigation(restored, NavSnapshot(tab = 1, editing = command.id, draft = true))

        assertEquals("未保存的组件草稿已丢失，已恢复上次保存的版本", restored.editorNotice)
    }

    @Test fun anEditedSavedComponentDraftLostInProcessDeathIsReported() {
        val state = AppState()
        val command = state.create(auto = true)
        state.addItem(command.id, CommandSection.ACTIONS,
            CommandCatalog.options(CommandSection.ACTIONS).first { it.typeId == "action.toast" })
        val item = state.find(command.id)!!.actions.single()
        state.openComponentEditor(command.id, CommandSection.ACTIONS, item)
        state.updateComponentDraft(item.copy(parameter = """{"text":"未保存"}"""))
        val restored = AppState(state.snapshot())

        restoreNavigation(
            restored,
            NavSnapshot(tab = 1, editing = command.id, actionCmd = command.id,
                action = item.id, draft = true),
        )

        assertEquals("未保存的组件草稿已丢失，已恢复上次保存的版本", restored.editorNotice)
        assertNull(restored.actionEditing)
        assertNull(restored.actionEditingCmd)
    }

    @Test fun theDraftFlagSurvivesTheSaveableRoundTrip() {
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val saved = with(NavSnapshotSaver) {
            with(scope) { save(NavSnapshot(tab = 1, editing = 2, draft = true)) }
        }!!
        val restored = NavSnapshotSaver.restore(saved)!!
        assertEquals(true, restored.draft)
    }

    @Test fun aLostDraftOpenedFromABlockPageReturnsToTheCommandRoot() {
        val state = AppState()
        val command = state.create(auto = true)
        state.addItem(command.id, CommandSection.ACTIONS,
            CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "block.if" })
        val block = state.find(command.id)!!.actions.single()
        val restored = AppState(state.snapshot())

        restoreNavigation(
            restored,
            NavSnapshot(tab = 1, editing = command.id, actionCmd = command.id,
                blockPath = listOf(block.id), draft = true),
        )

        assertEquals("未保存的组件草稿已丢失，已恢复上次保存的版本", restored.editorNotice)
        assertTrue(restored.blockPath.isEmpty())
    }

    @Test fun aCleanRestoreDoesNotReportALostDraft() {
        val state = AppState()
        val command = state.create(auto = true)
        val restored = AppState(state.snapshot())

        restoreNavigation(restored, NavSnapshot(tab = 1, editing = command.id, draft = false))

        assertNull(restored.editorNotice)
    }
}

private class NavProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NavProbeContent() }
    }
}

@Composable
private fun NavProbeContent() {
    var nav by rememberSaveable(stateSaver = NavSnapshotSaver) { mutableStateOf(NavSnapshot()) }
    Column {
        BasicText("tab=${nav.tab}", Modifier.testTag("nav-tab"))
        BasicText("edit=${nav.editing}", Modifier.testTag("nav-edit"))
        BasicText("action=${nav.action}", Modifier.testTag("nav-action"))
        BasicText("block=${nav.blockPath.joinToString(",")}", Modifier.testTag("nav-block"))
        BasicText(
            "change",
            Modifier
                .testTag("nav-change")
                .semantics { contentDescription = "change-navigation" }
                .clickable {
                    nav = NavSnapshot(tab = 1, editing = 41, actionCmd = 41, action = 42, blockPath = listOf(41, 42))
                },
        )
    }
}
