package com.flowglass.demo.ui

import android.content.Context
import android.content.Intent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import com.flowglass.demo.liquid.LiquidToggle
import com.flowglass.demo.system.exec.ComponentAvailability
import com.flowglass.demo.system.exec.LayerStatus
import com.flowglass.demo.system.exec.componentAvailability
import com.flowglass.demo.system.trigger.listenState
import com.flowglass.engine.ExecutionLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.state.ToggleableState
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/** Editor UI; native execution lifetime is owned by ExecutionRuntime. */
@Composable
fun CommandEditorHost(state: AppState, onAuthorization: (Int) -> Unit = { page ->
    state.closeEditor()
    state.settingsPage = page
}) {
    val scope = rememberCoroutineScope()
    val cmd = state.editing?.let(state::find)
    var last by remember { mutableStateOf<Cmd?>(null) }
    if (cmd != null) last = cmd
    val shown = cmd ?: last
    AnimatedVisibility(cmd != null,
        enter = fadeIn(effect()) + scaleIn(spatial(), initialScale = 0.96f),
        exit = fadeOut(effect()) + scaleOut(spatial(), targetScale = 0.96f)) {
        if (shown != null) key(shown.id) { CommandEditor(state, shown, scope, onAuthorization) }
    }
}

@Composable
private fun CommandEditor(state: AppState, cmd: Cmd, scope: CoroutineScope, onAuthorization: (Int) -> Unit) {
    RefreshEditorRoot(state, cmd)
    val context = androidx.compose.ui.platform.LocalContext.current
    val tick = com.flowglass.demo.system.SysTick.tick
    val issues = state.validationItems(cmd)
    var panel by remember { mutableStateOf<EditorPanel?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    val moreAnchor = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var titleFocused by remember { mutableStateOf(false) }
    val backdrop = rememberLayerBackdrop()
    val exec = state.executing[cmd.id] ?: 0
    val currentBlock = state.blockPath.lastOrNull()?.let { cmd.actions.findActionNode(it) }
    LaunchedEffect(cmd.id, cmd.actions, state.blockPath) {
        val valid = state.validBlockPath(state.blockPath)
        if (valid != state.blockPath) state.blockPath = valid
    }
    LaunchedEffect(cmd.id, state.blockPath) { panel = null }
    fun back() {
        if (panel != null) panel = null
        else if (state.blockPath.isNotEmpty()) state.backBlock()
        else state.closeEditor()
    }
    BackHandler(enabled = state.editing == cmd.id) { back() }
    Box(Modifier.fillMaxSize().background(C.Bg).testTag("command-editor")
        .pointerInput(Unit) { detectTapGestures { } }) {
    if (currentBlock == null) {
    Column(Modifier.fillMaxSize().layerBackdrop(backdrop)
        .then(if (panel != null) Modifier.clearAndSetSemantics { } else Modifier)
        .statusBarsPadding().navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailTopBar(
            onBack = { back() },
            title = {
                // BasicTextField cannot ellipsize when unfocused; show a one-line preview and
                // swap in the editable field only while it has focus.
                Box(Modifier.fillMaxWidth()) {
                    if (!titleFocused) {
                        T(
                            cmd.title.ifEmpty { "指令名称" }, 20.sp, FontWeight.Bold,
                            if (cmd.title.isEmpty()) C.Muted else C.OnSurface,
                            modifier = Modifier.fillMaxWidth(), maxLines = 1,
                        )
                    }
                    BasicTextField(
                        cmd.title, { state.setTitle(cmd.id, it) },
                        Modifier.fillMaxWidth().testTag("editor-name").imeAutoScroll()
                            .onFocusChanged { titleFocused = it.isFocused }
                            .alpha(if (titleFocused) 1f else 0f),
                        textStyle = TextStyle(color = C.OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        singleLine = true,
                        cursorBrush = SolidColor(C.Primary),
                        decorationBox = { inner -> Box { if (cmd.title.isEmpty() && titleFocused) T("指令名称", 20.sp, color = C.Muted, maxLines = 1); inner() } },
                    )
                }
            },
            trailing = {
                EditorRunButton(
                    exec,
                    Modifier.testTag("editor-execute"),
                    height = TopBarControlVisual,
                    onClick = { state.execute(cmd, scope, if (cmd.auto) "手动测试" else "手动执行") },
                    onStop = if (exec == 1) ({ state.stopExecution(cmd.id) }) else null,
                )
                Spacer(Modifier.width(4.dp))
                Box {
                    TopBarIconButton(
                        Ic.More, "更多",
                        modifier = Modifier.trackMenuAnchor(moreAnchor, moreOpen),
                    ) { moreOpen = true }
                    if (moreOpen) {
                        AnchoredMenu(
                            anchor = moreAnchor.value,
                            onDismiss = { moreOpen = false },
                            items = listOf(
                                MenuItemSpec("编辑描述", Ic.Edit) { moreOpen = false; panel = EditorPanel.Description },
                                MenuItemSpec("变量", Ic.Puzzle) { moreOpen = false; panel = EditorPanel.Variables },
                                MenuItemSpec("换图标", Ic.Puzzle) { moreOpen = false; panel = EditorPanel.Icons },
                                MenuItemSpec("创建副本", Ic.Copy) {
                                    moreOpen = false
                                    state.duplicate(cmd.id)?.let { copyId ->
                                        state.openEditor(copyId)
                                        state.editorNotice = "已创建副本"
                                    }
                                },
                                MenuItemSpec("分享", Ic.Share) { moreOpen = false; shareCommand(context, cmd) },
                                MenuItemSpec("删除指令", Ic.Trash, danger = true) {
                                    moreOpen = false
                                    confirmDelete = true
                                },
                            ),
                        )
                    }
                }
            },
        )
        if (cmd.descriptionEdited && cmd.desc.isNotBlank()) {
            T(cmd.desc, 13.sp, color = C.OnSurfaceVar, maxLines = 2, lineHeight = 18.sp)
        }
        if (cmd.auto) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ListenStatePill(cmd.listenState(state.running, context))
                Spacer(Modifier.weight(1f))
                AutoEnableToggle(state, cmd, context, issues.map { it.text })
            }
        }
        StorageErrorNotice(state.storageError)
        state.editorNotice?.let { notice ->
            NoticeCard(notice, C.Mid, C.MidSoft) { state.editorNotice = null }
        }
        ValidationBanner(state, cmd, issues)
        MissingPermissionNotice(cmd) { onAuthorization(SettingsPage.Permissions) }
        val sections = if (cmd.auto) CommandSection.entries else listOf(CommandSection.ACTIONS)
        sections.forEachIndexed { i, section ->
            Column(Modifier.fillMaxWidth().testTag("section-${section.name}")
                .staggerIn(i).clip(com.kyant.shapes.RoundedRectangle(28.dp)).background(C.Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    T(section.label, 20.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    T("${if (section == CommandSection.ACTIONS) cmd.actions.size else cmd.items(section).size}", 13.sp, color = C.Muted)
                    Spacer(Modifier.width(10.dp))
                    EditorPill("添加", "添加${section.label}") { panel = EditorPanel.Add(section) }
                }
                if (cmd.items(section).isEmpty()) T("尚未添加${section.label}", 14.sp, color = C.Muted)
                if (section == CommandSection.ACTIONS) {
                    ActionList(
                        state = state, cmd = cmd, items = cmd.actions, depth = 0,
                        parentId = null, branch = null, onPanel = { panel = it },
                        cards = true, onOpenBlock = { state.openBlock(it) },
                    )
                } else {
                    ConfigItemList(state, cmd, section, cmd.items(section))
                }
                if (section == CommandSection.CONDITIONS) {
                    T("多个条件需要全部满足才会执行。", 11.sp, color = C.Muted, lineHeight = 16.sp)
                }
            }
        }
        if (cmd.auto) {
            Column(
                Modifier.fillMaxWidth().testTag("section-advanced")
                    .staggerIn(sections.size)
                    .clip(com.kyant.shapes.RoundedRectangle(28.dp)).background(C.Card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T("进阶选项", 20.sp, FontWeight.Bold)
                TriggerPolicyControls(state, cmd)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    } else {
        BlockPage(state, cmd, currentBlock) { panel = it }
    }
    if (confirmDelete) {
        DeleteCommandDialog(
            title = cmd.title,
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                state.delete(cmd.id)
            },
        )
    }
    panel?.let { active ->
        EditorGlassPanel(backdrop, when (active) {
            is EditorPanel.Add -> "添加${active.section.label}"
            is EditorPanel.Move -> "移动到…"
            EditorPanel.Description -> "编辑描述"
            EditorPanel.Icons -> "选择图标"
            EditorPanel.Variables -> "变量"
        },
            bottom = active is EditorPanel.Add || active is EditorPanel.Move, onClose = { panel = null }) {
            when (active) {
                EditorPanel.Icons -> CommandIcons.chunked(4).forEach { icons ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        icons.forEach { icon ->
                            Box(Modifier.weight(1f).height(64.dp).clip(PolygonShape(M3EShapes.Clover4))
                                .background(if (icon == cmd.ic) C.PrimarySoft else C.Card)
                                .semantics { selected = icon == cmd.ic }
                                .editorAction("图标：${icon.editorLabel}") { state.setIcon(cmd.id, icon); panel = null },
                                contentAlignment = Alignment.Center) { LineIcon(icon, C.Primary, 26.dp) }
                        }
                        repeat(4 - icons.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                is EditorPanel.Add -> {
                    ComponentPicker(
                        section = active.section,
                        onLocked = { required ->
                            panel = null
                            val page = when {
                                ExecutionLayer.XPOSED in required -> SettingsPage.LSPosed
                                ExecutionLayer.ROOT in required -> SettingsPage.Root
                                else -> SettingsPage.Layers
                            }
                            onAuthorization(page)
                        },
                    ) { option ->
                        panel = null
                        val meta = option.typeId?.let {
                            com.flowglass.engine.BuiltInComponents.registry().find(it)
                        }
                        if (meta != null &&
                            componentAvailability(meta, LayerStatus.current()) is ComponentAvailability.AvailableWithHint
                        ) {
                            state.editorNotice =
                                "系统框架前台事件未就绪：请到设置 → LSPosed 激活页打开「系统框架前台事件」并重启手机。"
                        }
                        val block = meta?.block
                        when {
                            // Blocks keep their parameters on the block page; add and jump there.
                            block == com.flowglass.engine.BlockKind.LOOP ||
                                block == com.flowglass.engine.BlockKind.WAIT -> {
                                val id = state.addComponentDirect(
                                    cmd.id, active.section, option, active.parentId, active.branch,
                                )
                                state.openBlock(id)
                            }
                            // Parameterless components (if, break, toastless actions, ...) are
                            // complete as soon as they are picked.
                            meta == null || meta.params.isEmpty() -> {
                                state.addComponentDirect(
                                    cmd.id, active.section, option, active.parentId, active.branch,
                                )
                            }
                            else -> state.startComponentDraft(
                                cmd.id, active.section, option, active.parentId, active.branch,
                            )
                        }
                    }
                }
                is EditorPanel.Move -> MoveToPanel(state, cmd, active.sourceId) { panel = null }
                EditorPanel.Description -> DescriptionPanel(state, cmd) { panel = null }
                EditorPanel.Variables -> VariablePanel(state, cmd)
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockPage(
    state: AppState,
    cmd: Cmd,
    block: CommandItem,
    onPanel: (EditorPanel) -> Unit,
) {
    val meta = block.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it) }
    val kind = meta?.block
    var outputPage by remember(block.id) { mutableStateOf(false) }
    if (outputPage && meta != null) {
        ComponentOutputPage(
            item = block,
            section = CommandSection.ACTIONS,
            cmdTitle = cmd.title,
            onBack = { outputPage = false },
            onSave = { outputs ->
                state.updateBlockOutputs(cmd.id, block.id, outputs)
                outputPage = false
            },
            validateName = { name ->
                VariableCatalog.validateName(
                    state, name, cmd.id, com.flowglass.engine.VariableScope.OUTPUT,
                    ignoreItem = block.id,
                )
            },
        )
        return
    }
    val crumbs = buildList {
        add(cmd.title)
        state.blockPath.forEach { id -> cmd.actions.findActionNode(id)?.let { add(it.name) } }
    }
    Column(
        Modifier.fillMaxSize().background(C.Bg).testTag("block-page")
            .statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetailTopBar(
            onBack = { state.backBlock() },
            title = { T(block.name, 20.sp, FontWeight.Bold, modifier = Modifier.fillMaxWidth(), maxLines = 1) },
            trailing = {
                TopBarIconButton(Ic.UpDown, "移动到其他位置") { onPanel(EditorPanel.Move(block.id)) }
                if (meta != null) TopBarIconButton(Ic.Output, "配置输出变量") { outputPage = true }
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            crumbs.forEachIndexed { index, label ->
                Box(
                    Modifier.clip(Capsule())
                        .background(if (index == crumbs.lastIndex) C.PrimarySoft else C.Card)
                        .pressable {
                            if (index == 0) state.gotoBlock(-1) else state.gotoBlock(index - 1)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    T(label, 12.sp, FontWeight.SemiBold, if (index == crumbs.lastIndex) C.Primary else C.OnSurfaceVar, maxLines = 1)
                }
                if (index < crumbs.lastIndex) T("›", 14.sp, color = C.Muted)
            }
        }
        ValidationBanner(
            state, cmd,
            state.validationItems(cmd).filter { issue ->
                issue.ownerId?.let { owner -> state.blockPathTo(owner)?.contains(block.id) == true } == true
            },
        )
        when (kind) {
            com.flowglass.engine.BlockKind.IF -> IfBlockSections(state, cmd, block, onPanel)
            com.flowglass.engine.BlockKind.LOOP -> LoopBlockSections(state, cmd, block, onPanel)
            com.flowglass.engine.BlockKind.WAIT -> WaitBlockSections(state, cmd, block, onPanel)
            else -> Unit
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PageSection(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().testTag(tag).clip(RoundedRectangle(26.dp)).background(C.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        T(title, 18.sp, FontWeight.Bold)
        content()
    }
}

@Composable
private fun ConditionSection(
    state: AppState,
    cmd: Cmd,
    block: CommandItem,
    onPanel: (EditorPanel) -> Unit,
) {
    if (block.conditions.isEmpty()) T("还没有条件", 13.sp, color = C.Muted)
    block.conditions.forEach { condition ->
        ConditionLine(
            state = state, cmd = cmd, item = block, branch = null,
            condition = condition,
        )
    }
    EditorPill("＋ 添加条件", "添加条件") {
        onPanel(EditorPanel.Add(CommandSection.CONDITIONS, block.id))
    }
}

@Composable
private fun StepSection(
    state: AppState,
    cmd: Cmd,
    items: List<CommandItem>,
    parentId: Long,
    branch: Int?,
    addTag: String,
    addLabel: String,
    onPanel: (EditorPanel) -> Unit,
) {
    if (items.isEmpty()) T("还没有步骤", 13.sp, color = C.Muted)
    ActionList(
        state = state, cmd = cmd, items = items, depth = 0,
        parentId = parentId, branch = branch, onPanel = onPanel,
        cards = true, onOpenBlock = { state.openBlock(it) },
    )
    BlockAddButton(
        label = addLabel,
        tag = addTag,
        entries = listOf(AddMenuEntry("步骤") { onPanel(EditorPanel.Add(CommandSection.ACTIONS, parentId, branch)) }),
    )
}

@Composable
private fun IfBlockSections(
    state: AppState,
    cmd: Cmd,
    block: CommandItem,
    onPanel: (EditorPanel) -> Unit,
) {
    PageSection("如果", "block-page-section-if") {
        ConditionSection(state, cmd, block, onPanel)
    }
    PageSection("就", "block-page-section-then") {
        StepSection(
            state = state, cmd = cmd, items = block.children, parentId = block.id, branch = null,
            addTag = "block-page-add-${block.id}",
            addLabel = "向就添加步骤", onPanel = onPanel,
        )
    }
    val elseIndex = block.branches.indexOfFirst { it.conditions.isEmpty() }
    PageSection("否则", "block-page-section-else") {
        if (elseIndex < 0) {
            EditorPill("＋ 添加否则", "添加否则", Modifier.testTag("block-page-add-else-${block.id}")) {
                state.addBranch(cmd.id, block.id, withCondition = false)
            }
        } else {
            val branch = block.branches[elseIndex]
            StepSection(
                state = state, cmd = cmd, items = branch.children,
                parentId = block.id, branch = elseIndex,
                addTag = "block-page-add-else-step-${block.id}",
                addLabel = "向否则添加步骤", onPanel = onPanel,
            )
        }
    }
}

@Composable
private fun LoopBlockSections(
    state: AppState,
    cmd: Cmd,
    block: CommandItem,
    onPanel: (EditorPanel) -> Unit,
) {
    val mode = block.parameter.substringBefore('|').trim().ifBlank { "按次数" }
    val timesLoop = mode in listOf("按次数", "times")
    var timesText by remember(block.id) { mutableStateOf(block.parameter.substringAfter('|', "3")) }
    LaunchedEffect(block.parameter) {
        val incoming = block.parameter.substringAfter('|', "3")
        if (incoming != timesText && incoming.toIntOrNull() != timesText.toIntOrNull()) timesText = incoming
    }
    fun setMode(nextMode: String) {
        val times = timesText.toIntOrNull()?.coerceIn(1, 1000) ?: 3
        state.setItemParameter(cmd.id, CommandSection.ACTIONS, block.id, "$nextMode|$times")
    }
    PageSection("方式", "block-page-section-mode") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("按次数", timesLoop, "循环方式：按次数") { setMode("按次数") }
            ModeChip("条件循环", !timesLoop, "循环方式：条件循环") { setMode("条件成立时一直循环") }
        }
        if (timesLoop) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("次数", 14.sp, color = C.OnSurfaceVar)
                BasicTextField(
                    timesText,
                    { raw ->
                        timesText = raw.filter(Char::isDigit).take(4)
                        val times = timesText.toIntOrNull()
                        if (times != null && times in 1..1000) {
                            state.setItemParameter(cmd.id, CommandSection.ACTIONS, block.id, "按次数|$times")
                        }
                    },
                    Modifier.width(84.dp).testTag("loop-times-field").imeAutoScroll()
                        .clip(RoundedRectangle(12.dp)).background(C.Tonal).padding(horizontal = 10.dp, vertical = 8.dp),
                    textStyle = TextStyle(color = C.OnSurface, fontSize = 15.sp),
                    singleLine = true,
                    cursorBrush = SolidColor(C.Primary),
                )
                T("1–1000 次", 12.sp, color = C.Muted)
            }
        } else {
            ConditionSection(state, cmd, block, onPanel)
        }
    }
    PageSection("循环体", "block-page-section-body") {
        StepSection(
            state = state, cmd = cmd, items = block.children, parentId = block.id, branch = null,
            addTag = "block-page-add-${block.id}",
            addLabel = "向循环体添加步骤", onPanel = onPanel,
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(Capsule()).background(if (selected) C.PrimarySoft else C.Tonal)
            .editorAction(description, onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { T(label, 13.sp, FontWeight.SemiBold, if (selected) C.Primary else C.OnSurfaceVar, maxLines = 1) }
}

@Composable
private fun WaitBlockSections(
    state: AppState,
    cmd: Cmd,
    block: CommandItem,
    onPanel: (EditorPanel) -> Unit,
) {
    var timeoutText by remember(block.id) { mutableStateOf(waitTimeoutSeconds(block.parameter).toString()) }
    LaunchedEffect(block.parameter) {
        val incoming = waitTimeoutSeconds(block.parameter).toString()
        if (incoming != timeoutText) timeoutText = incoming
    }
    PageSection("等待条件", "block-page-section-wait-condition") {
        ConditionSection(state, cmd, block, onPanel)
    }
    PageSection("超时", "block-page-section-timeout") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T("最长等待", 14.sp, color = C.OnSurfaceVar)
            BasicTextField(
                timeoutText,
                { raw ->
                    timeoutText = raw.filter(Char::isDigit).take(4)
                    val seconds = timeoutText.toIntOrNull()
                    if (seconds != null && seconds in 1..3600) {
                        val poll = block.parameter.substringAfter('|', "1 秒").ifBlank { "1 秒" }
                        state.setItemParameter(cmd.id, CommandSection.ACTIONS, block.id, "$seconds 秒|$poll")
                    }
                },
                Modifier.width(84.dp).testTag("wait-timeout-field").imeAutoScroll()
                    .clip(RoundedRectangle(12.dp)).background(C.Tonal).padding(horizontal = 10.dp, vertical = 8.dp),
                textStyle = TextStyle(color = C.OnSurface, fontSize = 15.sp),
                singleLine = true,
                cursorBrush = SolidColor(C.Primary),
            )
            T("秒（1–3600）", 12.sp, color = C.Muted)
        }
    }
}

private fun waitTimeoutSeconds(parameter: String): Int {
    val head = parameter.substringBefore('|').trim()
    val match = Regex("""([0-9]+)\s*(毫秒|秒|分钟)""").matchEntire(head) ?: return 30
    val value = match.groupValues[1].toIntOrNull() ?: return 30
    return when (match.groupValues[2]) {
        "分钟" -> value * 60
        "毫秒" -> (value / 1000).coerceAtLeast(1)
        else -> value
    }.coerceIn(1, 3600)
}

@Composable
private fun ConditionDeleteButton(
    state: AppState,
    cmd: Cmd,
    item: CommandItem,
    condition: CommandItem,
    branch: Int?,
    compact: Boolean = false,
) {
    DeleteItemButton(
        label = "删除条件${condition.name}",
        testTag = "delete-item-${condition.id}",
        size = if (compact) 36.dp else 44.dp,
    ) {
        state.removeConditionItemWithUndo(cmd.id, item.id, branch, condition.id)
    }
}

@Composable
private fun ConditionLine(
    state: AppState,
    cmd: Cmd,
    item: CommandItem,
    branch: Int?,
    condition: CommandItem,
    compact: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedRectangle(16.dp)).background(C.Tonal)
            .editorAction("编辑条件${condition.name}") {
                state.openComponentEditor(cmd.id, CommandSection.CONDITIONS, condition, item.id, branch)
            }
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(ActionSummary.conditionRowText(condition), 12.sp, color = C.OnSurfaceVar,
            maxLines = 1, modifier = Modifier.weight(1f))
        ConditionDeleteButton(state, cmd, item, condition, branch, compact)
    }
}

internal sealed interface EditorPanel {
    data object Icons : EditorPanel
    data class Add(val section: CommandSection, val parentId: Long? = null, val branch: Int? = null) : EditorPanel
    data class Move(val sourceId: Long) : EditorPanel
    data object Description : EditorPanel
    data object Variables : EditorPanel
}

@Composable
internal fun StorageErrorNotice(error: String?) {
    if (error != null) T(error, 14.sp, color = C.Bad,
        modifier = Modifier.fillMaxWidth().clip(RoundedRectangle(24.dp)).background(C.Card).padding(16.dp))
}

internal val CommandSection.label: String
    get() = when (this) {
        CommandSection.TRIGGERS -> "触发器"
        CommandSection.CONDITIONS -> "条件"
        CommandSection.ACTIONS -> "任务"
    }

// Deliberate whitelist: UI affordances must not become new command identities.
private val CommandIcons = listOf(
    Ic.Home, Ic.Bolt, Ic.Play, Ic.Bell, Ic.Shield, Ic.Wifi, Ic.Moon, Ic.Battery,
    Ic.Timer, Ic.Location, Ic.Leaf, Ic.Gauge, Ic.Rocket, Ic.Clock, Ic.Phone,
    Ic.Bluetooth, Ic.Message, Ic.Camera, Ic.Tap, Ic.Lock, Ic.Terminal, Ic.Puzzle, Ic.Key,
)

private val Ic.editorLabel: String
    get() = when (this) {
        Ic.Home -> "主页"; Ic.Grid -> "网格"; Ic.List -> "列表"; Ic.Settings -> "设置"
        Ic.Bolt -> "闪电"; Ic.Copy -> "复制"; Ic.ChevronRight -> "向右"; Ic.UpDown -> "排序"; Ic.More -> "更多"
        Ic.Variable -> "变量"; Ic.Output -> "输出"
        Ic.ArrowUp -> "上箭头"; Ic.ArrowDown -> "下箭头"; Ic.Play -> "播放"; Ic.Stop -> "停止"
        Ic.Plus -> "添加"; Ic.Bell -> "通知"; Ic.Refresh -> "刷新"; Ic.Shield -> "盾牌"
        Ic.Wifi -> "无线网络"; Ic.Moon -> "月亮"; Ic.Battery -> "电池"; Ic.Timer -> "计时器"
        Ic.Location -> "位置"; Ic.Leaf -> "叶子"; Ic.Gauge -> "仪表"; Ic.Rocket -> "火箭"
        Ic.Clock -> "时钟"; Ic.Phone -> "手机"; Ic.Bluetooth -> "蓝牙"; Ic.Message -> "消息"
        Ic.Camera -> "相机"; Ic.Info -> "信息"; Ic.Search -> "搜索"; Ic.Accessibility -> "无障碍"
        Ic.Tap -> "轻点"; Ic.Back -> "返回"; Ic.Lock -> "锁"; Ic.Terminal -> "终端"
        Ic.Puzzle -> "拼图"; Ic.Key -> "钥匙"; Ic.Pin -> "置顶"; Ic.Edit -> "编辑"
        Ic.Share -> "分享"; Ic.Trash -> "删除"
    }

/** Original press spring, with equivalent keyboard/accessibility activation. */
internal fun Modifier.editorAction(label: String, action: () -> Unit): Modifier =
    semantics { role = Role.Button; contentDescription = label; onClick { action(); true } }
        .pressable(onClick = action)

@Composable
private fun ValidationBanner(state: AppState, cmd: Cmd, issues: List<ValidationIssue>) {
    if (issues.isEmpty()) return
    var expanded by remember(cmd.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().testTag("validation-banner")
            .clip(RoundedRectangle(18.dp)).background(C.BadSoft),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .testTag("validation-banner-toggle")
                .semantics { contentDescription = if (expanded) "收起问题" else "展开问题" }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T("${issues.size} 个问题", 13.sp, FontWeight.SemiBold, C.Bad, modifier = Modifier.weight(1f))
            T(if (expanded) "⌃" else "›", 18.sp, FontWeight.Bold, C.Bad, maxLines = 1)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 8.dp)) {
                issues.forEach { issue ->
                    val ownerId = issue.ownerId
                    Row(
                        Modifier.fillMaxWidth()
                            .then(
                                if (ownerId != null) Modifier
                                    .testTag("validation-issue-${ownerId}")
                                    .editorAction("打开问题") { state.openBlockAt(ownerId) }
                                else Modifier
                            )
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T("•", 12.sp, color = C.Bad)
                        Spacer(Modifier.width(6.dp))
                        T(issue.text, 12.sp, color = C.Bad, lineHeight = 17.sp, maxLines = 3,
                            modifier = Modifier.weight(1f))
                        if (ownerId != null) T("›", 16.sp, color = C.Bad)
                    }
                }
            }
        }
    }
}

@Composable
private fun DescriptionPanel(state: AppState, cmd: Cmd, onDone: () -> Unit) {
    var text by remember(cmd.id) { mutableStateOf(if (cmd.descriptionEdited) cmd.desc else "") }
    T("只显示你手写的描述；列表页仍保留自动摘要。", 12.sp, color = C.Muted, lineHeight = 17.sp)
    BasicTextField(
        text, { text = it.take(500) },
        Modifier.fillMaxWidth().testTag("description-editor").imeAutoScroll()
            .clip(RoundedRectangle(14.dp)).background(C.Tonal).padding(12.dp),
        textStyle = TextStyle(color = C.OnSurface, fontSize = 14.sp, lineHeight = 20.sp),
        maxLines = 2,
        cursorBrush = SolidColor(C.Primary),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorPill("清空", "清空描述") {
            state.setDescription(cmd.id, "")
            onDone()
        }
        EditorPill("完成", "保存描述") {
            state.setDescription(cmd.id, text.trim())
            onDone()
        }
    }
}

@Composable
private fun DeleteItemButton(
    label: String,
    testTag: String,
    size: Dp = 48.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).testTag(testTag)
            .clip(Capsule()).background(C.BadSoft)
            .editorAction(label, onClick),
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(Ic.Trash, C.Bad, if (size < 48.dp) 20.dp else 22.dp)
    }
}

@Composable
private fun ConfigItemRow(
    state: AppState,
    cmd: Cmd,
    section: CommandSection,
    item: CommandItem,
    onMeasuredHeight: (Int) -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val dragStart = rememberUpdatedState(onDragStart)
    val dragMove = rememberUpdatedState(onDrag)
    val dragEnd = rememberUpdatedState(onDragEnd)
    val dragCancel = rememberUpdatedState(onDragCancel)
    val itemMeta = item.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it) }
    val deleteLabel = when (section) {
        CommandSection.TRIGGERS -> "删除触发器${item.name}"
        CommandSection.CONDITIONS -> "删除条件${item.name}"
        CommandSection.ACTIONS -> "删除动作${item.name}"
    }
    Column(
        Modifier.fillMaxWidth().testTag("item-${item.id}")
            .onGloballyPositioned { onMeasuredHeight(it.size.height) }
            .clip(RoundedRectangle(24.dp)).background(C.Tonal)
            .pointerInput(item.id) {
                detectTapGestures(onTap = {
                    state.openComponentEditor(cmd.id, section, item)
                })
            }
            .pointerInput(item.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragStart.value() },
                    onDragEnd = { dragEnd.value() },
                    onDragCancel = { dragCancel.value() },
                    onDrag = { change, amount -> change.consume(); dragMove.value(amount.y) },
                )
            }
            .semantics {
                contentDescription = "编辑${item.name}"
                onClick(label = "编辑") {
                    state.openComponentEditor(cmd.id, section, item)
                    true
                }
            }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineIcon(item.ic, C.Primary, 22.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).testTag("item-text-${item.id}")) {
                T(item.name, 15.sp, FontWeight.SemiBold, maxLines = 1)
                T(itemParameterLabel(section, item), 13.sp, color = C.OnSurfaceVar, maxLines = 1)
            }
            DeleteItemButton(
                label = deleteLabel,
                testTag = "delete-item-${item.id}",
            ) {
                state.removeItemWithUndo(cmd.id, section, item.id)
            }
        }
        if (itemMeta?.comingSoon == true) {
            T("即将支持，暂不会自动运行", 11.sp, color = C.Mid)
        }
    }
}

