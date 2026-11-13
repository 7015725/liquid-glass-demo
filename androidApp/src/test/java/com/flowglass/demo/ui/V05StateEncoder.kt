package com.flowglass.demo.ui

import org.json.JSONArray
import org.json.JSONObject

/** Encoder copied verbatim from v0.5.0 (1b7d67d), AppPersistence.kt.
 * Keep the real released wire format in regression tests; not the new v2 encoder.
 */
internal object V05StateEncoder {
    fun encode(state: PersistedAppState): String = JSONObject().apply {
        put("version", 2)
        put("autoCmds", array(state.autoCmds, ::commandJson))
        put("oneTaps", array(state.oneTaps, ::commandJson))
        put("logs", array(state.logs) { log -> JSONObject().apply {
            put("id", log.id); put("title", log.title); put("reason", log.reason)
            put("time", log.time); put("ok", log.ok); put("ic", log.ic.name)
            put("detail", log.detail)
            put("steps", array(log.steps) { step -> JSONObject().apply {
                put("itemId", step.itemId); put("name", step.name); put("status", step.status.name)
                put("detail", step.detail); put("durationMs", step.durationMs)
            } })
        } })
        put("running", state.running)
        put("mode", state.mode)
    }.toString()

    private fun commandJson(c: Cmd) = JSONObject().apply {
        put("id", c.id); put("auto", c.auto); put("ic", c.ic.name)
        put("title", c.title); put("desc", c.desc); put("descriptionEdited", c.descriptionEdited)
        put("triggers", array(c.triggers, ::itemJson)); put("conditions", array(c.conditions, ::itemJson))
        put("actions", array(c.actions, ::itemJson)); put("category", c.category)
        put("enabled", c.enabled); put("pinned", c.pinned); put("lastRun", c.lastRun)
    }

    private fun itemJson(item: CommandItem) = JSONObject().apply {
        put("id", item.id); put("name", item.name); put("parameter", item.parameter); put("ic", item.ic.name)
    }

    private fun <T> array(values: List<T>, encode: (T) -> JSONObject) = JSONArray().apply {
        values.forEach { put(encode(it)) }
    }
}
