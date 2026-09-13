package com.ailife.wftools.pc

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ailife.wftools.agent.model.*
import com.ailife.wftools.agent.provider.*
import com.ailife.wftools.agent.runtime.*
import com.ailife.wftools.agent.storage.*
import com.ailife.wftools.agent.tools.*
import com.ailife.wftools.agent.workspace.DesktopShellRunner
import com.ailife.wftools.agent.workspace.AgentWorkspace
import com.ailife.wftools.agent.workspace.BashApproval
import com.ailife.wftools.agent.workspace.BashApprovalStrategy
import com.ailife.wftools.agent.workspace.ShellCommandRequest
import com.ailife.wftools.agent.workspace.WorkspacePermissionSettings
import com.ailife.wftools.pc.platform.chooseAgentFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Toolkit
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Compact desktop workbench for the watch Agent. It deliberately uses a simple message stream,
 * rather than copying the mobile or IDE sidebar layouts. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopAgentPanel(app: EditorApplicationModel, doc: DocumentController, modifier: Modifier = Modifier, theme: ThemePreference = ThemePreference.SYSTEM, onTheme: (ThemePreference) -> Unit = {}) {
    val viewModel = remember(app, doc) { app.agentModel(doc) }
    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(viewModel, app.agentSettings.activeProviderId) { viewModel.syncProviderSelection() }
    var showSettings by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AgentConversation?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<AgentConversation?>(null) }
    val listState = rememberLazyListState()
    var inputHeight by remember { mutableStateOf(86.dp) }
    // Keep following a live response only while the user is at the end of the
    // stream.  Checking only the last visible item is insufficient: a long
    // streaming bubble can remain partially visible while the wheel is moving
    // it, which used to trigger an auto-scroll on every text delta and make the
    // list feel stuck.
    var followOutput by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val atBottom = info.totalItemsCount == 0 || (last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 24)
            listState.isScrollInProgress to atBottom
        }.collect { (scrolling, atBottom) ->
            // Only an active scroll gesture is allowed to change this flag.
            // Content growth often settles the list at the bottom for a frame;
            // treating that as user intent re-enabled auto-follow while the
            // wheel was still moving and pulled the viewport back.
            if (scrolling) followOutput = nextAgentFollowOutput(followOutput, userScrollInProgress = true, atBottom)
        }
    }
    LaunchedEffect(viewModel.messages.size, viewModel.liveText) {
        if (!followOutput || listState.isScrollInProgress) return@LaunchedEffect
        // Wait for the new message/text height to be laid out, then use an
        // immediate scroll.  This avoids an animation competing with wheel
        // input while the provider is still streaming.
        withFrameNanos { }
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull { it.index == count - 1 }
            val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
            listState.scrollToItem(count - 1, (last?.size?.minus(viewportHeight) ?: 0).coerceAtLeast(0))
        }
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("agent-panel")) {
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(viewModel.activeConversation?.title?.ifBlank { "新会话" } ?: "表盘 AI", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        val displayedTokens = (viewModel.activeConversation?.tokenCount ?: 0L) +
                            (if (viewModel.generating) viewModel.turnTokens ?: 0L else 0L)
                        Text((viewModel.status ?: "当前表盘 · revision ${doc.view.revision}") +
                            displayedTokens.takeIf { it > 0L }?.let { " · ${formatAgentCount(it)} tokens" }.orEmpty(),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { viewModel.showHistory = true }, enabled = !viewModel.generating) { Icon(Icons.Default.History, "历史") }
                    IconButton(onClick = { viewModel.newConversation() }, enabled = !viewModel.generating) { Icon(Icons.Default.Add, "新建") }
                    IconButton(onClick = viewModel::compressContext, enabled = !viewModel.generating && viewModel.messages.size >= 2) { Icon(Icons.Default.Compress, "压缩上下文") }
                    IconButton(onClick = { if (doc.agentFullscreen) doc.requestedInspectorTab = "AI"; doc.agentFullscreen = !doc.agentFullscreen }) { Icon(if (doc.agentFullscreen) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull, if (doc.agentFullscreen) "恢复" else "最大化") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Agent 设置") }
                }
            }
        }
        if (viewModel.showHistory) {
            Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("当前表盘的历史会话", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); TextButton(onClick = { viewModel.showHistory = false }) { Text("关闭") } }
                    viewModel.conversations.forEach { conversation ->
                        var menu by remember(conversation.id) { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().clickable(enabled = !viewModel.generating) { viewModel.selectConversation(conversation.id) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(conversation.title.ifBlank { "新会话" }, Modifier.weight(1f), maxLines = 1)
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "会话操作") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; renameTarget = conversation; renameDraft = conversation.title })
                                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleteTarget = conversation })
                                }
                            }
                        }
                    }
                }
            }
        }
        viewModel.pendingBashApproval?.let { pending ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("运行本机命令？", fontWeight = FontWeight.SemiBold)
                    Text(pending.command, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.resolveBashApproval(true) }) { Text("允许本次") }
                        OutlinedButton(onClick = { viewModel.resolveBashApproval(false) }) { Text("拒绝") }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(start = 10.dp, end = 18.dp)
                    .onPointerEvent(PointerEventType.Scroll) { followOutput = false },
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                if (viewModel.messages.isEmpty() && viewModel.liveText.isBlank()) item {
                    Text("描述你想修改的表盘内容。Agent 会先读取 revision，再验证 XML、脚本和预览。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                val visibleMessages = visibleAgentMessages(viewModel.messages, viewModel.liveText, viewModel.generating)
                items(visibleMessages, key = { it.id }) { message ->
                    AgentMessageBubble(message, onRetry = if (message.role == AgentMessageRole.USER) ({ viewModel.retry(message) }) else null, retryEnabled = !viewModel.generating)
                }
                if (viewModel.liveText.isNotBlank()) item(key = "streaming") { AgentMessageBubble(AgentMessage("streaming", viewModel.activeConversation?.id.orEmpty(), AgentMessageRole.ASSISTANT, viewModel.liveText, System.currentTimeMillis(), AgentMessageStatus.STREAMING)) }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp).width(8.dp), style = desktopScrollbarStyle())
        }
        viewModel.error?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!viewModel.generating && (viewModel.error != null || viewModel.status == "已停止")) TextButton(onClick = { viewModel.retry() }, modifier = Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("重试上一条") }
        if (viewModel.pendingAttachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            viewModel.pendingAttachments.forEach { attachment -> InputChip(selected = true, onClick = { viewModel.removeAttachment(attachment.id) }, label = { Text(attachment.displayName, maxLines = 1) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) }) }
        }
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val density = LocalDensity.current
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(viewModel.draft, { viewModel.draft = it }, Modifier.fillMaxWidth().height(inputHeight).testTag("agent-input").onFocusChanged { state ->
                        if (state.hasFocus) doc.focus = EditorFocus.TEXT else if (doc.focus == EditorFocus.TEXT) doc.focus = EditorFocus.OTHER
                    }.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.V && viewModel.pasteClipboardImage()) true
                        else if (event.key == Key.Enter && !event.isShiftPressed && event.type in setOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) { viewModel.send(); true } else false
                    }.onKeyEvent { event ->
                        if (event.key == Key.Enter && !event.isShiftPressed && event.type in setOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) { viewModel.send(); true } else false
                    }, placeholder = { Text("告诉 Agent 你要做什么…") }, maxLines = 12)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(22.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val delta = with(density) { dragAmount.y.toDp() }
                                    inputHeight = (inputHeight + delta).coerceIn(54.dp, 240.dp)
                                }
                            }.testTag("agent-input-resize"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.UnfoldMore, "调整输入框高度", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { chooseAgentFiles(doc.window).forEach(viewModel::addAttachment) }, enabled = !viewModel.generating && app.agentSettings.attachmentImportAllowed) { Icon(Icons.Default.AttachFile, "添加附件") }
                    Box {
                        TextButton(modifier = Modifier.widthIn(max = 104.dp), contentPadding = PaddingValues(horizontal = 5.dp), enabled = !viewModel.modelsLoading, onClick = { viewModel.refreshModels() }) {
                            Icon(Icons.Default.Memory, null, Modifier.size(15.dp)); Spacer(Modifier.width(3.dp)); Text(viewModel.selectedModel.ifBlank { "模型" }, maxLines = 1)
                        }
                        DropdownMenu(viewModel.modelMenu, { viewModel.modelMenu = false }) {
                            if (viewModel.modelOptions.isEmpty()) DropdownMenuItem(text = { Text(if (viewModel.modelsLoading) "正在拉取模型…" else "点击刷新模型") }, enabled = false, onClick = {})
                            viewModel.modelOptions.forEach { model -> DropdownMenuItem(text = { Text(model) }, onClick = { viewModel.selectModel(model) }) }
                        }
                    }
                    Box {
                        TextButton(modifier = Modifier.widthIn(max = 78.dp), contentPadding = PaddingValues(horizontal = 5.dp), onClick = { viewModel.thinkingMenu = true }) { Icon(Icons.Default.Psychology, null, Modifier.size(15.dp)); Spacer(Modifier.width(3.dp)); Text(viewModel.thinkingLabel, maxLines = 1) }
                        DropdownMenu(viewModel.thinkingMenu, { viewModel.thinkingMenu = false }) {
                            viewModel.thinkingOptions.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { viewModel.selectThinking(id) }) }
                        }
                    }
                    Box {
                        TextButton(modifier = Modifier.widthIn(max = 96.dp), contentPadding = PaddingValues(horizontal = 5.dp), onClick = { viewModel.workspaceMenu = true }) { Icon(Icons.Default.Folder, null, Modifier.size(15.dp)); Spacer(Modifier.width(3.dp)); Text(viewModel.selectedWorkspaceLabel, maxLines = 1) }
                        DropdownMenu(viewModel.workspaceMenu, { viewModel.workspaceMenu = false }) {
                            viewModel.workspaceOptions.forEach { workspace -> DropdownMenuItem(text = { Text(workspace.displayName) }, onClick = { viewModel.selectWorkspace(workspace.id) }) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (viewModel.generating) FilledTonalButton(onClick = viewModel::stop) { Icon(Icons.Default.Stop, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("停止") }
                    else FilledTonalButton(enabled = viewModel.draft.isNotBlank() || viewModel.pendingAttachments.isNotEmpty(), onClick = viewModel::send, modifier = Modifier.testTag("agent-send")) { Icon(Icons.Default.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("发送") }
                }
            }
        }
    }
    renameTarget?.let { target -> AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text("重命名会话") }, text = { OutlinedTextField(renameDraft, { renameDraft = it }, label = { Text("标题") }, singleLine = true) }, confirmButton = { TextButton(enabled = renameDraft.isNotBlank(), onClick = { viewModel.renameConversation(target, renameDraft); renameTarget = null }) { Text("保存") } }, dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }) }
    deleteTarget?.let { target -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除会话？") }, text = { Text("将删除“${target.title.ifBlank { "新会话" }}”及其聊天记录。") }, confirmButton = { TextButton(onClick = { viewModel.deleteConversation(target.id); deleteTarget = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }) }
    if (showSettings) HomeSettingsDialog(app, theme, onTheme, app.canvasInput, { app.canvasInput = it }, onDismiss = { showSettings = false }, initialPage = 1)
}

