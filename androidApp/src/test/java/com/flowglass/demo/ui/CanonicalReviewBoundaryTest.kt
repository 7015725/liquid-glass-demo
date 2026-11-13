package com.flowglass.demo.ui

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.flowglass.demo.system.exec.ExecutionEngine
import com.flowglass.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CanonicalReviewBoundaryTest {
    @get:JUnitRule val folder = TemporaryFolder()

    @Test fun invalidKnownEditRefusesSaveAndKeepsOriginalTypeExtensionsAndPolicy() {
        val component = RuleComponent("action.delay", mapOf("duration" to JsonPrimitive(1000), "extension" to JsonPrimitive("KEEP")), OnError.RETRY)
        val document = RuleDocument(rules = listOf(com.flowglass.engine.Rule("draft", type = RuleType.MANUAL, actions = listOf(component))))
        val file = File(folder.root, "commands.json").apply { writeText(RuleJson.encode(document)) }
        val original = file.readBytes()
        val store = CommandStore(file)
        val app = store.load().appState()
        val command = app.oneTaps.single()
        val item = command.actions.single()
        app.setItemParameter(command.id, CommandSection.ACTIONS, item.id, "")
        val error = runCatching { store.save(app.snapshot()) }.exceptionOrNull()
        assertTrue("invalid known edits must reject the write", error is IllegalArgumentException)
        assertArrayEquals(original, file.readBytes())
        assertEquals("", app.find(command.id)!!.actions.single().parameter)
        val reloaded = CommandStore(file).load().appState()
        assertEquals(component, reloaded.engineRules.single().actions.single())
        app.setItemParameter(command.id, CommandSection.ACTIONS, item.id, "2 秒")
        store.save(app.snapshot())
        assertEquals(component.copy(params = component.params + ("duration" to JsonPrimitive(2000))), RuleJson.decode(file.readText()).rules.single().actions.single())
    }

    @Test fun newInvalidKnownItemRefusesSaveAndCanBeCorrected() {
        val store = CommandStore(File(folder.root, "commands.json"))
        val app = store.load().appState()
        store.save(app.snapshot()) // Historical demo seeds must still be losslessly encodable.
        val command = app.create(false)
        app.addItem(command.id, CommandSection.ACTIONS, CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.delay" })
        val item = app.find(command.id)!!.actions.single()
        app.setItemParameter(command.id, CommandSection.ACTIONS, item.id, "wrong")
        assertTrue(runCatching { store.save(app.snapshot()) }.exceptionOrNull() is IllegalArgumentException)
        app.setItemParameter(command.id, CommandSection.ACTIONS, item.id, "2 秒")
        store.save(app.snapshot())
        assertEquals("action.delay", CommandStore(File(folder.root, "commands.json")).load().appState().find(command.id)!!.actions.single().typeId)
    }

    @Test fun invalidKnownActionReturnsFailureWithoutExecutingFollowingTasks() = runBlocking {
        val app = AppState()
        val command = app.create(false)
        app.addItem(command.id, CommandSection.ACTIONS, CommandCatalog.options(CommandSection.ACTIONS).single { it.typeId == "action.delay" })
        val item = app.find(command.id)!!.actions.single()
        app.setItemParameter(command.id, CommandSection.ACTIONS, item.id, "wrong")
        var calls = 0
        val result = ExecutionEngine(RuntimeEnvironment.getApplication()) { _, _ ->
            calls++
            com.flowglass.demo.system.exec.TaskOutcome(com.flowglass.demo.system.exec.StepStatus.SUCCESS, "unexpected")
        }.run(app.find(command.id)!!, "测试")
        assertFalse(result.ok)
        assertEquals(0, calls)
        assertTrue(result.detail.contains("参数"))
    }

    @Test fun unbridgeableNotificationTitleIsRejectedWithoutChangingFile() {
        val document = RuleDocument(rules = listOf(com.flowglass.engine.Rule("title", type = RuleType.MANUAL, actions = listOf(
            RuleComponent("action.post_notification", mapOf("title" to JsonPrimitive("A|B"), "content" to JsonPrimitive("C")))
        ))))
        val raw = RuleJson.format.encodeToString(document)
        val file = File(folder.root, "commands.json").apply { writeText(raw) }
        val store = CommandStore(file)
        val loaded = store.load()
        assertNotNull(loaded.error)
        assertFalse(store.canSave)
        assertEquals(raw, file.readText())
        assertTrue(runCatching { RuleJson.encode(document) }.isFailure)
    }

    @Test fun notificationBodySeparatorsSurviveEditSaveReloadAndNativeExecution() = runBlocking {
        val document = RuleDocument(rules = listOf(com.flowglass.engine.Rule("title", type = RuleType.MANUAL, name = "默认标题", actions = listOf(
            RuleComponent("action.post_notification", mapOf("title" to JsonPrimitive("A"), "content" to JsonPrimitive("B|C")))
        ))))
        val file = File(folder.root, "commands.json").apply { writeText(RuleJson.encode(document)) }
        val store = CommandStore(file)
        val app = store.load().appState()
        val command = app.oneTaps.single()
        app.setItemParameter(command.id, CommandSection.ACTIONS, command.actions.single().id, "A|new|正文\n末行")
        store.save(app.snapshot())
        val reloaded = CommandStore(file).load().appState().oneTaps.single()
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        val outcome = ExecutionEngine(context).run(reloaded, "测试")
        assertTrue(outcome.ok)
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals("A", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("new|正文\n末行", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
