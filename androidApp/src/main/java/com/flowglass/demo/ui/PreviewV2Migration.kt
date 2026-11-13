package com.flowglass.demo.ui

import com.flowglass.engine.BuiltInComponents
import com.flowglass.engine.LegacyCommandMigration
import com.flowglass.engine.ParameterCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject

/**
 * Files written by the pre-release 0.6.0-alpha1 builds already carried `formatVersion: 2`,
 * but each presentation component still stored the display text (`name` + `parameter`) and
 * the canonical value types did not exist yet. Those bytes exist on real devices, so they are
 * converted through the same text-based migration as the v0.5 files instead of being rejected.
 *
 * The discriminator is structural: only the preview shape carries `name`/`parameter` inside a
 * presentation component. A canonical v2 file never does, so genuine corruption still fails closed.
 */
internal object PreviewV2Migration {
    private val kinds = listOf("triggers", "conditions", "actions")
    private val registry = BuiltInComponents.registry()
    private val json = Json { ignoreUnknownKeys = true }

    fun looksLikePreview(root: JSONObject): Boolean {
        if (root.optInt("formatVersion", -1) != 2) return false
        val presentation = root.optJSONObject("ui")?.optJSONObject("presentation") ?: return false
        for (ruleId in presentation.keys()) {
            val sidecar = presentation.optJSONObject(ruleId) ?: continue
            for (kind in kinds) {
                val items = sidecar.optJSONArray(kind) ?: continue
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    if (item.has("name") || item.has("parameter")) return true
                }
            }
        }
        return false
    }

    /** Rewrites the preview document into the legacy text schema, then reuses the v0.5 migration. */
    fun toLegacyJson(root: JSONObject): String {
        val ui = root.optJSONObject("ui") ?: JSONObject()
        val presentation = ui.optJSONObject("presentation") ?: JSONObject()
        val rules = root.optJSONArray("rules") ?: JSONArray()
        val ids = mutableListOf<Long>()
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            val sidecar = presentation.optJSONObject(rule.optString("id"))
            sidecar?.let { ids += sidecar.optLong("legacyId") }
            for (kind in kinds) {
                val items = sidecar?.optJSONArray(kind) ?: continue
                for (itemIndex in 0 until items.length()) ids += items.getJSONObject(itemIndex).optLong("id")
            }
        }
        for (log in ui.optJSONArray("logs").orEmpty()) ids += log.optLong("id")
        var nextId = maxOf(1000L, (ids.filter { it > 0L }.maxOrNull() ?: 999L) + 1L)

        fun allocate(): Long {
            check(nextId < Long.MAX_VALUE) { "兼容标识已用尽" }
            return nextId++
        }

        fun items(rule: JSONObject, sidecar: JSONObject?, kind: String): JSONArray {
            val components = rule.optJSONArray(kind) ?: JSONArray()
            val sidecars = sidecar?.optJSONArray(kind) ?: JSONArray()
            val count = maxOf(components.length(), sidecars.length())
            val result = JSONArray()
            for (index in 0 until count) {
                val component = components.optJSONObject(index)
                val presentationItem = sidecars.optJSONObject(index)
                val meta = component?.optString("type")?.takeIf { it.isNotEmpty() }?.let(registry::find)
                val name = presentationItem?.optString("name")?.takeIf { it.isNotEmpty() }
                    ?: meta?.name
                    ?: component?.optString("type").orEmpty()
                val parameter = presentationItem?.optString("parameter")
                    ?: component?.parameterText(meta)
                    ?: ""
                result.put(JSONObject()
                    .put("id", presentationItem?.optLong("id") ?: allocate())
                    .put("name", name)
                    .put("parameter", parameter)
                    .put("ic", presentationItem?.optString("icon")?.takeIf { it.isNotEmpty() } ?: "Bolt"))
            }
            return result
        }

        fun command(rule: JSONObject): JSONObject {
            val sidecar = presentation.optJSONObject(rule.optString("id"))
            return JSONObject()
                .put("id", sidecar?.optLong("legacyId") ?: allocate())
                .put("auto", rule.optString("type") == "auto")
                .put("ic", sidecar?.optString("icon")?.takeIf { it.isNotEmpty() } ?: "Bolt")
                .put("title", rule.optString("name"))
                .put("desc", rule.optString("description"))
                .put("triggers", items(rule, sidecar, "triggers"))
                .put("conditions", items(rule, sidecar, "conditions"))
                .put("actions", items(rule, sidecar, "actions"))
                .put("category", sidecar?.optInt("category") ?: 0)
                .put("enabled", rule.optBoolean("enabled", true))
                .put("pinned", sidecar?.optBoolean("pinned") ?: false)
                .put("lastRun", sidecar?.optString("lastRun") ?: "从未")
                .put("descriptionEdited", sidecar?.optBoolean("descriptionEdited") ?: true)
        }

        val auto = JSONArray()
        val manual = JSONArray()
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            if (rule.optString("type") == "auto") auto.put(command(rule)) else manual.put(command(rule))
        }
        val logs = JSONArray()
        for (log in ui.optJSONArray("logs").orEmpty()) {
            val steps = JSONArray()
            for (step in log.optJSONArray("steps").orEmpty()) {
                steps.put(JSONObject()
                    .put("itemId", step.optLong("itemId"))
                    .put("name", step.optString("name"))
                    .put("status", step.optString("status"))
                    .put("detail", step.optString("detail"))
                    .put("durationMs", step.optLong("durationMs")))
            }
            logs.put(JSONObject()
                .put("id", log.optLong("id"))
                .put("title", log.optString("title"))
                .put("reason", log.optString("reason"))
                .put("time", log.optString("time"))
                .put("ok", log.optBoolean("ok", true))
                .put("ic", log.optString("icon").ifEmpty { "Bolt" })
                .put("detail", log.optString("detail"))
                .put("steps", steps))
        }
        return JSONObject()
            .put("version", 2)
            .put("autoCmds", auto)
            .put("oneTaps", manual)
            .put("logs", logs)
            .put("running", ui.optBoolean("running", true))
            .put("mode", ui.optInt("mode", 1))
            .toString()
    }

    private fun JSONObject.parameterText(meta: com.flowglass.engine.ComponentMeta?): String {
        val params = optJSONObject("params")?.let { raw ->
            runCatching { json.parseToJsonElement(raw.toString()).jsonObject }.getOrNull()
        }
        return when {
            meta != null && params != null -> ParameterCodec.formatComponent(meta, params)
            params != null -> params.toString()
            else -> ""
        }
    }

    private fun JSONArray?.orEmpty(): List<JSONObject> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

}
