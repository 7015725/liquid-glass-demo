package com.flowglass.demo.ui

import com.flowglass.engine.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CanonicalDisplayTest {
    @get:JUnitRule val folder = TemporaryFolder()

    @Test fun actualCommandItemsDisplayV05NamesAndParameterLabels() {
        val expected = listOf("时间：08:00 · 每天", "亮屏", "熄屏", "解锁", "插入充电器", "拔出充电器", "电量：低于 20%", "开机完成",
            "电量低于：20%", "充电状态：开始充电", "进入地点：公司", "离开地点：家", "收到通知：验证码", "蓝牙连接：耳机",
            "WiFi 连接/断开：连接家庭网络", "应用启动：应用包名",
            "时间段：08:00–22:00", "星期：工作日", "电量范围：20%–80%", "充电状态：充电中", "屏幕状态：已亮屏", "网络类型：WiFi", "勿扰状态：已关闭",
            "开关 WiFi：开启", "开关蓝牙：开启", "开关勿扰：开启", "调节亮度：50%", "调节音量：50%", "启动应用：应用包名",
            "发送通知：任务已完成", "复制到剪贴板：待复制文本", "延时等待：1 秒", "执行 Shell(Root)：echo 流光", "HTTP 请求：GET https://example.com")
        val metas = BuiltInComponents.all
        fun components(kind: ComponentKind) = metas.filter { it.kind == kind }.map { RuleComponent(it.typeId, it.params.associate { p -> p.key to p.defaultValue }) }
        val state = RuleDocument(rules = listOf(com.flowglass.engine.Rule("golden", name = "显示",
            triggers = components(ComponentKind.TRIGGER), conditions = components(ComponentKind.CONDITION), actions = components(ComponentKind.ACTION))))
            .toPersistedAppState()
        val command = state.autoCmds.single()
        assertEquals(expected, (command.triggers + command.conditions + command.actions).map { it.display })
        assertEquals(expected.size, metas.size)
    }

    @Test fun malformedCanonicalValuesFailClosedAndLeaveSourceBytesUnchanged() {
        val samples = listOf(
            RuleComponent("action.set_brightness", mapOf("value" to JsonPrimitive("50%"))),
            RuleComponent("action.wifi_switch", mapOf("mode" to JsonPrimitive("开启"))),
            RuleComponent("action.delay", mapOf("duration" to JsonPrimitive(-1))),
            RuleComponent("action.copy_clipboard", mapOf("text" to JsonNull)),
            RuleComponent("condition.time_range", mapOf("range" to JsonPrimitive("08:00–22:00"))),
            RuleComponent("condition.battery_range", mapOf("range" to RuleJson.format.parseToJsonElement("{\"min\":80,\"max\":20}"))),
        )
        for ((index, component) in samples.withIndex()) {
            val document = RuleDocument(rules = listOf(com.flowglass.engine.Rule("bad-$index", actions = listOf(component))))
            val source = RuleJson.format.encodeToString(document)
            val file = File(folder.newFolder(), "commands.json").apply { writeText(source) }
            val store = CommandStore(file)
            val loaded = store.load()
            assertNotNull(loaded.error)
            assertNull(loaded.saved)
            assertFalse(store.canSave)
            assertEquals(source, file.readText())
            assertTrue(runCatching { store.save(AppState().snapshot()) }.isFailure)
        }
    }

    @Test fun realStoreMigrationProducesReviewableJsonFixture() {
        val source = requireNotNull(javaClass.getResource("/canonical-v1.json")).readText()
        val file = File(folder.root, "commands.json").apply { writeText(source) }
        val store = CommandStore(file)
        val loaded = store.load()
        assertNull(loaded.error)
        assertEquals(source, File(folder.root, "commands.v1.bak.json").readText())
        val generated = file.readText()
        val document = RuleJson.decode(generated)
        val rule = document.rules.single()
        assertEquals(JsonPrimitive("on"), rule.actions[0].params["mode"])
        assertEquals(JsonPrimitive(50), rule.actions[1].params["value"])
        assertEquals(JsonPrimitive(1500), rule.actions[2].params["duration"])
        assertEquals(JsonPrimitive("wifi"), rule.conditions[2].params["network"])
        assertEquals("legacy.unknown", rule.actions.last().type)
        store.save(requireNotNull(loaded.saved))
        assertEquals(generated, file.readText())
        assertEquals(document, RuleJson.decode(file.readText()))
        val artifact = File("build/reports/migration/commands.v2.json")
        requireNotNull(artifact.parentFile).mkdirs()
        artifact.writeText(generated)
        println("MIGRATED_V2=$generated")
    }
}