/** Trigger/condition list with the same long-press reorder as action siblings. */
@Composable
private fun ConfigItemList(
    state: AppState,
    cmd: Cmd,
    section: CommandSection,
    items: List<CommandItem>,
) {
    var preview by remember(items) { mutableStateOf<List<Long>?>(null) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var startIndex by remember { mutableIntStateOf(0) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val offset = remember { Animatable(0f) }
    val heights = remember { mutableMapOf<Long, Int>() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    val shadow = with(density) { 18.dp.toPx() }
    val shown = preview?.let { ids -> ids.mapNotNull { id -> items.firstOrNull { it.id == id } } } ?: items

    fun movePreview(id: Long, direction: Int) {
        val order = (preview ?: items.map { it.id }).toMutableList()
        val from = order.indexOf(id)
        val to = from + direction
        if (from >= 0 && to in order.indices) {
            order[from] = order[to]
            order[to] = id
            preview = order
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { item ->
            key(item.id) {
                val isDragging = draggingId == item.id
                Box(
                    Modifier.fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = offset.value
                                scaleX = 1.02f
                                scaleY = 1.02f
                                shadowElevation = shadow
                            }
                        }
                        .then(if (isDragging) Modifier else Modifier.animatePlacement()),
                ) {
                    ConfigItemRow(
                        state = state, cmd = cmd, section = section, item = item,
                        onMeasuredHeight = { heights[item.id] = it },
                        onDragStart = {
                            if (draggingId == null) {
                                draggingId = item.id
                                startIndex = items.indexOfFirst { it.id == item.id }
                                preview = items.map { it.id }
                                dragY = 0f
                                scope.launch { offset.snapTo(0f) }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { dy ->
                            if (draggingId == item.id) {
                                dragY += dy
                                val step = (heights[item.id]?.toFloat() ?: 72f) + gap
                                while (dragY > step / 2f && (preview?.indexOf(item.id) ?: 0) < shown.lastIndex) {
                                    movePreview(item.id, 1)
                                    dragY -= step
                                }
                                while (dragY < -step / 2f && (preview?.indexOf(item.id) ?: 0) > 0) {
                                    movePreview(item.id, -1)
                                    dragY += step
                                }
                                scope.launch { offset.snapTo(dragY) }
                            }
                        },
                        onDragEnd = {
                            if (draggingId == item.id) {
                                val target = preview?.indexOf(item.id) ?: startIndex
                                if (target != startIndex) {
                                    state.moveItemTo(cmd.id, section, item.id, target)
                                }
                                preview = null
                                dragY = 0f
                                scope.launch {
                                    offset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                                    if (draggingId == item.id) draggingId = null
                                }
                            }
                        },
                        onDragCancel = {
                            preview = null
                            dragY = 0f
                            scope.launch {
                                offset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                                if (draggingId == item.id) draggingId = null
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String, color: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color, onDismiss: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedRectangle(18.dp)).background(background).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        T(text, 12.sp, color = color, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        if (onDismiss != null) EditorIconButton(Ic.Trash, "关闭提示") { onDismiss() }
    }
}

@Composable
private fun VariablePanel(state: AppState, cmd: Cmd) {
    val locals = state.localsOf(cmd.id)
    T("指令变量", 15.sp, FontWeight.SemiBold)
    T("只在这条指令里有效，运行结束后写回初始值。", 12.sp, color = C.Muted)
    locals.forEach { variable ->
        VariableRow(
            name = variable.name,
            value = variable.initial.toString().trim('"'),
            onName = {},
            onValue = { state.setLocal(cmd.id, variable.name, it, variable.type) },
            onDelete = { state.removeLocal(cmd.id, variable.name) },
        )
    }
    EditorPill("＋指令变量", "添加指令变量") {
        state.setLocal(cmd.id, "var${locals.size + 1}", "")
    }
    Spacer(Modifier.height(6.dp))
    T("全局变量", 15.sp, FontWeight.SemiBold)
    T("所有指令共用；运行成功或失败都会写回，手动停止不写。", 12.sp, color = C.Muted)
    state.engineGlobals.toSortedMap().forEach { (name, json) ->
        VariableRow(
            name = name,
            value = json.toString().trim('"'),
            onName = {},
            onValue = { state.setGlobal(name, it) },
            onDelete = { state.removeGlobal(name) },
        )
    }
    EditorPill("＋全局变量", "添加全局变量") { state.setGlobal("g${state.engineGlobals.size + 1}", "") }
    Spacer(Modifier.height(6.dp))
    T("在参数里用 %{rule.变量名} 或 %{global.变量名} 引用；运行期临时变量用 %{run.变量名}。",
        12.sp, color = C.Muted, lineHeight = 17.sp)
}

@Composable
private fun VariableRow(name: String, value: String, onName: (String) -> Unit, onValue: (String) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T(name, 13.sp, color = C.OnSurfaceVar, modifier = Modifier.weight(0.4f))
        BasicTextField(value, onValue, Modifier.weight(0.6f).imeAutoScroll().clip(RoundedRectangle(12.dp)).background(C.Card).padding(8.dp),
            textStyle = TextStyle(color = C.OnSurface, fontSize = 13.sp), singleLine = true,
            cursorBrush = SolidColor(C.Primary),
            decorationBox = { inner -> Box { if (value.isEmpty()) T("值", 13.sp, color = C.Muted); inner() } })
        EditorIconButton(Ic.Trash, "删除变量$name") { onDelete() }
    }
}

/** The "..." menu on every step: order changes plus in/out of the enclosing block. */
@Composable
private fun EditorRunButton(
    exec: Int,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    onClick: () -> Unit,
    onStop: (() -> Unit)?,
) {
    val running = exec == 1 && onStop != null
    val label = when {
        running -> "停止"
        exec == 2 -> "完成"
        exec == 3 -> "失败"
        else -> "运行"
    }
    val bg by animateColorAsState(
        when {
            running -> C.Bad
            exec == 2 -> C.GoodSoft
            exec == 3 -> C.Bad
            else -> C.Primary
        }, effect(), label = "headerRunBg",
    )
    val fg = if (exec == 2) C.GoodText else C.OnPrimary
    Box(
        modifier.height(TopBarControlTouch)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label = label) {
                    if (running) onStop?.invoke() else onClick()
                    true
                }
            }
            .pressable(0.9f) { if (running) onStop?.invoke() else onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(height).clip(Capsule()).background(bg)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineIcon(
                    if (running) Ic.Stop else if (exec == 3) Ic.Info else if (exec == 2) Ic.Refresh else Ic.Play,
                    fg, 16.dp,
                    modifier = if (!running && exec == 0) Modifier.testTag("editor-execute-play") else Modifier,
                )
                Spacer(Modifier.width(4.dp))
                T(label, 13.sp, FontWeight.SemiBold, fg, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AutoEnableToggle(state: AppState, cmd: Cmd, context: android.content.Context, issues: List<String>) {
    val toggleBackdrop = rememberLayerBackdrop()
    Box(Modifier.clip(Capsule()).heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().layerBackdrop(toggleBackdrop).background(C.Card))
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T(if (cmd.enabled) "已开启" else "已关闭", 12.sp, color = C.OnSurfaceVar)
            Spacer(Modifier.width(6.dp))
            LiquidToggle({ cmd.enabled }, {
                if (!cmd.enabled && issues.isNotEmpty()) state.editorNotice = issues.first()
                else PermissionGate.request(state, cmd, context)
            }, toggleBackdrop,
                Modifier.testTag("editor-toggle").semantics {
                    contentDescription = "启用指令"
                    toggleableState = if (cmd.enabled) ToggleableState.On else ToggleableState.Off
                    onClick { PermissionGate.request(state, cmd, context); true }
                })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeleteCommandDialog(title: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(C.ScrimSubtle)
                .pointerInput(Unit) { detectTapGestures { onCancel() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(320.dp).clip(RoundedRectangle(24.dp)).background(C.Card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T("删除指令？", 18.sp, FontWeight.Bold)
                T("「$title」删除后可在提示条撤销。", 14.sp, color = C.OnSurfaceVar, lineHeight = 20.sp)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.width(132.dp).heightIn(min = 44.dp).clip(Capsule()).background(C.Tonal)
                            .editorAction("取消删除") { onCancel() }.padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) { T("取消", 14.sp, FontWeight.SemiBold, maxLines = 1) }
                    Box(
                        Modifier.width(132.dp).heightIn(min = 44.dp).clip(Capsule()).background(C.Bad)
                            .editorAction("确认删除") { onConfirm() }.padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) { T("删除", 14.sp, FontWeight.SemiBold, C.OnBad, maxLines = 1) }
                }
            }
        }
    }
}

private fun shareCommand(context: Context, cmd: Cmd) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, commandShareText(cmd))
    runCatching {
        context.startActivity(Intent.createChooser(send, "分享指令").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ActionDeleteButton(
    state: AppState,
    cmd: Cmd,
    item: CommandItem,
    compact: Boolean = false,
) {
    DeleteItemButton(
        label = "删除动作${item.name}",
        testTag = "delete-item-${item.id}",
        size = if (compact) 40.dp else 48.dp,
    ) {
        state.removeActionItemWithUndo(cmd.id, item.id)
    }
}

/** Shared by the block add menu and the component picker; returns the new condition id. */
internal fun addConditionFromBlockMenu(
    state: AppState,
    cmdId: Long,
    parentId: Long?,
    branch: Int?,
    option: CommandOption,
): Long? {
    val command = state.find(cmdId) ?: return null
    if (parentId == null) {
        state.addItem(cmdId, CommandSection.CONDITIONS, option)
        return state.find(cmdId)?.conditions?.lastOrNull()?.id
    }
    state.addConditionItem(cmdId, parentId, branch, option)
    val parent = command.actions.findItem(parentId) ?: return null
    return when {
        branch == null -> parent.conditions.lastOrNull()
        else -> parent.branches.getOrNull(branch)?.conditions?.lastOrNull()
    }?.id
}

/** One compact add button at the end of a block or branch. The menu is the only place where
 * a block's allowed additions are listed, so narrow screens never have to fit four pills. */
@Composable
private fun BlockAddButton(
    label: String,
    tag: String,
    entries: List<AddMenuEntry>,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val anchor = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(modifier) {
        EditorPill(
            "＋ 添加", label,
            Modifier.testTag(tag).trackMenuAnchor(anchor, open),
        ) {
            if (entries.size == 1) entries.single().action() else if (entries.size > 1) open = true
        }
        if (open && entries.size > 1) {
            AnchoredMenu(
                anchor = anchor.value,
                onDismiss = { open = false },
                items = entries.map { entry ->
                    MenuItemSpec(entry.label) {
                        open = false
                        entry.action()
                    }
                },
            )
        }
    }
}

@Composable
internal fun EditorIconButton(
    ic: Ic,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    action: () -> Unit,
) {
    Box(modifier.size(size).clip(Capsule()).background(C.Card).editorAction(label, action),
        contentAlignment = Alignment.Center) {
        LineIcon(ic, C.OnSurface, if (size < 48.dp) 20.dp else 22.dp)
    }
}

private const val MaxIndentLevels = 3
private val IndentStep = 10.dp

private fun childIndent(depth: Int) = if (depth < MaxIndentLevels) IndentStep else 0.dp

/**
 * Siblings of one action list. Dragging only edits a local id order; AppState is touched once on
 * release so undo records a single move and nothing is persisted on every drag delta.
 */
@Composable
private fun ActionList(
    state: AppState,
    cmd: Cmd,
    items: List<CommandItem>,
    depth: Int,
    parentId: Long?,
    branch: Int?,
    onPanel: (EditorPanel) -> Unit,
    cards: Boolean = false,
    onOpenBlock: (Long) -> Unit = {},
) {
    var preview by remember(items) { mutableStateOf<List<Long>?>(null) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var startIndex by remember { mutableIntStateOf(0) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val offset = remember { Animatable(0f) }
    val heights = remember { mutableMapOf<Long, Int>() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    val shadow = with(density) { 18.dp.toPx() }
    val shown = preview?.let { ids -> ids.mapNotNull { id -> items.firstOrNull { it.id == id } } } ?: items

    fun movePreview(id: Long, direction: Int) {
        val order = (preview ?: items.map { it.id }).toMutableList()
        val from = order.indexOf(id)
        val to = from + direction
        if (from >= 0 && to in order.indices) {
            order[from] = order[to]
            order[to] = id
            preview = order
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEachIndexed { index, item ->
            key(item.id) {
                val isDragging = draggingId == item.id
                Box(
                    Modifier.fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = offset.value
                                scaleX = 1.02f
                                scaleY = 1.02f
                                shadowElevation = shadow
                            }
                        }
                        .then(if (isDragging) Modifier else Modifier.animatePlacement()),
                ) {
                    ActionTreeRow(
                        state = state, cmd = cmd, item = item, depth = depth,
                        index = index, lastIndex = shown.lastIndex,
                        parentId = parentId, branch = branch,
                        onMeasuredHeight = { heights[item.id] = it },
                        onDragStart = {
                            if (draggingId == null) {
                                draggingId = item.id
                                startIndex = items.indexOfFirst { it.id == item.id }
                                preview = items.map { it.id }
                                dragY = 0f
                                scope.launch { offset.snapTo(0f) }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { dy ->
                            if (draggingId == item.id) {
                                dragY += dy
                                val step = (heights[item.id]?.toFloat() ?: 72f) + gap
                                while (dragY > step / 2f && (preview?.indexOf(item.id) ?: 0) < shown.lastIndex) {
                                    movePreview(item.id, 1)
                                    dragY -= step
                                }
                                while (dragY < -step / 2f && (preview?.indexOf(item.id) ?: 0) > 0) {
                                    movePreview(item.id, -1)
                                    dragY += step
                                }
                                scope.launch { offset.snapTo(dragY) }
                            }
                        },
                        onDragEnd = {
                            if (draggingId == item.id) {
                                val target = preview?.indexOf(item.id) ?: startIndex
                                if (target != startIndex) {
                                    state.moveStepTo(cmd.id, parentId, branch, item.id, target)
                                }
                                preview = null
                                dragY = 0f
                                scope.launch {
                                    offset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                                    if (draggingId == item.id) draggingId = null
                                }
                            }
                        },
                        onDragCancel = {
                            preview = null
                            dragY = 0f
                            scope.launch {
                                offset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                                if (draggingId == item.id) draggingId = null
                            }
                        },
                        onPanel = onPanel,
                        cards = cards,
                        onOpenBlock = onOpenBlock,
                    )
                }
            }
        }
    }
}

/** A block is a card on its parent page; its children live on a separate page. */
@Composable
private fun BlockCardRow(
    state: AppState,
    cmd: Cmd,
    item: CommandItem,
    index: Int,
    lastIndex: Int,
    parentId: Long?,
    branch: Int?,
    compact: Boolean,
    onMeasuredHeight: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onPanel: (EditorPanel) -> Unit,
    onOpenBlock: (Long) -> Unit,
) {
    val issueCount = state.blockValidationIssues(cmd, item.id).size
    val summary = ActionSummary.summarize(item, item.conditions.size + item.children.size, true)
    val preview = ActionSummary.blockPreview(item)
    val dragStart = rememberUpdatedState(onDragStart)
    val dragMove = rememberUpdatedState(onDrag)
    val dragEnd = rememberUpdatedState(onDragEnd)
    val dragCancel = rememberUpdatedState(onDragCancel)
    val rowPadding = if (compact) 10.dp else 14.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().onGloballyPositioned { onMeasuredHeight(it.size.height) }
                .testTag("block-card-${item.id}")
                .clip(RoundedRectangle(26.dp)).background(C.Tonal).padding(rowPadding)
                .pointerInput(item.id) {
                    detectTapGestures(onTap = { onOpenBlock(item.id) })
                }
                .pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragStart.value() },
                        onDragEnd = { dragEnd.value() },
                        onDragCancel = { dragCancel.value() },
                        onDrag = { change, amount -> change.consume(); dragMove.value(amount.y) },
                    )
                }
                .semantics {
                    contentDescription = "打开${item.name}块"
                    onClick(label = "打开块") { onOpenBlock(item.id); true }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineIcon(item.ic, C.Primary, if (compact) 20.dp else 24.dp)
            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            Column(Modifier.weight(1f).testTag("action-text-${item.id}"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(item.name, 16.sp, FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    T("块", 10.sp, FontWeight.SemiBold, C.Primary)
                }
                T(summary, 13.sp, color = C.OnSurfaceVar, maxLines = 2)
                T(preview, 12.sp, color = C.Muted, maxLines = 1)
                if (issueCount > 0) T("块内有 $issueCount 个问题", 11.sp, color = C.Bad, maxLines = 1)
            }
            Spacer(Modifier.width(4.dp))
            LineIcon(Ic.ChevronRight, C.Muted, 18.dp)
            Spacer(Modifier.width(2.dp))
            ActionDeleteButton(state, cmd, item, compact)
        }
    }
}

/**
 * One task step and everything nested in it.
 *
 * C1 keeps this deliberately plain: indent + move/delete + "add step/condition" buttons. C2 adds
 * folding, drag and the validation banner on top of the same tree.
 */
@Composable
private fun ActionTreeRow(
    state: AppState,
    cmd: Cmd,
    item: CommandItem,
    depth: Int,
    index: Int,
    lastIndex: Int,
    parentId: Long?,
    branch: Int?,
    onMeasuredHeight: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onPanel: (EditorPanel) -> Unit,
    cards: Boolean = false,
    onOpenBlock: (Long) -> Unit = {},
) {
    val meta = item.typeId?.let { com.flowglass.engine.BuiltInComponents.registry().find(it) }
    val block = meta?.block
    val container = block == com.flowglass.engine.BlockKind.IF ||
        block == com.flowglass.engine.BlockKind.LOOP ||
        block == com.flowglass.engine.BlockKind.WAIT
    val nested = item.conditions.size + item.children.size +
        item.branches.sumOf { it.children.size + it.conditions.size }
    val open = !item.collapsed
    val timesLoop = block == com.flowglass.engine.BlockKind.LOOP &&
        item.parameter.substringBefore('|') in listOf("按次数", "times")
    // pointerInput keeps its first lambda; read the latest callbacks so a recomposed sibling
    // order is visible during a long drag.
    val dragStart = rememberUpdatedState(onDragStart)
    val dragMove = rememberUpdatedState(onDrag)
    val dragEnd = rememberUpdatedState(onDragEnd)
    val dragCancel = rememberUpdatedState(onDragCancel)
    val hint = state.validationIssues(cmd).firstOrNull { it.contains("「${item.name}」") }
    val compact = LocalConfiguration.current.screenWidthDp < 300
    val rowPadding = if (compact) 8.dp else 12.dp
    if (cards && container) {
        BlockCardRow(
            state = state, cmd = cmd, item = item, index = index, lastIndex = lastIndex,
            parentId = parentId, branch = branch, compact = compact,
            onMeasuredHeight = onMeasuredHeight, onDragStart = onDragStart,
            onDrag = onDrag, onDragEnd = onDragEnd, onDragCancel = onDragCancel,
            onPanel = onPanel, onOpenBlock = onOpenBlock,
        )
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().onGloballyPositioned { onMeasuredHeight(it.size.height) }
                .clip(RoundedRectangle(24.dp)).background(C.Tonal).padding(rowPadding)
                // Tap opens the parameter page; the drag detector only starts after a 300 ms
                // hold, and a scroll gesture cancels the tap because the list consumes it.
                .pointerInput(item.id) {
                    detectTapGestures(onTap = {
                        state.openComponentEditor(cmd.id, CommandSection.ACTIONS, item, parentId, branch)
                    })
                }
                .pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragStart.value() },
                        onDragEnd = { dragEnd.value() },
                        onDragCancel = { dragCancel.value() },
                        onDrag = { change, amount -> change.consume(); dragMove.value(amount.y) },
                    )
                }
                .semantics {
                    contentDescription = "编辑${item.name}"
                    onClick(label = "编辑") {
                        state.openComponentEditor(cmd.id, CommandSection.ACTIONS, item, parentId, branch)
                        true
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (container && nested > 0) {
                Box(Modifier.size(if (compact) 32.dp else 40.dp).clip(Capsule()).background(C.Card)
                    .editorAction(if (open) "折叠${item.name}" else "展开${item.name}") {
                        state.toggleCollapsed(cmd.id, item.id)
                    }, contentAlignment = Alignment.Center) {
                    LineIcon(Ic.ChevronRight, C.OnSurface, if (compact) 18.dp else 20.dp,
                        modifier = Modifier.rotate(if (open) 90f else 0f))
                }
                Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
            }
            LineIcon(item.ic, C.Primary, if (compact) 18.dp else 22.dp)
            Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
            Column(
                Modifier.weight(1f).then(if (compact) Modifier else Modifier.widthIn(min = 120.dp))
                    .testTag("action-text-${item.id}"),
            ) {
                if (depth >= MaxIndentLevels) T("第 ${depth + 1} 级", 10.sp, color = C.Muted, maxLines = 1)
                T(item.name, 15.sp, FontWeight.SemiBold, maxLines = 1)
                val summary = ActionSummary.summarize(item, nested, open)
                T(summary, 13.sp, color = C.OnSurfaceVar, maxLines = 2)
                hint?.let { T(it, 11.sp, color = C.Bad, maxLines = 1) }
            }
            Spacer(Modifier.width(if (compact) 2.dp else 4.dp))
            ActionDeleteButton(state, cmd, item, compact)
        }
        if (open) {
            item.conditions.forEach { condition ->
                Row(Modifier.fillMaxWidth().padding(start = 10.dp).clip(RoundedRectangle(20.dp))
                    .background(C.Card)
                    .editorAction("编辑条件${condition.name}") {
                        state.openComponentEditor(cmd.id, CommandSection.CONDITIONS, condition, item.id, null)
                    }
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    T("条件", 11.sp, color = C.Muted)
                    Spacer(Modifier.width(8.dp))
                    T(ActionSummary.conditionRowText(condition),
                        12.sp, color = C.OnSurfaceVar, maxLines = 1, modifier = Modifier.weight(1f))
                    ConditionDeleteButton(state, cmd, item, condition, null, compact)
                }
            }
            if (timesLoop && item.conditions.isNotEmpty()) {
                T("按次数循环不会使用这些条件；需要条件循环请切换方式。", 11.sp, color = C.Mid,
                    modifier = Modifier.padding(start = 16.dp))
            }
            if (item.children.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().drawBehind {
                        val rails = if (depth >= MaxIndentLevels) 3 else 1
                        repeat(rails) { rail ->
                            val x = (1 + rail * 3).dp.toPx()
                            drawLine(C.Divider, androidx.compose.ui.geometry.Offset(x, 0f),
                                androidx.compose.ui.geometry.Offset(x, size.height), 2.dp.toPx())
                        }
                    }.padding(start = childIndent(depth)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionList(state, cmd, item.children, depth + 1, item.id, null, onPanel, cards, onOpenBlock)
                }
            }
            item.branches.forEachIndexed { branchIndex, branch ->
                if (branch.conditions.isNotEmpty()) return@forEachIndexed
                Column(Modifier.fillMaxWidth().padding(start = childIndent(depth)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("否则", 12.sp, color = C.Mid, modifier = Modifier.weight(1f))
                        EditorIconButton(Ic.Trash, "删除分支${branchIndex + 1}") {
                            state.removeBranch(cmd.id, item.id, branchIndex)
                        }
                    }
                    branch.conditions.forEach { condition ->
                        Row(Modifier.fillMaxWidth().clip(RoundedRectangle(16.dp)).background(C.Card)
                            .editorAction("编辑条件${condition.name}") {
                                state.openComponentEditor(cmd.id, CommandSection.CONDITIONS, condition, item.id, branchIndex)
                            }.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            T(ActionSummary.conditionRowText(condition), 12.sp, color = C.OnSurfaceVar,
                                maxLines = 1, modifier = Modifier.weight(1f))
                            ConditionDeleteButton(state, cmd, item, condition, branchIndex, compact)
                        }
                    }
                    ActionList(state, cmd, branch.children, depth + 1, item.id, branchIndex, onPanel, cards, onOpenBlock)
                    BlockAddButton(
                        label = "向该分支添加内容",
                        tag = "branch-add-${item.id}-$branchIndex",
                        entries = branchAddActions().map { add ->
                            AddMenuEntry(add.label) {
                                when (add.kind) {
                                    AddActionKind.STEP -> onPanel(EditorPanel.Add(CommandSection.ACTIONS, item.id, branchIndex))
                                    AddActionKind.CONDITION -> onPanel(EditorPanel.Add(CommandSection.CONDITIONS, item.id, branchIndex))
                                    AddActionKind.ELSE -> Unit
                                }
                            }
                        },
                    )
                }
            }
            when (block) {
                com.flowglass.engine.BlockKind.IF, com.flowglass.engine.BlockKind.LOOP, com.flowglass.engine.BlockKind.WAIT -> {
                    BlockAddButton(
                        label = "向${item.name}添加内容",
                        tag = "block-add-${item.id}",
                        entries = blockAddActions(block, timesLoop, item.branches).map { add ->
                            AddMenuEntry(add.label) {
                                when (add.kind) {
                                    AddActionKind.STEP -> onPanel(EditorPanel.Add(CommandSection.ACTIONS, item.id))
                                    AddActionKind.CONDITION -> onPanel(EditorPanel.Add(CommandSection.CONDITIONS, item.id))
                                    AddActionKind.ELSE -> state.addBranch(cmd.id, item.id, withCondition = false)
                                }
                            }
                        },
                    )
                }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerPolicyControls(state: AppState, cmd: Cmd) {
    val all = cmd.triggerMatch.mode == com.flowglass.engine.MatchMode.ALL
    T("触发器匹配", 12.sp, FontWeight.SemiBold, C.OnSurfaceVar)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PolicyChip("任一满足", !all, Modifier.weight(1f)) {
            state.setTriggerMatch(cmd.id, com.flowglass.engine.MatchMode.ANY)
        }
        PolicyChip("全部满足", all, Modifier.weight(1f)) {
            state.setTriggerMatch(cmd.id, com.flowglass.engine.MatchMode.ALL)
        }
    }
    if (all) {
        T("事件触发器的有效窗口", 11.sp, color = C.Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(30 to "30 秒", 60 to "60 秒", 300 to "5 分钟").forEach { (seconds, label) ->
                PolicyChip(label, cmd.triggerMatch.windowSeconds == seconds, Modifier.weight(1f)) {
                    state.setTriggerWindow(cmd.id, seconds)
                }
            }
        }
    }
    T("重复触发策略", 12.sp, FontWeight.SemiBold, C.OnSurfaceVar)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.flowglass.engine.RunMode.entries.forEach { mode ->
            PolicyChip(
                when (mode) {
                    com.flowglass.engine.RunMode.DROP -> "丢弃"
                    com.flowglass.engine.RunMode.QUEUE -> "排队"
                    com.flowglass.engine.RunMode.RESTART -> "重启"
                    com.flowglass.engine.RunMode.PARALLEL -> "并行"
                },
                cmd.runMode == mode,
                Modifier.width(78.dp),
            ) { state.setRunMode(cmd.id, mode) }
        }
    }
    T(
        if (all) "全部满足：状态型触发器按当前状态判断，事件型触发器需在窗口内都发生过。"
        else "任一满足：任一触发器命中即可执行。",
        11.sp, color = C.Muted, lineHeight = 16.sp,
    )
    T(
        when (cmd.runMode) {
            com.flowglass.engine.RunMode.DROP -> "正在执行时新的触发会被丢弃。"
            com.flowglass.engine.RunMode.QUEUE -> "最多排队 10 次，当前运行结束后依次执行。"
            com.flowglass.engine.RunMode.RESTART -> "新的触发会停止当前运行并重新开始。"
            com.flowglass.engine.RunMode.PARALLEL -> "最多同时运行 3 个实例，超出后丢弃。"
        },
        11.sp, color = C.Muted, lineHeight = 16.sp,
    )
}

@Composable
private fun PolicyChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.clip(Capsule()).background(if (selected) C.PrimarySoft else C.Tonal)
            .pressable { onClick() }.padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(label, 12.sp, FontWeight.SemiBold, if (selected) C.Primary else C.OnSurfaceVar)
    }
}
