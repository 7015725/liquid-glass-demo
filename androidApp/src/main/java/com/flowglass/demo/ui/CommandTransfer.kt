package com.flowglass.demo.ui

import android.content.Context
import com.flowglass.engine.ComponentPresentation
import com.flowglass.engine.LegacyCommandMigration
import com.flowglass.engine.Rule
import com.flowglass.engine.RuleBranch
import com.flowglass.engine.RuleComponent
import com.flowglass.engine.RuleDocument
import com.flowglass.engine.RuleJson
import com.flowglass.engine.RulePresentation
import com.flowglass.engine.RuleUiMetadata
import com.flowglass.engine.methodhook.AppMethodCall
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

internal const val COMMAND_FILE_FORMAT = "flowglass.command"
internal const val BACKUP_FILE_FORMAT = "flowglass.backup"
internal const val TRANSFER_FORMAT_VERSION = 1
private const val MAX_TRANSFER_CHARS = 2_000_000
private const val MAX_TRANSFER_DEPTH = 128

private val transferJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    prettyPrint = true
}

internal data class BackupSettings(
    /** ThemeMode.name; unknown values fall back to SYSTEM. */
    val themeMode: String = ThemeMode.SYSTEM.name,
    /** App-local settings that are safe to restore; root authorization is never included. */
    val appSettings: Map<String, String> = emptyMap(),
)

internal enum class ImportKind { COMMAND, BACKUP }

internal data class PreviewCommand(
    val title: String,
    val auto: Boolean,
    val triggerCount: Int,
    val conditionCount: Int,
    val actionCount: Int,
    val triggers: List<String>,
    val conditions: List<String>,
    val actions: List<String>,
    val sensitive: List<String>,
    /** Actions whose only implementation is the Root layer. */
    val rootRequired: List<String> = emptyList(),
)

internal data class ImportPreview(
    val kind: ImportKind,
    val commands: List<PreviewCommand>,
    val themeMode: ThemeMode? = null,
)

internal data class ParsedImport(
    val kind: ImportKind,
    val document: RuleDocument,
    val converted: PersistedAppState,
    val preview: ImportPreview,
    val settings: BackupSettings? = null,
)

/** Human-readable text used by both share entry points. */
internal fun commandShareText(cmd: Cmd): String = buildString {
    appendLine("【流光自动化】${cmd.title}")
    appendLine(cmd.desc)
    appendLine(if (cmd.auto) "类型：自动指令" else "类型：一键指令")
    if (cmd.auto) {
        appendLine("触发器：${cmd.triggers.joinToString("、")}")
        if (cmd.conditions.isNotEmpty()) appendLine("条件：${cmd.conditions.joinToString("、")}")
    }
    append("任务：${cmd.actions.joinToString(" → ")}")
}

internal fun exportCommandJson(state: AppState, cmd: Cmd): String {
    val document = commandDocument(state, cmd)
    // Validate the embedded document before producing a file users may restore later.
    RuleJson.encode(document)
    return transferJson.encodeToString(
        JsonElement.serializer(),
        envelope(COMMAND_FILE_FORMAT, document),
    ).also(::validateTransferText)
}

internal fun exportBackupJson(
    state: AppState,
    themeMode: ThemeMode,
    appSettings: Map<String, String> = emptyMap(),
): String = exportBackupJson(state.snapshot(), themeMode, appSettings)

/** Same backup envelope, built from an already-captured immutable snapshot. */
internal fun exportBackupJson(
    snapshot: PersistedAppState,
    themeMode: ThemeMode,
    appSettings: Map<String, String> = emptyMap(),
): String {
    val document = snapshot.toRuleDocument()
    RuleJson.encode(document)
    return transferJson.encodeToString(
        JsonElement.serializer(),
        envelope(BACKUP_FILE_FORMAT, document, BackupSettings(themeMode.name, appSettings)),
    ).also(::validateTransferText)
}

private fun envelope(format: String, document: RuleDocument, settings: BackupSettings? = null) =
    buildJsonObject {
        put("format", format)
        put("formatVersion", TRANSFER_FORMAT_VERSION)
        put("exportedAt", System.currentTimeMillis())
        if (settings != null) {
            put("settings", buildJsonObject {
                put("themeMode", settings.themeMode)
                settings.appSettings.forEach { (key, value) -> put(key, value) }
            })
        }
        put("document", transferJson.parseToJsonElement(RuleJson.encode(document)))
    }

