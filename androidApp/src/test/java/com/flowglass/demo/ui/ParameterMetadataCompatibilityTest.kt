package com.flowglass.demo.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.ParamType
import com.flowglass.engine.RuleJson
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParameterMetadataCompatibilityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun catalogBatteryRangeUsesTextWhileScalarPercentagesKeepTheirSliders() {
        val option = CommandCatalog.options(CommandSection.CONDITIONS).single { it.typeId == "condition.battery_range" }
        val metadata = requireNotNull(BuiltInComponents.registry().find(requireNotNull(option.typeId)))
        assertEquals("20%–80%", option.defaultParameter)
        assertEquals("range", metadata.params.single().key)
        assertEquals(RuleJson.format.parseToJsonElement("{\"min\":20,\"max\":80}"), metadata.params.single().defaultValue)
        assertEquals(ParamType.INT_RANGE, metadata.params.single().type)
        listOf("trigger.battery_low", "action.set_brightness", "action.set_volume").forEach { type ->
            assertEquals(type, ParamType.PERCENTAGE, BuiltInComponents.registry().find(type)?.params?.first()?.type)
        }
    }

    @Test fun productionEditorEditsAndSavesTheEntireCatalogBatteryRange() {
        val file = File(folder.root, "commands.json")
        val store = CommandStore(file)
        val state = store.load().appState()
        val command = state.create(auto = true)
        val option = CommandCatalog.options(CommandSection.CONDITIONS).single { it.typeId == "condition.battery_range" }
        state.addItem(command.id, CommandSection.CONDITIONS, option)
        state.addItem(command.id, CommandSection.TRIGGERS,
            CommandCatalog.options(CommandSection.TRIGGERS).single { it.typeId == "trigger.time" })
        state.addItem(command.id, CommandSection.ACTIONS,
            CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.delay" })
        state.openEditor(command.id)
        val itemId = requireNotNull(state.find(command.id)).conditions.single().id
        assertEquals("20%–80%", requireNotNull(state.find(command.id)).conditions.single().parameter)

        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        var done = false
        try {
            activity.setContent {
                val item = requireNotNull(state.find(command.id)).conditions.single()
                Column {
                    ActionParameterEditor(CommandSection.CONDITIONS, item,
                        onParameter = { state.setItemParameter(command.id, CommandSection.CONDITIONS, itemId, it) },
                        onDone = { done = true }, commandTitle = command.title)
                }
            }
            pump(activity)
            val initialNodes = nodes(activity.window.decorView)
            assertTrue("The actual production editor must be composed", initialNodes.isNotEmpty())
            assertFalse("A battery range must never select the single-value percentage slider",
                initialNodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "parameter-percent-slider" })
            assertEquals("20%–80%", textField(activity).config[SemanticsProperties.EditableText].text)

            // Invoke the real BasicTextField semantics action, not a copied parameter adapter.
            val setText = requireNotNull(textField(activity).config[SemanticsActions.SetText].action)
            assertTrue(setText(AnnotatedString("25%–75%")))
            pump(activity)
            assertEquals("25%–75%", textField(activity).config[SemanticsProperties.EditableText].text)
            val finish = nodes(activity.window.decorView).single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("完成") == true
            }
            assertTrue(requireNotNull(finish.config[SemanticsActions.OnClick].action).invoke())
            assertTrue(done)
            state.closeEditor()
            store.save(state.snapshot())

            val loaded = CommandStore(file).load()
            assertNull(loaded.error)
            val saved = requireNotNull(loaded.saved)
            assertEquals("25%–75%", saved.autoCmds.single { it.id == command.id }.conditions.single().parameter)
            val disk = RuleJson.decode(file.readText())
            val rule = disk.rules.single { disk.ui.presentation[it.id]?.legacyId == command.id }
            assertEquals("condition.battery_range", rule.conditions.single().type)
            assertEquals(RuleJson.format.parseToJsonElement("{\"min\":25,\"max\":75}"), rule.conditions.single().params["range"])

            val reopened = loaded.appState()
            activity.setContent {
                val item = requireNotNull(reopened.find(command.id)).conditions.single()
                Column {
                    ActionParameterEditor(CommandSection.CONDITIONS, item,
                        onParameter = { reopened.setItemParameter(command.id, CommandSection.CONDITIONS, item.id, it) },
                        onDone = {})
                }
            }
            pump(activity)
            assertEquals("25%–75%", textField(activity).config[SemanticsProperties.EditableText].text)
            assertFalse(nodes(activity.window.decorView).any {
                it.config.getOrNull(SemanticsProperties.TestTag) == "parameter-percent-slider"
            })
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun textField(activity: ComponentActivity): SemanticsNode = nodes(activity.window.decorView).single {
        it.config.getOrNull(SemanticsProperties.TestTag) == "item-parameter"
    }

    private fun pump(activity: ComponentActivity) {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1080, 1920)
            roots(decor).forEach { it.measureAndLayoutForTest() }
        }
    }

    private fun roots(view: View): List<ViewRootForTest> =
        (if (view is ViewRootForTest) listOf(view) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { roots(view.getChildAt(it)) } else emptyList())

    private fun nodes(view: View): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return roots(view).flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
}
