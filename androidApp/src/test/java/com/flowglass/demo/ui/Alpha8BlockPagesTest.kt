package com.flowglass.demo.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
@Config(sdk = [35], qualifiers = "w411dp-h891dp-440dpi")
class Alpha8BlockPagesTest {
    private fun action(type: String) = CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == type }
    private fun condition(type: String) = CommandCatalog.options(CommandSection.CONDITIONS).single { it.typeId == type }

    @Test
    fun ifPageHasThreeSectionsAndAddsTheElseInPlace() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, action("block.if"))
        val block = requireNotNull(state.find(cmd.id)).actions.single()
        state.addConditionItem(cmd.id, block.id, null, condition("condition.charging"))
        state.addChildItem(cmd.id, block.id, null, action("action.toast"))
        state.openEditor(cmd.id)
        state.openBlock(block.id)

        withActivity(state) { activity ->
            activity.setContent { Box(Modifier.fillMaxSize()) { CommandEditorHost(state) } }
            pump(activity)
            val found = find(activity)
            assertNotNull("如果段", found["block-page-section-if"])
            assertNotNull("就段", found["block-page-section-then"])
            assertNotNull("否则段", found["block-page-section-else"])
            assertNotNull("还没有否则时只能看到添加入口", found["block-page-add-else-${block.id}"])
            assertNotNull("就段内步骤", found["action-text-${requireNotNull(state.find(cmd.id)).actions.single().children.single().id}"])
            assertTrue("页面不得再出现否则如果", texts(activity).none { it.contains("否则如果") })

            click(activity, "添加否则")
            pump(activity)
            val withElse = requireNotNull(state.find(cmd.id)).actions.single()
            assertEquals("添加后只应有一个空的否则分支", 1, withElse.branches.size)
            assertTrue(withElse.branches.single().conditions.isEmpty())
            assertNotNull("否则段内应有自己的添加入口", find(activity)["block-page-add-else-step-${block.id}"])
            assertTrue(texts(activity).any { it == "否则" })
        }
    }

    @Test
    fun loopPageShowsModeAndBodyAndHidesConditionsInCountMode() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, action("block.loop"))
        val block = requireNotNull(state.find(cmd.id)).actions.single()
        state.addConditionItem(cmd.id, block.id, null, condition("condition.charging"))
        state.openEditor(cmd.id)
        state.openBlock(block.id)

        withActivity(state) { activity ->
            activity.setContent { Box(Modifier.fillMaxSize()) { CommandEditorHost(state) } }
            pump(activity)
            assertNotNull(find(activity)["block-page-section-mode"])
            assertNotNull(find(activity)["block-page-section-body"])
            assertNotNull(find(activity)["loop-times-field"])
            assertTrue("次数模式不显示条件列表", find(activity)["block-page-section-mode"]!!.children.none {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d == "编辑条件充电状态" } == true
            })
            find(activity)["loop-times-field"]!!.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("7"))
            pump(activity)
            assertTrue("次数应就地写回", requireNotNull(state.find(cmd.id)).actions.single().parameter.startsWith("按次数|7"))

            click(activity, "循环方式：条件循环")
            pump(activity)
            assertTrue("条件模式显示条件列表", texts(activity).any { it.contains("充电状态") })
            assertTrue("条件模式不显示次数输入", find(activity)["loop-times-field"] == null)
        }
    }

    @Test
    fun waitPageHasConditionAndTimeoutSections() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, action("block.wait_until"))
        val block = requireNotNull(state.find(cmd.id)).actions.single()
        state.addConditionItem(cmd.id, block.id, null, condition("condition.charging"))
        state.openEditor(cmd.id)
        state.openBlock(block.id)

        withActivity(state) { activity ->
            activity.setContent { Box(Modifier.fillMaxSize()) { CommandEditorHost(state) } }
            pump(activity)
            assertNotNull(find(activity)["block-page-section-wait-condition"])
            assertNotNull(find(activity)["block-page-section-timeout"])
            val field = find(activity)["wait-timeout-field"]
            assertNotNull("超时应就地编辑", field)
            field!!.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("45"))
            pump(activity)
            assertEquals("45 秒|1 秒", requireNotNull(state.find(cmd.id)).actions.single().parameter)
        }
    }

    @Test
    fun triggerAndConditionRowsUseDedicatedDeleteButtons() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.TRIGGERS,
            CommandCatalog.options(CommandSection.TRIGGERS).single { it.typeId == "trigger.time" })
        state.addItem(cmd.id, CommandSection.CONDITIONS, condition("condition.charging"))
        val trigger = requireNotNull(state.find(cmd.id)).triggers.single()
        val condition = requireNotNull(state.find(cmd.id)).conditions.single()
        state.openEditor(cmd.id)

        withActivity(state) { activity ->
            activity.setContent { Box(Modifier.fillMaxSize()) { CommandEditorHost(state) } }
            pump(activity)
            assertNotNull(find(activity)["item-${trigger.id}"])
            assertNotNull(find(activity)["item-${condition.id}"])
            assertNotNull(find(activity)["item-text-${trigger.id}"])
            assertTrue("不应再有底部上移按钮", nodes(activity.window.decorView).none {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("上移第") } == true
            })
            assertTrue("不应再有行尾更多菜单", nodes(activity.window.decorView).none {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d ->
                    d == "更多操作${trigger.name}" || d == "更多操作${condition.name}"
                } == true
            })
            assertNotNull("触发器行尾应有删除按钮", find(activity)["delete-item-${trigger.id}"])
            assertNotNull("条件行尾应有删除按钮", find(activity)["delete-item-${condition.id}"])

            clickTag(activity, "delete-item-${trigger.id}")
            pump(activity)
            assertTrue("删除按钮应删除对应触发器", requireNotNull(state.find(cmd.id)).triggers.isEmpty())
            assertNotNull("删除后应提供撤销", state.undo)
            state.undoDelete()
            pump(activity)
            assertEquals("撤销应恢复触发器", 1, requireNotNull(state.find(cmd.id)).triggers.size)
        }
    }

    @Test
    fun nestedConditionDeleteButtonRemovesConditionAndUndoRestoresIt() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, action("block.if"))
        val block = requireNotNull(state.find(cmd.id)).actions.single()
        state.addConditionItem(cmd.id, block.id, null, condition("condition.charging"))
        val condition = requireNotNull(state.find(cmd.id)).actions.single().conditions.single()
        state.openEditor(cmd.id)
        state.openBlock(block.id)

        withActivity(state) { activity ->
            activity.setContent { Box(Modifier.fillMaxSize()) { CommandEditorHost(state) } }
            pump(activity)
            assertTrue("块内条件不应再有更多菜单", nodes(activity.window.decorView).none {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d ->
                    d == "更多操作条件1"
                } == true
            })
            assertNotNull("块内条件应有删除按钮", find(activity)["delete-item-${condition.id}"])
            clickTag(activity, "delete-item-${condition.id}")
            pump(activity)
            assertTrue("删除应移除块内条件",
                requireNotNull(state.find(cmd.id)).actions.single().conditions.isEmpty())
            assertNotNull("删除后应提供撤销", state.undo)
            state.undoDelete()
            pump(activity)
            assertEquals("撤销应恢复块内条件", 1,
                requireNotNull(state.find(cmd.id)).actions.single().conditions.size)
        }
    }

    @Test
    fun actionRowsUseDeleteButtonsAndUndoRestoresTheStep() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, action("action.toast"))
        val step = requireNotNull(state.find(cmd.id)).actions.single()
        state.openEditor(cmd.id)

        withActivity(state) { activity ->
            activity.setContent { Box(Modifier.fillMaxSize()) { CommandEditorHost(state) } }
            pump(activity)
            assertTrue("动作行不应再有更多菜单", nodes(activity.window.decorView).none {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d ->
                    d == "更多操作${step.name}"
                } == true
            })
            assertNotNull("动作行应有删除按钮", find(activity)["delete-item-${step.id}"])
            clickTag(activity, "delete-item-${step.id}")
            pump(activity)
            assertTrue("删除按钮应删除对应动作", requireNotNull(state.find(cmd.id)).actions.isEmpty())
            assertNotNull("删除后应提供撤销", state.undo)
            state.undoDelete()
            pump(activity)
            assertEquals("撤销应恢复动作", 1, requireNotNull(state.find(cmd.id)).actions.size)
        }
    }

    @Test
    fun automaticSummaryIsNotCreatedButManualDescriptionIsShown() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.openEditor(cmd.id)
        val generated = requireNotNull(state.find(cmd.id)).desc
        assertTrue("新建指令不应生成自动摘要", generated.isEmpty())
        withActivity(state) { activity ->
            activity.setContent { Box(Modifier.fillMaxSize()) { CommandEditorHost(state) } }
            pump(activity)
            state.setDescription(cmd.id, "我手写的说明")
            pump(activity)
            assertTrue(texts(activity).any { it == "我手写的说明" })
            assertTrue(requireNotNull(state.find(cmd.id)).descriptionEdited)
        }
    }

    @Test
    fun validationBannerExpandsAndOpensTheOwningBlockPage() {
        val state = AppState()
        val cmd = state.create(auto = true)
        state.addItem(cmd.id, CommandSection.ACTIONS, action("block.if"))
        val block = requireNotNull(state.find(cmd.id)).actions.single()
        state.openEditor(cmd.id)
        withActivity(state) { activity ->
            activity.setContent { Box(Modifier.fillMaxSize()) { CommandEditorHost(state) } }
            pump(activity)
            assertNotNull(find(activity)["validation-banner"])
            assertTrue(texts(activity).any { it == "2 个问题" })
            clickTag(activity, "validation-banner-toggle")
            pump(activity)
            val issue = find(activity)["validation-issue-${block.id}"]
            assertNotNull("问题行应可点击", issue)
            issue!!.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
            pump(activity)
            assertEquals(listOf(block.id), state.blockPath)
            assertNotNull(find(activity)["block-page"])
        }
    }

    private fun find(activity: ComponentActivity): Map<String?, SemanticsNode> =
        nodes(activity.window.decorView).associateBy { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun texts(activity: ComponentActivity): List<String> =
        nodes(activity.window.decorView).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }

    private fun click(activity: ComponentActivity, description: String) {
        val node = nodes(activity.window.decorView).firstOrNull {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d == description } == true
        }
        assertNotNull("找不到 $description", node)
        node!!.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
    }

    private fun clickTag(activity: ComponentActivity, tag: String) {
        val node = find(activity)[tag]
        assertNotNull("找不到 $tag", node)
        node!!.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
    }

    private fun withActivity(state: AppState, block: (ComponentActivity) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            block(controller.get())
        } finally {
            controller.get().setContent { }
            state.closeEditor()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun pump(activity: ComponentActivity, cycles: Int = 12) {
        repeat(cycles) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val decor = activity.window.decorView
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, 1080, 2400)
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
}