private fun commandDocument(state: AppState, cmd: Cmd): RuleDocument {
    val ruleId = state.engineUi.presentation.entries
        .firstOrNull { it.value.legacyId == cmd.id }?.key
    val rule = state.engineRules.firstOrNull { it.id == ruleId }
    val sidecar = ruleId?.let { state.engineUi.presentation[it] }
    val ui = state.engineUi.copy(
        logs = emptyList(),
        presentation = if (ruleId != null && sidecar != null) mapOf(ruleId to sidecar) else emptyMap(),
    )
    val knownGlobals = state.engineGlobals.keys + state.engineGlobalMeta.keys
    val usedGlobals = usedGlobalNames(cmd, knownGlobals)
    return PersistedAppState(
        autoCmds = if (cmd.auto) listOf(cmd) else emptyList(),
        oneTaps = if (cmd.auto) emptyList() else listOf(cmd),
        logs = emptyList(),
        running = state.running,
        mode = state.mode,
        engineRules = listOfNotNull(rule),
        engineUi = ui,
        engineGlobals = emptyMap(),
        engineGlobalMeta = state.engineGlobalMeta.filterKeys { it in usedGlobals },
    ).toRuleDocument()
}

/** A single-command export only declares globals that the command actually references. */
private fun usedGlobalNames(cmd: Cmd, candidates: Set<String>): Set<String> {
    val parameters = (cmd.triggers + cmd.conditions + cmd.actions).allItems().map { it.parameter }
    return candidates.filterTo(linkedSetOf()) { name ->
        parameters.any { text ->
            text.contains("{$name}") || text.contains("%{global.$name}") || text.contains("%{$name}")
        }
    }
}

/** Retired pre-release trigger that [CommandStore.load] refuses to read. */
private fun RuleComponent.isRetiredMethodHook(): Boolean =
    type == "trigger.method_hook" ||
        children.any { it.isRetiredMethodHook() } ||
        branches.any { branch -> branch.children.any { it.isRetiredMethodHook() } }

private fun RuleDocument.hasRetiredMethodHookTrigger(): Boolean =
    rules.any { rule ->
        (rule.triggers + rule.conditions + rule.actions).any { it.isRetiredMethodHook() }
    }

/**
 * 有界读取导入文件：先按 UTF-8 最多 4 字节/字符截断，再用严格解码器校验。
 * 不能在 readText() 之后再检查长度——那已经把整个文件放进内存了。
 */
internal fun readImportText(input: java.io.InputStream, maxChars: Int = MAX_TRANSFER_CHARS): String {
    val maxBytes = maxChars.toLong() * 4L + 4L
    val collected = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "文件过大" }
        collected.write(chunk, 0, read)
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    val text = decoder.decode(java.nio.ByteBuffer.wrap(collected.toByteArray())).toString()
    require(text.length <= maxChars) { "文件过大" }
    return text
}

internal fun parseImport(text: String, state: AppState): ParsedImport {
    validateTransferText(text)
    val root = transferJson.parseToJsonElement(text) as? JsonObject
        ?: throw IllegalArgumentException("文件内容不是 JSON 对象")
    val format = root["format"]?.jsonPrimitive?.contentOrNull
    return when (format) {
        COMMAND_FILE_FORMAT -> {
            require(root["formatVersion"]?.jsonPrimitive?.intOrNull == TRANSFER_FORMAT_VERSION) {
                "不支持的导入格式版本"
            }
            buildParsed(ImportKind.COMMAND, documentField(root), state, null)
        }
        BACKUP_FILE_FORMAT -> {
            require(root["formatVersion"]?.jsonPrimitive?.intOrNull == TRANSFER_FORMAT_VERSION) {
                "不支持的备份格式版本"
            }
            val settings = (root["settings"] as? JsonObject)?.let { settings ->
                BackupSettings(
                    themeMode = settings["themeMode"]?.jsonPrimitive?.contentOrNull
                        ?: ThemeMode.SYSTEM.name,
                    appSettings = settings.entries
                        .filter { it.key != "themeMode" && it.value is JsonPrimitive }
                        .associate { (key, value) -> key to value.jsonPrimitive.content },
                )
            }
            buildParsed(ImportKind.BACKUP, documentField(root), state, settings)
        }
        null -> {
            // Accept a bare RuleDocument for compatibility with hand-written/test fixtures.
            val document = transferJson.decodeFromJsonElement(RuleDocument.serializer(), root)
            buildParsed(ImportKind.COMMAND, document, state, null)
        }
        else -> throw IllegalArgumentException("不支持的文件格式：$format")
    }
}