/** Keep provider tool-turn records, but paint only one copy of repeated prose. */
internal fun visibleAgentMessages(messages: List<AgentMessage>, liveText: String, generating: Boolean): List<AgentMessage> {
    // A provider tool turn is persisted for protocol continuity, but its prose
    // is represented by the compact tool-result banner below. Do not dedupe by
    // text: two separate answers are allowed to have identical content.
    return messages.filterNot { it.role == AgentMessageRole.ASSISTANT && it.toolCalls.isNotEmpty() }
}

internal fun nextAgentFollowOutput(current: Boolean, userScrollInProgress: Boolean, atBottom: Boolean): Boolean =
    if (userScrollInProgress) atBottom else current

@OptIn(ExperimentalComposeUiApi::class)
@Composable private fun AgentMessageBubble(message: AgentMessage, onRetry: (() -> Unit)? = null, retryEnabled: Boolean = true) {
    val user = message.role == AgentMessageRole.USER
    val tool = message.role == AgentMessageRole.TOOL
    var expanded by remember(message.id) { mutableStateOf(false) }
    var detailsExpanded by remember(message.id) { mutableStateOf(false) }
    var contextMenu by remember(message.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 520.dp).onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    contextMenu = true
                    event.changes.forEach { it.consume() }
                }
            },
        ) {
        Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else if (tool) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = if (tool) 6.dp else 9.dp), verticalArrangement = Arrangement.spacedBy(if (tool) 2.dp else 4.dp)) {
                if (tool) {
                    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Default.Build, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(toolDisplayName(message.toolName), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        Text(if (message.content.contains("失败")) "失败" else "完成", style = MaterialTheme.typography.labelSmall, color = if (message.content.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    if (expanded) {
                        Row(Modifier.fillMaxWidth().clickable { detailsExpanded = !detailsExpanded }, verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer { Text(message.content, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                            Text(if (detailsExpanded) "收起详情" else "查看详情", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (detailsExpanded) Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                            SelectionContainer {
                                Text(toolDetails(message), Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                } else {
                    Text(if (user) "你" else "Agent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (message.content.isNotBlank()) SelectionContainer {
                        Text(remember(message.content) { agentMarkdown(message.content) }, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (message.toolCalls.isNotEmpty()) Text("调用工具", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                message.attachments.forEach { attachment ->
                    val bitmap = remember(attachment.localPath) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(Files.readAllBytes(Path.of(attachment.localPath!!))).toComposeImageBitmap() }.getOrNull() }
                    if (bitmap != null) Image(bitmap, attachment.displayName, Modifier.fillMaxWidth().heightIn(max = 200.dp), contentScale = ContentScale.Fit)
                    else Text(attachment.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (!tool) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    message.turnTokens?.takeIf { it > 0L }?.let { Text("${formatAgentCount(it)} tokens", Modifier.align(Alignment.CenterVertically).padding(end = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (onRetry != null) IconButton(enabled = retryEnabled, onClick = onRetry, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Refresh, "重试消息", Modifier.size(15.dp))
                    }
                    IconButton(onClick = { copyAgentText(message.content) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, "复制消息", Modifier.size(15.dp))
                    }
                }
            }
        }
        DropdownMenu(
            expanded = contextMenu,
            onDismissRequest = { contextMenu = false },
            modifier = Modifier.testTag("agent-message-context-menu"),
        ) {
            if (!tool) DropdownMenuItem(
                text = { Text("复制消息") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                onClick = { contextMenu = false; copyAgentText(message.content) },
            )
            if (user && onRetry != null) {
                DropdownMenuItem(
                    text = { Text("重试消息") },
                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                    enabled = retryEnabled,
                    onClick = { contextMenu = false; onRetry() },
                )
            }
        }
        }
    }
}

private fun formatAgentCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> "${(value / 100.0).toInt() / 10.0}K"
    else -> "${(value / 100_000.0).toInt() / 10.0}M"
}

private fun copyAgentText(text: String) {
    if (text.isBlank()) return
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

private fun toolDisplayName(raw: String?): String = when (raw) {
    "inspect_face" -> "检查表盘"
    "read_document" -> "读取文档"
    "search_knowledge" -> "检索知识"
    "validate_face" -> "校验表盘"
    "preview_face" -> "生成预览"
    "edit_document" -> "编辑文档"
    "write_document" -> "写入文档"
    "list_workspace_files" -> "列出工作区文件"
    "read_image" -> "读取图片"
    "write_workspace_file" -> "写入工作区文件"
    "run_command", "run_bash" -> "运行命令"
    "sync_face_mirror" -> "同步表盘镜像"
    "validate_face_mirror" -> "校验表盘镜像"
    "commit_face_mirror" -> "提交表盘镜像"
    "import_face_resource" -> "导入表盘资源"
    "generate_image" -> "生成图片"
    "edit_image" -> "编辑图片"
    "search_web" -> "搜索资料"
    "scrape_page" -> "抓取网页"
    else -> raw?.replace('_', ' ')?.ifBlank { null } ?: "工具步骤"
}

private fun toolDetails(message: AgentMessage): String {
    val input = message.toolInputJson?.let { AgentJsonParser.parse(it) as? AgentJsonValue.Obj }
    val result = message.toolResultJson?.let { (AgentJsonParser.parse(it) as? AgentJsonValue.Obj)?.obj("result") }
    val lines = buildList {
        input?.string("command")?.let { add("命令：$it") }
        input?.string("path")?.let { add("路径：$it") }
        result?.string("working_directory")?.let { add("工作目录：$it") }
        result?.long("exit_code")?.let { add("退出码：$it") }
        result?.bool("dry_run")?.takeIf { it }?.let { add("试运行：是") }
        result?.string("stdout")?.takeIf { it.isNotBlank() }?.let { add("输出：\n$it") }
        result?.string("command")?.let { if (none { it.startsWith("命令：") }) add("命令：$it") }
        if (input != null) add("调用参数：${AgentJsonWriter.encode(input)}")
        if (result != null && result.fields.keys.any { it !in setOf("command", "stdout", "exit_code", "dry_run", "working_directory", "truncated", "run_id") }) add("执行结果：${AgentJsonWriter.encode(result)}")
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n\n") ?: message.toolResultJson.orEmpty().ifBlank { message.content }
}

/** Small desktop Markdown renderer for headings, lists, emphasis and inline code. */
private fun agentMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    source.lines().forEachIndexed { lineIndex, original ->
        if (lineIndex > 0) append('\n')
        val heading = original.takeWhile { it == '#' }.length.takeIf { it in 1..3 && original.getOrNull(it) == ' ' }
        val line = when {
            heading != null -> original.drop(heading + 1)
            original.startsWith("- ") -> "• " + original.drop(2)
            else -> original
        }
        val style = if (heading != null) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle()
        withStyle(style) {
            var at = 0
            while (at < line.length) {
                val marker = when {
                    line.startsWith("**", at) -> "**"
                    line.startsWith("`", at) -> "`"
                    else -> null
                }
                if (marker == null) { append(line[at]); at++; continue }
                val close = line.indexOf(marker, at + marker.length)
                if (close < 0) { append(marker); at += marker.length; continue }
                withStyle(if (marker == "**") SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontFamily = FontFamily.Monospace)) { append(line.substring(at + marker.length, close)) }
                at = close + marker.length
            }
        }
    }
}

internal class DesktopAgentViewModel(private val app: EditorApplicationModel, private val doc: DocumentController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var shellRunner: DesktopShellRunner? = null
    private var pendingFuture: CompletableFuture<Boolean>? = null
    private val toolInputs = mutableMapOf<String, String>()
    private val titleJobs = mutableMapOf<String, Job>()
    private val attachmentMutex = Mutex()
    data class PendingBashApproval(val command: String, val workspaceId: String)
    var pendingBashApproval by mutableStateOf<PendingBashApproval?>(null)
        private set
    private var started = false
    var draft by mutableStateOf("")
    var pendingAttachments by mutableStateOf<List<AgentAttachment>>(emptyList())
        private set
    var messages by mutableStateOf<List<AgentMessage>>(emptyList())
        private set
    var conversations by mutableStateOf<List<AgentConversation>>(emptyList())
        private set
    var activeConversation by mutableStateOf<AgentConversation?>(null)
        private set
    var liveText by mutableStateOf("")
        private set
    var turnTokens by mutableStateOf<Long?>(null)
        private set
    var generating by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var showHistory by mutableStateOf(false)
    var modelOptions by mutableStateOf<List<String>>(emptyList())
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelMenu by mutableStateOf(false)
    var selectedModel by mutableStateOf("")
        private set
    var thinkingMenu by mutableStateOf(false)
    var thinkingDepth by mutableStateOf("default")
        private set
    val thinkingOptions = listOf("none" to "不思考", "low" to "快速", "medium" to "平衡", "high" to "深度", "default" to "默认")
    val thinkingLabel: String get() = thinkingOptions.firstOrNull { it.first == thinkingDepth }?.second ?: "默认"
    var workspaceMenu by mutableStateOf(false)
    var workspaceOptions by mutableStateOf<List<AgentWorkspace>>(emptyList())
        private set
    var selectedWorkspaceId by mutableStateOf<String?>(null)
        private set
    val selectedWorkspaceLabel: String get() = workspaceOptions.firstOrNull { it.id == selectedWorkspaceId }?.displayName ?: "工作区"
    /** Whether a system-level Python executable was present when this conversation was opened. */
    private var globalPythonAvailable by mutableStateOf(false)

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            val manager = app.agentWorkspaceManager
            if (manager.bindingFor(doc.view.identity) == null) {
                val selected = app.agentSettings.workspaceId?.let(manager::get)
                if (selected != null) manager.bind(doc.view.identity, selected.id)
                else manager.create("${doc.view.title} 工作区", watchScope = doc.view.identity)
            }
            refreshConversations()
            workspaceOptions = manager.list()
            selectedWorkspaceId = manager.bindingFor(doc.view.identity)?.workspaceId ?: app.agentSettings.workspaceId
            globalPythonAvailable = detectGlobalPython()
            val currentProfile = app.agentProviderStore.active(app.agentSettings.activeProviderId)
            selectedModel = currentProfile?.modelId ?: app.agentSettings.defaultModelId.orEmpty()
            thinkingDepth = currentProfile?.reasoningEffort ?: "default"
            val conversation = conversations.firstOrNull() ?: app.agentConversationRepository.createConversation(doc.view.identity)
            activeConversation = conversation
            messages = app.agentConversationRepository.listMessages(conversation.id)
        }
    }

    fun syncProviderSelection() {
        if (!started) return
        scope.launch(Dispatchers.IO) {
            val profile = app.agentProviderStore.active(app.agentSettings.activeProviderId)
            selectedModel = profile?.modelId ?: app.agentSettings.defaultModelId.orEmpty()
            thinkingDepth = profile?.reasoningEffort ?: "default"
            modelOptions = emptyList()
            modelMenu = false
        }
    }

    fun newConversation() {
        scope.launch(Dispatchers.IO) {
            // Recheck for every newly opened conversation so a Python install or
            // removal made while the editor is running is reflected in the
            // system prompt.  An absent interpreter is intentionally silent.
            globalPythonAvailable = detectGlobalPython()
            val conversation = app.agentConversationRepository.createConversation(doc.view.identity)
            activeConversation = conversation; messages = emptyList(); refreshConversations()
        }
    }

    /** Compacts visible conversation text with a tool-free provider request. */
    fun compressContext() {
        if (generating) return
        val conversation = activeConversation ?: return
        if (messages.count { it.role == AgentMessageRole.USER || it.role == AgentMessageRole.ASSISTANT && it.toolCalls.isEmpty() } < 2) return
        generating = true; status = "正在压缩上下文…"; error = null
        job = scope.launch(Dispatchers.IO) {
            try {
                val store = app.agentProviderStore
                val profile = store.active(app.agentSettings.activeProviderId) ?: throw IllegalStateException("尚未配置 Provider")
                if (compressConversation(conversation, profile, store.apiKey(profile.id), store.headers(profile.id), app.agentConversationRepository.listMessages(conversation.id))) {
                    messages = app.agentConversationRepository.listMessages(conversation.id)
                    refreshConversations()
                    status = "上下文已压缩"
                } else status = "当前对话无需压缩"
            } catch (cancelled: CancellationException) { status = "已停止" }
            catch (failure: Throwable) { error = failure.message ?: "上下文压缩失败"; status = null }
            finally { generating = false; job = null }
        }
    }

    fun refreshModels() {
        modelMenu = true
        if (modelsLoading) return
        modelsLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val store = app.agentProviderStore
                val profile = store.active(app.agentSettings.activeProviderId) ?: throw IllegalStateException("请先在 Agent 设置中添加 Provider")
                modelOptions = AgentProviderClient().listModels(profile, store.apiKey(profile.id), store.headers(profile.id)).map { it.id }
                if (selectedModel.isBlank()) modelOptions.firstOrNull()?.let(::selectModel)
                error = null
            } catch (failure: Throwable) { error = failure.message ?: "模型列表拉取失败" }
            finally { modelsLoading = false }
        }
    }

    fun selectModel(model: String) {
        selectedModel = model
        modelMenu = false
        scope.launch(Dispatchers.IO) {
            val store = app.agentProviderStore
            store.active(app.agentSettings.activeProviderId)?.let { profile ->
                store.save(profile.copy(modelId = model), store.apiKey(profile.id).orEmpty())
                app.updateAgentSettings(app.agentSettings.copy(activeProviderId = profile.id, defaultModelId = model))
            }
        }
    }

    fun selectThinking(value: String) {
        thinkingDepth = value; thinkingMenu = false
        scope.launch(Dispatchers.IO) {
            val store = app.agentProviderStore
            store.active(app.agentSettings.activeProviderId)?.let { profile -> store.save(profile.copy(reasoningEffort = value.takeIf { it != "default" }), store.apiKey(profile.id).orEmpty()) }
        }
    }

    fun selectWorkspace(id: String) {
        selectedWorkspaceId = id; workspaceMenu = false
        app.updateAgentSettings(app.agentSettings.copy(workspaceId = id))
        scope.launch(Dispatchers.IO) { app.agentWorkspaceManager.bind(doc.view.identity, id) }
    }

    fun selectConversation(id: String) {
        scope.launch(Dispatchers.IO) {
            val conversation = app.agentConversationRepository.getConversation(id) ?: return@launch
            if (conversation.watchScope != doc.view.identity) return@launch
            activeConversation = conversation; messages = app.agentConversationRepository.listMessages(id); showHistory = false
        }
    }

    fun renameConversation(conversation: AgentConversation, title: String) {
        scope.launch(Dispatchers.IO) {
            val renamed = app.agentConversationRepository.renameConversation(conversation.id, title)
            if (activeConversation?.id == renamed.id) activeConversation = renamed
            refreshConversations()
        }
    }

    fun deleteConversation(id: String) {
        scope.launch(Dispatchers.IO) {
            titleJobs.remove(id)?.cancel()
            app.agentConversationRepository.deleteConversation(id); refreshConversations()
            if (activeConversation?.id == id) newConversation()
        }
    }

    fun send() {
        val text = draft.trim(); val conversation = activeConversation ?: return
        if ((text.isBlank() && pendingAttachments.isEmpty()) || generating) return
        val attached = pendingAttachments
        val user = AgentMessage.user(conversation.id, text.ifBlank { "请分析我附上的图片。" }, System.currentTimeMillis()).copy(attachments = attached)
        draft = ""; pendingAttachments = emptyList()
        launchGeneration(conversation, user, appendUser = true)
    }

    fun retry(message: AgentMessage? = null) {
        val conversation = activeConversation ?: return
        val user = message ?: messages.lastOrNull { it.role == AgentMessageRole.USER } ?: return
        if (user.role != AgentMessageRole.USER || user.conversationId != conversation.id) return
        if (generating) return
        launchGeneration(conversation, user, appendUser = false)
    }

    private fun launchGeneration(conversation: AgentConversation, user: AgentMessage, appendUser: Boolean) {
        error = null; generating = true; liveText = ""; turnTokens = null; status = "正在准备表盘上下文…"
        job = scope.launch(Dispatchers.IO) {
            var inputTokens = 0L
            var outputTokens = 0L
            var completed = false
            if (appendUser) {
                app.agentConversationRepository.appendMessage(conversation.id, user)
                messages = messages + user
            } else {
                app.agentConversationRepository.deleteMessagesAfter(conversation.id, user.id)
                messages = app.agentConversationRepository.listMessages(conversation.id)
            }
            try {
                val store = app.agentProviderStore
                var profile = store.active(app.agentSettings.activeProviderId)
                if (profile == null) throw IllegalStateException("尚未配置 Provider，请打开 Agent 设置")
                if (profile.modelId.isNullOrBlank()) {
                    status = "正在拉取模型列表…"
                    val found = AgentProviderClient().listModels(profile, store.apiKey(profile.id), store.headers(profile.id))
                    val first = found.firstOrNull()?.id ?: throw IllegalStateException("Provider 没有可用模型")
                    profile = profile.copy(modelId = first); store.save(profile, store.apiKey(profile.id).orEmpty())
                }
                val contextWindow = app.agentSettings.contextWindowOverride ?: profile.contextWindowOverride ?: AgentModelContextResolver.DEFAULT_CONTEXT_WINDOW_TOKENS
                val stored = app.agentConversationRepository.listMessages(conversation.id)
                val estimated = stored.sumOf { (it.content.length + it.attachments.sumOf { attachment -> attachment.displayName.length }) / 4L } +
                    com.ailife.wftools.agent.prompt.WatchAgentPrompt.baseline.length / 4L
                if (estimated >= (contextWindow * 0.8).toLong()) {
                    status = "正在压缩上下文…"
                    try {
                        if (compressConversation(conversation, profile, store.apiKey(profile.id), store.headers(profile.id), stored)) {
                            messages = app.agentConversationRepository.listMessages(conversation.id)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        status = "上下文压缩不可用，继续处理…"
                    }
                }
                val host = DesktopWatchAgentToolHost(doc, preview = DesktopPreviewRenderer(doc)::render, workspace = app.agentWorkspaceManager, watchScope = doc.view.identity)
                host.syncFaceMirror()
                val nativeShell = DesktopShellRunner(app.agentWorkspaceManager, BashApprovalStrategy { request -> approvalFor(request) })
                shellRunner = nativeShell
                val runner = WatchAgentToolRunner(host,
                    permissionProvider = { WorkspacePermissionSettings(readAllowed = app.agentSettings.readAllowed, writeAllowed = app.agentSettings.writeAllowed, attachmentImportAllowed = app.agentSettings.attachmentImportAllowed) },
                    workspaceImages = AgentWorkspaceImageTools(app.agentWorkspaceManager, doc.view.identity),
                    externalTools = DesktopAgentExternalTools(app.agentWorkspaceManager, doc.view.identity, app.agentSettings, store.imageApiKey(), store.searchApiKey(), doc.session.workDir),
                    shellRunner = nativeShell,
                    workspaceIdProvider = { app.agentWorkspaceManager.bindingFor(doc.view.identity)?.workspaceId.orEmpty() },
                    bashEnabled = { app.agentSettings.bashMode != AgentBashMode.DENY })
                val provider = AgentProviderClient()
                val loop = WatchAgentLoop(
                    completionExecutor = AgentCompletionExecutor { request, onEvent ->
                        var requestInputTokens = 0L
                        var requestOutputTokens = 0L
                        status = "Agent 正在思考…"
                        val prepared = AgentRequestContextReducer.compact(request, contextWindow)
                        AgentContextRetryExecutor.execute(prepared,
                            send = { current -> provider.send(profile!!, store.apiKey(profile.id), current, extraHeaders = store.headers(profile.id), onEvent = { event: AgentStreamEvent ->
                                if (event is AgentStreamEvent.TextDelta) liveText += event.text
                                if (event is AgentStreamEvent.Usage) {
                                    event.input?.let { reported ->
                                        val next = maxOf(requestInputTokens, reported)
                                        inputTokens += next - requestInputTokens
                                        requestInputTokens = next
                                    }
                                    event.output?.let { reported ->
                                        val next = maxOf(requestOutputTokens, reported)
                                        outputTokens += next - requestOutputTokens
                                        requestOutputTokens = next
                                    }
                                    turnTokens = inputTokens + outputTokens
                                }
                                onEvent(event)
                            }) },
                            compress = { current -> liveText = ""; status = "正在压缩上下文后重试…"; AgentRequestContextReducer.compact(current, contextWindow, force = true) },
                        )
                    },
                    toolRunner = runner,
                )
                val initial = buildList {
                    add(AgentRequestMessage(AgentMessageRole.SYSTEM, buildWatchAgentSystemPrompt(globalPythonAvailable)))
                    messages.takeWhile { it.id != user.id }.forEach { add(it.toRequestMessage()) }
                    add(user.toRequestMessage())
                }
                val completion = loop.run(initial, onAssistantToolTurn = { content, calls ->
                    status = "正在调用 ${calls.joinToString { it.name }}…"
                    val assistantTurn = AgentMessage.assistant(conversation.id, content, System.currentTimeMillis()).copy(toolCalls = calls)
                    calls.forEach { toolInputs[it.id] = it.argumentsJson }
                    app.agentConversationRepository.appendMessage(conversation.id, assistantTurn)
                    Snapshot.withMutableSnapshot { liveText = ""; messages = messages + assistantTurn }
                }, onToolResults = { results ->
                    results.forEach { result ->
                        val summary = when (val content = result.content) {
                            is WatchAgentValidationResult -> if (content.valid) "校验通过 · revision ${result.revision}" else content.diagnostics.joinToString("\n") { "${it.code}：${it.message}" }
                            is WatchAgentPreviewResult -> if (content.available) "本机表盘预览已生成 · revision ${result.revision}" else "本机预览不可用"
                            else -> if (result.succeeded) "已完成 · revision ${result.revision}" else "失败：${result.error?.message}"
                        }
                        val attachment = runCatching { when (val content = result.content) {
                            is WatchAgentPreviewResult -> content.imageBytes?.let { app.agentConversationRepository.addImageBytes(conversation.id, "表盘预览.png", it) }
                            is WatchAgentImageResult -> app.agentConversationRepository.addImageBytes(conversation.id, "生成图片", Base64.getDecoder().decode(content.image.base64))
                            else -> null
                        } }.getOrNull()
                        val tool = AgentMessage(UUID.randomUUID().toString(), conversation.id, AgentMessageRole.TOOL, summary, System.currentTimeMillis(), toolName = result.name, toolCallId = result.callId, toolResultJson = loop.encodeToolResult(result), toolInputJson = toolInputs.remove(result.callId), attachments = listOfNotNull(attachment))
                        app.agentConversationRepository.appendMessage(conversation.id, tool); messages = messages + tool
                    }
                })
                val assistant = AgentMessage.assistant(conversation.id, completion.text, System.currentTimeMillis()).copy(turnTokens = inputTokens + outputTokens)
                app.agentConversationRepository.appendMessage(conversation.id, assistant)
                Snapshot.withMutableSnapshot { liveText = ""; messages = messages + assistant }
                completed = true
                app.agentConversationRepository.markGeneratedTitlePending(conversation.id)
                generateConversationTitle(conversation, profile, store.apiKey(profile.id), store.headers(profile.id), user.content, completion.text)
                status = "已完成 · revision ${doc.view.revision}"
                refreshConversations()
            } catch (cancelled: CancellationException) {
                if (!completed && liveText.isNotBlank()) {
                    val partial = AgentMessage.assistant(conversation.id, liveText, System.currentTimeMillis()).copy(status = AgentMessageStatus.CANCELLED, turnTokens = inputTokens + outputTokens)
                    app.agentConversationRepository.appendMessage(conversation.id, partial)
                    Snapshot.withMutableSnapshot { liveText = ""; messages = messages + partial }
                }
                liveText = ""; status = "已停止"
            } catch (failure: Throwable) {
                if (!completed && liveText.isNotBlank()) {
                    val partial = AgentMessage.assistant(conversation.id, liveText, System.currentTimeMillis()).copy(status = AgentMessageStatus.FAILED, turnTokens = inputTokens + outputTokens)
                    app.agentConversationRepository.appendMessage(conversation.id, partial)
                    Snapshot.withMutableSnapshot { liveText = ""; messages = messages + partial }
                }
                error = failure.message ?: "Agent 请求失败"; liveText = ""; status = null
            } finally {
                val total = inputTokens + outputTokens
                if (total > 0L) activeConversation = app.agentConversationRepository.addTokenUsage(conversation.id, total)
                turnTokens = total.takeIf { it > 0L }
                refreshConversations()
                generating = false; job = null; shellRunner = null; resolveBashApproval(false)
            }
        }
    }

    private suspend fun compressConversation(
        conversation: AgentConversation, profile: AiProviderProfile, apiKey: String?, headers: Map<String, String>, history: List<AgentMessage>,
    ): Boolean {
        val visible = history.filter { it.content.isNotBlank() && (it.role == AgentMessageRole.USER || it.role == AgentMessageRole.ASSISTANT && it.toolCalls.isEmpty()) }
        if (visible.size < 2) return false
        val summary = AgentProviderClient().send(profile.copy(reasoningEffort = null), apiKey,
            AgentConversationCompressionPrompt.request(visible), extraHeaders = headers).text.trim()
        if (summary.isBlank()) return false
        app.agentConversationRepository.replaceMessages(conversation.id,
            AgentConversationCompressionPrompt.replacement(conversation.id, visible, summary))
        return true
    }

    private fun approvalFor(request: ShellCommandRequest): BashApproval = when (app.agentSettings.bashMode) {
        AgentBashMode.ALLOW -> BashApproval.ALLOW
        AgentBashMode.DENY -> BashApproval.DENY
        AgentBashMode.ASK -> {
            val future = CompletableFuture<Boolean>()
            pendingFuture = future
            pendingBashApproval = PendingBashApproval(request.command, request.workspaceId)
            val allowed = runCatching { future.get(120, TimeUnit.SECONDS) }.getOrDefault(false)
            pendingFuture = null; pendingBashApproval = null
            if (allowed) BashApproval.ALLOW else BashApproval.DENY
        }
    }
    fun resolveBashApproval(allowed: Boolean) { pendingFuture?.complete(allowed); pendingFuture = null; pendingBashApproval = null }
    fun addAttachment(path: java.nio.file.Path) {
        val conversation = activeConversation ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val attachment = importAttachmentToWorkspace(conversation, path)
                attachmentMutex.withLock { pendingAttachments = pendingAttachments + attachment }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.message ?: "附件无法添加"
            }
        }
    }
    /** Routes an image paste into the Agent composer when it owns focus. */
    fun pasteClipboardImage(): Boolean {
        val clipboard = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return false
        if (!runCatching { clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) }.getOrDefault(false)) return false
        val image = runCatching { clipboard.getData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return false
        val width = image.getWidth(null).takeIf { it > 0 } ?: return true
        val height = image.getHeight(null).takeIf { it > 0 } ?: return true
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        try { graphics.drawImage(image, 0, 0, null) } finally { graphics.dispose() }
        val temporary = runCatching { Files.createTempFile("wftools-agent-clipboard-", ".png") }.getOrNull() ?: return true
        try {
            if (!ImageIO.write(buffered, "png", temporary.toFile())) throw IllegalStateException("PNG 编码器不可用")
            val conversation = activeConversation ?: return true
            scope.launch(Dispatchers.IO) {
                try {
                    val attachment = importAttachmentToWorkspace(conversation, temporary)
                    attachmentMutex.withLock { pendingAttachments = pendingAttachments + attachment }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    error = failure.message ?: "剪贴板图片无法添加"
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
        } catch (failure: Throwable) {
            Files.deleteIfExists(temporary)
            error = failure.message ?: "剪贴板图片无法读取"
        }
        return true
    }
    fun removeAttachment(id: String) { pendingAttachments = pendingAttachments.filterNot { it.id == id } }
    fun stop() {
        val active = job ?: return
        status = "正在停止…"
        resolveBashApproval(false)
        shellRunner?.cancelAll()
        active.cancel(CancellationException("用户停止 Agent 响应"))
    }
    fun close() { titleJobs.values.forEach(Job::cancel); titleJobs.clear(); stop(); scope.cancel() }

    private fun generateConversationTitle(
        conversation: AgentConversation,
        profile: AiProviderProfile,
        apiKey: String?,
        headers: Map<String, String>,
        userText: String,
        modelText: String,
    ) {
        val current = app.agentConversationRepository.getConversation(conversation.id)
        if (current?.titleFinalized != false || titleJobs[conversation.id]?.isActive == true) return
        titleJobs[conversation.id] = scope.launch(Dispatchers.IO) {
            try {
                val completion = AgentProviderClient().send(
                    profile.copy(reasoningEffort = null),
                    apiKey,
                    AgentConversationTitlePrompt.request(userText, modelText),
                    extraHeaders = headers,
                )
                val updated = app.agentConversationRepository.applyGeneratedTitle(conversation.id, completion.text)
                if (updated != null && activeConversation?.id == updated.id) activeConversation = updated
                refreshConversations()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the first-user-message title and retry after a later successful turn.
            } finally {
                titleJobs.remove(conversation.id)
            }
        }
    }

    private suspend fun refreshConversations() { conversations = app.agentConversationRepository.listConversations(doc.view.identity) }

    private fun importAttachmentToWorkspace(conversation: AgentConversation, source: Path): AgentAttachment {
        require(Files.isRegularFile(source) && !Files.isSymbolicLink(source)) { "附件不存在或不是普通文件" }
        val manager = app.agentWorkspaceManager
        val workspaceId = manager.bindingFor(doc.view.identity)?.workspaceId
            ?: throw IllegalStateException("请先选择工作区")
        val workspace = manager.get(workspaceId) ?: throw IllegalStateException("工作区不存在")
        val fileName = source.fileName.toString().ifBlank { "附件-${UUID.randomUUID()}" }
        var relative = "workspace/$fileName"
        var suffix = 1
        while (Files.exists(workspace.paths.root.resolve(relative))) {
            val stem = fileName.substringBeforeLast('.', fileName)
            val ext = fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            relative = "workspace/$stem-$suffix$ext"
            suffix++
        }
        when (manager.importFile(workspaceId, source, relative)) {
            com.ailife.wftools.agent.workspace.ImportResult.IMPORTED -> Unit
            com.ailife.wftools.agent.workspace.ImportResult.QUOTA_EXCEEDED -> throw IllegalStateException("附件超过工作区配额")
            else -> throw IllegalStateException("附件无法复制到工作区")
        }
        val target = workspace.paths.root.resolve(relative)
        // Keep a conversation-local copy for visual inputs so a later workspace
        // switch cannot invalidate the image sent with this message. Other
        // files stay in workspace/ and are discovered there by the Agent.
        if (isImageFile(target)) return app.agentConversationRepository.addImageAttachment(conversation.id, target)
        val mime = Files.probeContentType(target) ?: "application/octet-stream"
        return AgentAttachment(UUID.randomUUID().toString(), fileName, mime, Files.size(target), sha256(target), target.toString())
    }

    private fun isImageFile(path: Path): Boolean = runCatching { ImageIO.read(path.toFile()) != null }.getOrDefault(false)

    private fun sha256(path: Path): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun detectGlobalPython(): Boolean {
        val command = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "python" else "python3"
        return runCatching {
            ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start()
                .waitFor(2, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }
    private fun AgentMessage.toRequestMessage() = AgentRequestMessage(
        role = role,
        content = if (role == AgentMessageRole.TOOL) toolResultJson ?: content else if (role == AgentMessageRole.USER && attachments.isNotEmpty()) {
            content + "\n\n附件已放入绑定工作区，文件名：" + attachments.joinToString { it.displayName }
        } else content,
        name = toolName, toolCallId = toolCallId,
        toolCalls = toolCalls.map { AgentAssistantToolCall(it.id, it.name, it.argumentsJson) },
        images = if (role == AgentMessageRole.USER) attachments.mapNotNull { attachment -> attachment.localPath?.let { path -> runCatching { AgentImageInput.fromFile(java.nio.file.Path.of(path)) }.getOrNull() } } else emptyList(),
    )
}

/** Builds the system prompt for a newly opened conversation.  Python is only
 * mentioned when the host actually exposes a global interpreter. */
internal fun buildWatchAgentSystemPrompt(globalPythonAvailable: Boolean): String = buildString {
    append(com.ailife.wftools.agent.prompt.WatchAgentPrompt.baseline)
    if (globalPythonAvailable) append("\n当前电脑检测到全局 Python，可在需要时通过 run_command 调用它执行脚本。")
}
