package com.flowglass.demo.ui

import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Files written by the pre-release 0.6.0-alpha1 builds used formatVersion 2 with the
 * old presentation sidecar (name + parameter per component). Those bytes are on real
 * devices, so they must keep loading instead of tripping the fail-safe banner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewV2CompatibilityTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "commands.json")
    private val backup get() = File(folder.root, "commands.v1.bak.json")
    private val previewBackup get() = File(folder.root, "commands.preview-v2.bak.json")
    private fun fixture() = requireNotNull(javaClass.getResource("/preview-v2.json")).readText()

    @Test fun previewV2FileLoadsWithoutTheFailSafeBanner() {
        val original = fixture()
        file.writeText(original)
        val store = CommandStore(file)
        val loaded = store.load()

        assertNull("preview v2 must load, not trip the fail-safe", loaded.error)
        assertNotNull(loaded.saved)
        assertTrue(store.canSave)
    }

    @Test fun previewV2CommandsAndLogsSurviveTheUpgrade() {
        file.writeText(fixture())
        val saved = requireNotNull(CommandStore(file).load().saved)

        val auto = saved.autoCmds.single()
        assertEquals(1L, auto.id)
        assertEquals("到公司自动连 WiFi", auto.title)
        assertEquals("工作日到达公司附近时，打开 WiFi 并调节亮度", auto.desc)
        assertEquals(Ic.Location, auto.ic)
        assertTrue(auto.pinned)
        assertEquals("08:56", auto.lastRun)
        assertEquals(4, auto.category)
        assertEquals(listOf("进入地点：公司"), auto.triggers.map { it.display })
        assertEquals(listOf("开关 WiFi：开启", "调节亮度：10%"), auto.actions.map { it.display })
        assertEquals(listOf("trigger.location_enter"), auto.triggers.map { it.typeId })
        assertEquals(listOf("action.wifi_switch", "action.set_brightness"), auto.actions.map { it.typeId })

        val oneTap = saved.oneTaps.single()
        assertEquals(101L, oneTap.id)
        assertEquals("一键省电", oneTap.title)
        assertEquals(listOf("调节亮度：30%"), oneTap.actions.map { it.display })

        val log = saved.logs.single()
        assertEquals(1001L, log.id)
        assertEquals("一键省电", log.title)
        assertTrue(log.ok)
        assertEquals(Ic.Leaf, log.ic)
        assertEquals(1, log.steps.size)
        assertEquals(1004L, log.steps.single().itemId)
    }

    @Test fun anEarlierV1BackupWithDifferentBytesMustNotBlockThePreviewUpgrade() {
        // Devices that upgraded from v0.5 already carry commands.v1.bak.json; those bytes are
        // the old text document, never the preview bytes, so a shared backup path would abort.
        val earlier = JSONObject().put("version", 2).put("autoCmds", org.json.JSONArray())
            .put("oneTaps", org.json.JSONArray()).put("logs", org.json.JSONArray())
            .put("running", true).put("mode", 1).toString()
        backup.writeText(earlier)

        val original = fixture()
        file.writeText(original)
        val store = CommandStore(file)
        val loaded = store.load()

        assertNull("existing v1 backup must not block the upgrade, was: ${loaded.error}", loaded.error)
        assertNotNull(loaded.saved)
        assertTrue(store.canSave)
        assertEquals("the earlier backup must stay untouched", earlier, backup.readText())
        assertEquals(original, File(folder.root, "commands.preview-v2.bak.json").readText())
        assertEquals(2, JSONObject(file.readText()).getInt("formatVersion"))
    }

    @Test fun aSecondFailedAttemptKeepsBothBackups() {
        val previewBackup = File(folder.root, "commands.preview-v2.bak.json")
        previewBackup.writeText("{\"earlier\":true}")
        file.writeText(fixture())

        val loaded = CommandStore(file).load()

        assertNull(loaded.error)
        assertEquals("{\"earlier\":true}", previewBackup.readText())
        assertEquals(fixture(), File(folder.root, "commands.preview-v2.bak-2.json").readText())
    }

    @Test fun anIdenticalBackupIsReusedInsteadOfMultiplied() {
        val original = fixture()
        File(folder.root, "commands.preview-v2.bak.json").writeText(original)
        file.writeText(original)

        assertNull(CommandStore(file).load().error)

        assertFalse(File(folder.root, "commands.preview-v2.bak-2.json").exists())
    }

    @Test fun previewV2IsCanonicalisedOnDiskWithAByteExactBackup() {
        val original = fixture()
        file.writeText(original)
        val store = CommandStore(file)
        val saved = requireNotNull(store.load().saved)

        assertTrue("preview upgrade must back up the original bytes", previewBackup.isFile)
        assertEquals(original, previewBackup.readText())
        val rewritten = file.readText()
        assertEquals(2, JSONObject(rewritten).getInt("formatVersion"))
        assertFalse("canonical v2 must not persist display text", rewritten.contains("\"parameter\""))
        assertEquals(JsonPrimitive(10), com.flowglass.engine.RuleJson.decode(rewritten).rules
            .first().actions[1].params.getValue("value"))

        store.save(saved)
        val reloaded = requireNotNull(CommandStore(file).load().saved)
        assertEquals(saved.autoCmds, reloaded.autoCmds)
        assertEquals(saved.oneTaps, reloaded.oneTaps)
        assertEquals(saved.logs, reloaded.logs)
    }

    @Test fun canonicalV2WithAStraySidecarKeyStillFailsClosed() {
        // Guard the discriminator: only the documented preview shape is migrated.
        val canonical = JSONObject(AppStateJson.encode(AppState().snapshot()))
        val presentation = canonical.getJSONObject("ui").getJSONObject("presentation")
        val ruleId = presentation.keys().asSequence().first()
        presentation.getJSONObject(ruleId).put("stray", 1)
        val corrupted = canonical.toString()
        file.writeText(corrupted)
        val store = CommandStore(file)
        val loaded = store.load()
        assertNotNull("canonical corruption must not be silently upgraded", loaded.error)
        assertTrue("banner should carry a short reason", loaded.error!!.contains("（"))
        assertFalse(store.canSave)
        assertEquals(corrupted, file.readText())
    }
}