private fun documentField(root: JsonObject): RuleDocument {
    val element = root["document"] ?: throw IllegalArgumentException("文件缺少指令数据")
    return transferJson.decodeFromJsonElement(RuleDocument.serializer(), element)
}

private fun buildParsed(
    kind: ImportKind,
    rawDocument: RuleDocument,
    state: AppState,
    settings: BackupSettings?,
): ParsedImport {
    val document = normalizeDocument(rawDocument)
    val initial = document.toPersistedAppState()
    // Same policy as CommandStore.load: a document that could never be loaded again must never be
    // written. Checked on the rule document because the legacy conversion drops the retired type.
    require(!document.hasRetiredMethodHookTrigger()) {
        "文件包含已退役的方法钩子触发器，当前版本无法导入；请在新版中重新创建该规则。"
    }
    // A method-listening command is sensitive: importing never silently enables it, even in a
    // full backup. Auto commands are disabled for one-shot command imports below as well.
    fun List<Cmd>.disableMethodCallCommands(): List<Cmd> = map { command ->
        if (command.triggers.any { it.typeId == AppMethodCall.TYPE_ID }) {
            command.copy(enabled = false)
        } else {
            command
        }
    }
    val converted = PersistedAppState(
        autoCmds = initial.autoCmds.disableMethodCallCommands(),
        oneTaps = initial.oneTaps.disableMethodCallCommands(),
        logs = initial.logs,
        running = initial.running,
        mode = initial.mode,
        engineRules = initial.engineRules,
        engineUi = initial.engineUi,
        engineGlobals = initial.engineGlobals,
        engineGlobalMeta = initial.engineGlobalMeta,
    )
    val commands = converted.autoCmds + converted.oneTaps
    require(commands.isNotEmpty() || kind == ImportKind.BACKUP) { "文件不包含任何指令" }
    val used = (state.autoCmds + state.oneTaps)
        .mapTo(mutableSetOf()) { it.title.trim().lowercase(Locale.ROOT) }
    val previewCommands = commands.map { cmd ->
        val planned = uniqueCommandTitle(cmd.title, cmd.auto, used)
        used += planned.trim().lowercase(Locale.ROOT)
        PreviewCommand(
            title = planned,
            auto = cmd.auto,
            triggerCount = cmd.triggers.size,
            conditionCount = cmd.conditions.size,
            actionCount = cmd.actions.allItems().size,
            triggers = cmd.triggers.map { it.display },
            conditions = cmd.conditions.map { it.display },
            actions = cmd.actions.allItems().map { it.display },
            sensitive = sensitiveActions(cmd),
            rootRequired = rootOnlyActions(cmd),
        )
    }
    return ParsedImport(
        kind = kind,
        document = document,
        converted = converted,
        preview = ImportPreview(
            kind = kind,
            commands = previewCommands,
            themeMode = settings?.themeMode?.let { raw ->
                ThemeMode.entries.firstOrNull { it.name == raw }
            },
        ),
        settings = settings,
    )
}

private fun normalizeDocument(document: RuleDocument): RuleDocument {
    val text = transferJson.encodeToString(RuleDocument.serializer(), document)
    return RuleJson.decodeWithMigration(text).document
}

/**
 * Applies a single-command import by merging it into a document snapshot. Conversion is done
 * completely before [AppState.replaceWith], so a malformed import never leaves partial data.
 */
internal fun AppState.applyCommandImport(parsed: ParsedImport) {
    require(parsed.kind == ImportKind.COMMAND) { "这不是指令文件" }
    val current = snapshot().toRuleDocument()
    val importedState = parsed.converted
    val used = (autoCmds + oneTaps).mapTo(mutableSetOf()) { it.title.trim().lowercase(Locale.ROOT) }
    val pieces = mutableListOf<RuleDocument>()
    for (source in importedState.autoCmds + importedState.oneTaps) {
        val sourceRuleId = importedState.engineUi.presentation.entries
            .firstOrNull { it.value.legacyId == source.id }?.key
        val sourceRule = importedState.engineRules.firstOrNull { it.id == sourceRuleId }
        val sourceSidecar = sourceRuleId?.let { importedState.engineUi.presentation[it] }
        val newCommandId = newId()
        val plannedTitle = uniqueCommandTitle(source.title, source.auto, used)
        used += plannedTitle.trim().lowercase(Locale.ROOT)
        val idMap = mutableMapOf<Long, Long>()
        registerItemIds(source.actions, idMap)
        registerItemIds(source.triggers, idMap)
        registerItemIds(source.conditions, idMap)
        val clonedCommand = cloneCommand(
            source, newCommandId, plannedTitle,
            enabled = if (source.auto) false else source.enabled,
            idMap = idMap,
        )
        val newRuleId = LegacyCommandMigration.uuidForLegacyId(newCommandId)
        val clonedRule = sourceRule?.let {
            cloneRule(it, newRuleId, plannedTitle, clonedCommand.enabled, idMap)
        }
        val clonedSidecar = sourceSidecar?.let { sidecar ->
            RulePresentation(
                category = sidecar.category,
                icon = clonedCommand.ic.name,
                pinned = clonedCommand.pinned,
                lastRun = "从未",
                descriptionEdited = clonedCommand.descriptionEdited,
                legacyId = newCommandId,
                items = sidecar.items.mapNotNull { (oldId, presentation) ->
                    idMap[oldId]?.let { it to ComponentPresentation(it, presentation.icon) }
                }.toMap(),
                collapsed = sidecar.collapsed.mapNotNull(idMap::get),
            )
        }
        val tempUi = RuleUiMetadata(
            presentation = if (clonedRule != null && clonedSidecar != null) {
                mapOf(newRuleId to clonedSidecar)
            } else emptyMap(),
        )
        pieces += PersistedAppState(
            autoCmds = if (clonedCommand.auto) listOf(clonedCommand) else emptyList(),
            oneTaps = if (clonedCommand.auto) emptyList() else listOf(clonedCommand),
            logs = emptyList(),
            running = running,
            mode = mode,
            engineRules = listOfNotNull(clonedRule),
            engineUi = tempUi,
            engineGlobals = emptyMap(),
            engineGlobalMeta = importedState.engineGlobalMeta,
        ).toRuleDocument()
    }
    val importedMeta = pieces.flatMap { piece -> piece.globalMeta.entries }
        .associate { (name, meta) -> name to meta }
    val merged = current.copy(
        rules = current.rules + pieces.flatMap { it.rules },
        globalMeta = importedMeta + current.globalMeta,
        ui = current.ui.copy(
            presentation = current.ui.presentation + pieces.flatMap { it.ui.presentation.entries }
                .associate { it.key to it.value },
        ),
    )
    replaceWith(merged.toPersistedAppState())
}

/**
 * A restore is data import, not consent to run it. Automatic rules, the engine and background
 * capabilities from the file start disabled; the user re-enables them explicitly afterwards.
 */
internal fun PersistedAppState.disarmedAfterRestore(): PersistedAppState = PersistedAppState(
    autoCmds = autoCmds.map { it.copy(enabled = false) },
    oneTaps = oneTaps,
    logs = logs,
    running = false,
    mode = mode,
    engineRules = engineRules,
    engineUi = engineUi.copy(running = false),
    engineGlobals = engineGlobals,
    engineGlobalMeta = engineGlobalMeta,
)

/**
 * Restores a full backup after writing the current in-memory snapshot to a safety file. The
 * document has already been validated and converted by [parseImport].
 */
internal fun restoreBackup(context: Context, state: AppState, parsed: ParsedImport): File =
    restoreBackup(
        state,
        parsed,
        File(context.filesDir, "backups"),
        exportBackupJson(state, AppearanceSettings.read(context), readBackupAppSettings(context)),
    )

internal fun restoreBackup(
    state: AppState,
    parsed: ParsedImport,
    backupFolder: File,
    now: () -> Long = System::currentTimeMillis,
    newId: () -> String = { java.util.UUID.randomUUID().toString() },
    readBack: (File) -> ByteArray = File::readBytes,
): File = restoreBackup(state, parsed, backupFolder, exportBackupJson(state, ThemeMode.SYSTEM), now, newId, readBack)

private fun restoreBackup(
    state: AppState,
    parsed: ParsedImport,
    backupFolder: File,
    safetyText: String,
    now: () -> Long = System::currentTimeMillis,
    newId: () -> String = { java.util.UUID.randomUUID().toString() },
    readBack: (File) -> ByteArray = File::readBytes,
): File {
    require(parsed.kind == ImportKind.BACKUP) { "这不是备份文件" }
    val safety = writeSafetyBackup(backupFolder, safetyText, now, newId, readBack)
    state.replaceWith(parsed.converted)
    return safety
}

/**
 * Immutable inputs for the pre-restore safety copy. Captured on the caller's thread so that the
 * blocking serialization, write and read-back pass never has to read live state from a worker.
 */
internal class RestoreSafetyInput internal constructor(
    internal val snapshot: PersistedAppState,
    internal val themeMode: ThemeMode,
    internal val appSettings: Map<String, String>,
)

/** Captures the safety-copy inputs without writing anything; safe to call on the main thread. */
internal fun restoreSafetyInput(context: Context, state: AppState): RestoreSafetyInput =
    RestoreSafetyInput(
        snapshot = state.snapshot(),
        themeMode = AppearanceSettings.read(context),
        appSettings = readBackupAppSettings(context),
    )

/**
 * Serializes, writes and byte-verifies the pre-restore safety copy. This performs blocking disk
 * I/O and must be called from a worker dispatcher, never from the Compose main thread.
 */
internal fun writeRestoreSafety(folder: File, input: RestoreSafetyInput): File =
    writeSafetyBackup(
        folder,
        exportBackupJson(input.snapshot, input.themeMode, input.appSettings),
        System::currentTimeMillis,
        { java.util.UUID.randomUUID().toString() },
        File::readBytes,
    )

private fun writeSafetyBackup(folder: File, text: String, now: () -> Long, newId: () -> String, readBack: (File) -> ByteArray): File {
    check(folder.exists() || folder.mkdirs()) { "无法创建备份目录" }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now()))
    repeat(32) {
        val file = File(folder, "before-restore-$stamp-${newId()}.json")
        if (!file.createNewFile()) return@repeat
        val atomic = android.util.AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            check(readBack(file).contentEquals(text.toByteArray(Charsets.UTF_8))) { "安全副本校验失败，已取消恢复" }
            return file
        } catch (failure: Throwable) {
            runCatching { atomic.failWrite(stream) }
            runCatching { atomic.delete() }
            throw failure
        }
    }
    error("无法创建不重复的安全副本")
}

internal fun listSafetyBackups(folder: File, state: AppState): List<File> =
    folder.listFiles().orEmpty()
        .filter { file ->
            file.isFile && file.name.startsWith("before-restore-") && file.extension == "json" &&
                file.canonicalFile.parentFile == folder.canonicalFile &&
                runCatching { readSafetyBackup(file, state) }.isSuccess
        }
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

internal fun readSafetyBackup(file: File, state: AppState): ParsedImport {
    val text = file.bufferedReader(Charsets.UTF_8).use { reader ->
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(text.length + count <= MAX_TRANSFER_CHARS) { "文件过大" }
            text.append(buffer, 0, count)
        }
        text.toString()
    }
    return parseImport(text, state).also { require(it.kind == ImportKind.BACKUP) { "这不是完整备份文件" } }
}

private fun validateTransferText(text: String) {
    require(text.length <= MAX_TRANSFER_CHARS) { "文件过大" }
    var depth = 0
    var quote = '\u0000'
    var escaped = false
    for (char in text) {
        if (quote != '\u0000') {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == quote) quote = '\u0000'
        } else when (char) {
            '"' -> quote = char
            '{', '[' -> {
                depth++
                require(depth <= MAX_TRANSFER_DEPTH) { "文件嵌套过深" }
            }
            '}', ']' -> depth--
        }
    }
    require(depth == 0) { "文件 JSON 结构不完整" }
}

private fun sensitiveActions(cmd: Cmd): List<String> {
    val methodCall = cmd.triggers
        .filter { it.typeId == AppMethodCall.TYPE_ID }
        .map { "应用方法调用（敏感：会在其他应用内监听方法）" }
    val registry = com.flowglass.engine.BuiltInComponents.registry()
    return methodCall + cmd.actions.allItems().mapNotNull { item ->
        val meta = item.typeId?.let(registry::find) ?: return@mapNotNull null
        val rootOnly = meta.supports.any {
            it.layer == com.flowglass.engine.ExecutionLayer.ROOT
        } && meta.supports.none { it.layer == com.flowglass.engine.ExecutionLayer.NORMAL }
        val dangerous = item.typeId in setOf(
            "action.root_shell", "action.http_request", "action.run_rule", "action.script",
        ) || rootOnly
        if (dangerous) meta.name else null
    }.distinct()
}

/** Root-only means there is no NORMAL implementation declared by the new layer framework. */
private fun rootOnlyActions(cmd: Cmd): List<String> {
    val registry = com.flowglass.engine.BuiltInComponents.registry()
    return cmd.actions.allItems().mapNotNull { item ->
        val meta = item.typeId?.let(registry::find) ?: return@mapNotNull null
        val rootOnly = meta.supports.any { it.layer == com.flowglass.engine.ExecutionLayer.ROOT } &&
            meta.supports.none { it.layer == com.flowglass.engine.ExecutionLayer.NORMAL }
        if (rootOnly) meta.name else null
    }.distinct()
}

/**
 * App-local settings that are safe and useful to move with a backup. Root authorization is
 * deliberately absent: it belongs to the device's root manager and must be granted again.
 */
internal fun readBackupAppSettings(context: Context): Map<String, String> {
    val out = linkedMapOf<String, String>()
    val keep = context.getSharedPreferences("keepalive", Context.MODE_PRIVATE)
    for (key in listOf("fgs", "boot", "job")) {
        if (keep.contains(key)) out["keepalive.$key"] = keep.getBoolean(key, false).toString()
    }
    out["system_hook.enabled"] =
        com.flowglass.demo.system.SystemHookSettings.isEnabled(context).toString()
    return out
}

internal fun applyBackupAppSettings(
    context: Context,
    settings: Map<String, String>,
    includeBackground: Boolean = false,
) {
    if (!includeBackground) return
    for (key in listOf("fgs", "boot", "job")) {
        settings["keepalive.$key"]?.toBooleanStrictOrNull()?.let {
            com.flowglass.demo.system.KeepAlive.set(context, key, it)
        }
    }
    settings["system_hook.enabled"]?.toBooleanStrictOrNull()?.let {
        com.flowglass.demo.system.SystemHookSettings.setEnabled(context, it)
    }
}

internal fun uniqueCommandTitle(base: String, auto: Boolean, used: MutableSet<String>): String {
    val clean = base.trim().ifBlank { if (auto) "未命名指令" else "未命名一键指令" }
    if (clean.lowercase(Locale.ROOT) !in used) return clean
    var index = 1
    while (true) {
        val suffix = if (index == 1) "（导入）" else "（导入 $index）"
        val candidate = clean + suffix
        if (candidate.lowercase(Locale.ROOT) !in used) return candidate
        index++
        check(index < 10_000) { "名称冲突过多" }
    }
}

private fun registerItemIds(items: List<CommandItem>, map: MutableMap<Long, Long>) {
    for (item in items) {
        map[item.id] = newId()
        registerItemIds(item.conditions, map)
        registerItemIds(item.children, map)
        for (branch in item.branches) {
            registerItemIds(branch.conditions, map)
            registerItemIds(branch.children, map)
        }
    }
}

private fun cloneCommand(
    source: Cmd,
    id: Long,
    title: String,
    enabled: Boolean,
    idMap: Map<Long, Long>,
): Cmd {
    fun items(values: List<CommandItem>): List<CommandItem> = values.map { item ->
        item.copy(
            id = idMap[item.id] ?: newId(),
            conditions = items(item.conditions),
            children = items(item.children),
            branches = item.branches.map { branch ->
                CommandBranch(children = items(branch.children), conditions = items(branch.conditions))
            },
        )
    }
    return source.copy(
        id = id,
        title = title,
        enabled = enabled,
        pinned = false,
        lastRun = "从未",
        triggers = items(source.triggers),
        conditions = items(source.conditions),
        actions = items(source.actions),
    )
}

private fun cloneRule(
    source: Rule,
    id: String,
    name: String,
    enabled: Boolean,
    idMap: Map<Long, Long>,
): Rule {
    fun components(values: List<RuleComponent>): List<RuleComponent> = values.map { component ->
        component.copy(
            id = component.id?.let { idMap[it] },
            conditions = components(component.conditions),
            children = components(component.children),
            branches = component.branches.map { branch ->
                RuleBranch(
                    conditions = components(branch.conditions),
                    children = components(branch.children),
                )
            },
        )
    }
    return source.copy(
        id = id,
        name = name,
        enabled = enabled,
        triggers = components(source.triggers),
        conditions = components(source.conditions),
        actions = components(source.actions),
    )
}
