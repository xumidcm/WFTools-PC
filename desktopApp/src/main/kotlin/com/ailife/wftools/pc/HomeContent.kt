@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.ailife.wftools.pc.platform.desktopPlatformLabel
import com.ailife.wftools.pc.platform.fileManagerOpenLabel
import com.ailife.wftools.pc.platform.openExternalUrl
import com.ailife.wftools.pc.platform.openDirectoryInFileManager
import com.ailife.wftools.pc.platform.watchFileAssociation
import com.ailife.wftools.agent.model.AiProviderProfile
import com.ailife.wftools.agent.model.AiProtocol
import com.ailife.wftools.agent.model.AgentBashMode
import com.ailife.wftools.agent.provider.AgentProviderClient
import com.ailife.wftools.agent.workspace.AgentWorkspace
import com.ailife.wftools.agent.workspace.WorkspaceManager
import com.ailife.wftools.agent.workspace.DesktopShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.math.roundToInt

internal data class FileAssociationActionResult(val associated:Boolean,val error:String?)

internal fun performFileAssociationAction(
    currentlyAssociated:Boolean,
    associate:()->Unit,
    unassociate:()->Unit,
    readAssociated:()->Boolean,
):FileAssociationActionResult {
    val actionFailure=runCatching{if(currentlyAssociated)unassociate()else associate()}.exceptionOrNull()
    val refreshed=runCatching(readAssociated)
    return FileAssociationActionResult(
        associated=refreshed.getOrDefault(currentlyAssociated),
        error=(actionFailure?:refreshed.exceptionOrNull())?.message ?: if(actionFailure!=null||refreshed.isFailure)"文件关联修改失败" else null,
    )
}

@Composable fun HomeContent(app: EditorApplicationModel,onOpen: () -> Unit,onNew: () -> Unit,modifier: Modifier = Modifier,
    theme: ThemePreference = ThemePreference.SYSTEM,onTheme: (ThemePreference) -> Unit = {},
    openExternal:(String)->Unit=::openExternalUrl) {
    var settings by remember { mutableStateOf(false) }
    var logoClicks by remember { mutableIntStateOf(0) }
    var lastLogoClick by remember { mutableLongStateOf(0L) }
    fun open(url:String,label:String){runCatching{openExternal(url)}.onFailure{app.error="无法打开$label：${it.message}"}}
    Box(modifier.padding(28.dp)) {
        Column(Modifier.fillMaxSize().widthIn(max=960.dp).align(Alignment.Center)) {
            Column(Modifier.fillMaxWidth().testTag("home-fixed-header"),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Image(painterResource("icons/wftools.png"),null,Modifier.size(56.dp).testTag("home-logo").clickable {
                        val now=System.currentTimeMillis()
                        logoClicks=if(now-lastLogoClick<=1200L)logoClicks+1 else 1
                        lastLogoClick=now
                        if(logoClicks>=5){logoClicks=0;open("https://www.bilibili.com/video/BV1GJ411x7h7","彩蛋视频")}
                    })
                    Column { Text("WFTools-PC",style=MaterialTheme.typography.headlineMedium);Text("WatchMaker 表盘编辑器",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Card(onClick=onNew,modifier=Modifier.weight(1f).testTag("home-new"),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) { Icon(Icons.Default.Add,null,Modifier.size(30.dp));Column { Text("创建新表盘",fontWeight=FontWeight.Bold);Text("矩形 · 512 × 512",style=MaterialTheme.typography.bodySmall) } }
                    }
                    Card(onClick=onOpen,modifier=Modifier.weight(1f).testTag("home-open"),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) { Icon(Icons.Default.FolderOpen,null,Modifier.size(30.dp));Column { Text("打开表盘",fontWeight=FontWeight.Bold);Text("选择或拖入 .watch 文件",style=MaterialTheme.typography.bodySmall) } }
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("最近打开",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    Column(horizontalAlignment=Alignment.End) {
                        FilledTonalButton(onClick=app::continueSession,enabled=app.canContinueSession,modifier=Modifier.testTag("continue-session")) { Icon(Icons.Default.History,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("继续上次的会话") }
                        Text(app.lastActiveDisplayName ?: "没有可继续的表盘",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("home-recent-list"),verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(top=4.dp,end=8.dp,bottom=4.dp)) {
                if(app.recentProjects.isEmpty()) item { Text("还没有最近项目。打开一个表盘，或从空白画布开始。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                items(app.recentProjects,key={it.path}) { recent -> RecentProjectRow(app,recent) }
            }
            app.status?.let { LinearProgressIndicator(Modifier.fillMaxWidth());Text(it,style=MaterialTheme.typography.bodySmall) }
            app.error?.let { ErrorBanner(it){app.error=null} }
            HorizontalDivider(Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().testTag("home-fixed-footer").padding(top=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(onClick={open("https://bilibili.com/space/1609437970","作者主页")},modifier=Modifier.weight(1f).testTag("developer-profile-link"),shape=RoundedCornerShape(12.dp),color=Color.Transparent) {
                    Row(Modifier.padding(6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Image(painterResource("branding/developer.png"),"开发者头像",Modifier.size(46.dp).clip(CircleShape),contentScale=ContentScale.Crop)
                        Column { Text("开发者 · 柚子皮",Modifier.testTag("developer-attribution"),fontWeight=FontWeight.SemiBold);Text("QQ · 3513903055",style=MaterialTheme.typography.bodySmall);Text(desktopPlatformLabel(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Column(horizontalAlignment=Alignment.End) {
                    TextButton(onClick={settings=true},modifier=Modifier.testTag("home-settings")){Icon(Icons.Default.Settings,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("设置")}
                    TextButton(onClick={open("https://gitlab.com/ailife8881/wftools","上游链接")}) { Text("上游 AILIFE · GitLab",Modifier.testTag("upstream-attribution")) }
                }
            }
        }
    }
    if(settings) HomeSettingsDialog(app,theme,onTheme,app.canvasInput,{app.canvasInput=it},onDismiss={settings=false})
}

@Composable private fun RecentProjectRow(app:EditorApplicationModel,recent:RecentProject) {
    var menuPosition by remember(recent.path){mutableStateOf<Offset?>(null)}
    var deleteConfirm by remember(recent.path){mutableStateOf(false)}
    Box(Modifier.fillMaxWidth()) {
        Card(onClick={if(recent.draftId!=null)app.recover(recent.draftId)else app.open(listOf(Path.of(recent.path)))},modifier=Modifier.fillMaxWidth().testTag("recent-project-${Path.of(recent.path.replace(':','_')).fileName}").onPointerEvent(PointerEventType.Press){event->
            if(event.buttons.isSecondaryPressed){menuPosition=event.changes.firstOrNull()?.position;event.changes.forEach{it.consume()}}
        },shape=RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(if(recent.draftId!=null)Icons.Default.EditNote else Icons.Default.Watch,null,Modifier.size(26.dp),tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)){Text(recent.title,fontWeight=FontWeight.SemiBold);Text(if(recent.draftId!=null)"未保存草稿 · ${recent.title}" else recent.path,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Icon(Icons.Default.ChevronRight,null,Modifier.size(20.dp))
            }
        }
        menuPosition?.let { position -> Box(Modifier.offset{IntOffset(position.x.roundToInt(),position.y.roundToInt())}.size(1.dp)) {
            DropdownMenu(true,{menuPosition=null},modifier=Modifier.testTag("recent-context-menu")) {
                DropdownMenuItem(text={Text("删除记录")},leadingIcon={Icon(Icons.Default.RemoveCircleOutline,null)},onClick={menuPosition=null;app.removeRecentRecord(recent)})
                DropdownMenuItem(text={Text("删除表盘",color=MaterialTheme.colorScheme.error)},leadingIcon={Icon(Icons.Default.Delete,null,tint=MaterialTheme.colorScheme.error)},onClick={menuPosition=null;deleteConfirm=true})
            }
        } }
    }
    if(deleteConfirm) AlertDialog(onDismissRequest={deleteConfirm=false},title={Text("删除表盘？")},text={Text(if(recent.draftId!=null)"这会删除未保存草稿，且无法从最近编辑恢复。" else "这会直接删除原始 .watch 文件，操作不可撤销：\n${recent.path}")},confirmButton={TextButton(onClick={deleteConfirm=false;app.deleteRecentWatch(recent)}){Text("删除")}},dismissButton={TextButton(onClick={deleteConfirm=false}){Text("取消")}})
}

@Composable internal fun HomeSettingsDialog(app: EditorApplicationModel,currentTheme:ThemePreference,onTheme:(ThemePreference)->Unit,canvasInput:CanvasInputPreferences,onCanvasInput:(CanvasInputPreferences)->Unit,onDismiss:()->Unit, initialPage: Int = 0) {
    val theme = when(currentTheme){ThemePreference.SYSTEM->"跟随系统";ThemePreference.LIGHT->"浅色";ThemePreference.DARK->"深色"}
    val association=remember{watchFileAssociation()}
    val scope=rememberCoroutineScope()
    var associated by remember{mutableStateOf(false)}
    var associationLoaded by remember{mutableStateOf(false)}
    var associationBusy by remember{mutableStateOf(false)}
    var associationError by remember{mutableStateOf<String?>(null)}
    var page by remember { mutableIntStateOf(initialPage.coerceIn(0, 1)) }
    var agent by remember { mutableStateOf(app.agentSettings) }
    var endpoint by remember { mutableStateOf("https://api.openai.com/v1") }
    var providerId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var providerName by remember { mutableStateOf("OpenAI") }
    var protocol by remember { mutableStateOf(AiProtocol.CHAT_COMPLETIONS) }
    var apiKey by remember { mutableStateOf("") }
    var headerName by remember { mutableStateOf("") }
    var headerValue by remember { mutableStateOf("") }
    var contextWindow by remember { mutableStateOf("") }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf<List<AiProviderProfile>>(emptyList()) }
    var workspaces by remember { mutableStateOf<List<AgentWorkspace>>(emptyList()) }
    var workspaceName by remember { mutableStateOf("") }
    var workspaceQuotaMiB by remember { mutableStateOf("1024") }
    var imageEndpoint by remember { mutableStateOf(app.agentSettings.imageGenerationEndpoint.orEmpty()) }
    var imageModel by remember { mutableStateOf(app.agentSettings.imageModelId.orEmpty()) }
    var imageKey by remember { mutableStateOf("") }
    var searchEndpoint by remember { mutableStateOf(app.agentSettings.searchEndpoint.orEmpty()) }
    var searchKey by remember { mutableStateOf("") }
    var diagnostics by remember { mutableStateOf("") }
    val settingsScroll = rememberScrollState()
    LaunchedEffect(page) { settingsScroll.scrollTo(0) }
    var model by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var providerBusy by remember { mutableStateOf(false) }
    var providerMessage by remember { mutableStateOf<String?>(null) }
    fun pullModels() {
        if (providerBusy) return
        providerBusy = true; providerMessage = null
        scope.launch {
            try {
                val old = profiles.firstOrNull { it.id == providerId }
                val profile = (old ?: AiProviderProfile(providerId, providerName.ifBlank { "自定义 Provider" }, protocol, endpoint.trim())).copy(
                    name = providerName.ifBlank { "自定义 Provider" }, protocol = protocol, baseUrl = endpoint.trim(),
                    modelId = model.takeIf(String::isNotBlank), contextWindowOverride = contextWindow.toLongOrNull(),
                )
                val found = withContext(Dispatchers.IO) { AgentProviderClient().listModels(profile, apiKey, if (headerName.isNotBlank()) mapOf(headerName.trim() to headerValue) else emptyMap()) }
                models = found.map { it.id }
                model = model.takeIf { it in models } ?: models.firstOrNull().orEmpty()
                providerMessage = "已拉取 ${models.size} 个模型；可在右侧下拉框选择。"
            } catch (failure: Throwable) { providerMessage = failure.message ?: "模型列表拉取失败" }
            finally { providerBusy = false }
        }
    }
    fun persistProviderDraft(): Boolean {
        val baseUrl = endpoint.trim()
        if (baseUrl.isBlank()) {
            providerMessage = "请填写接口地址后再保存 Provider。"
            return false
        }
        return runCatching {
            val profile = AiProviderProfile(
                id = providerId,
                name = providerName.ifBlank { "OpenAI" },
                protocol = protocol,
                baseUrl = baseUrl,
                modelId = model.takeIf(String::isNotBlank),
                contextWindowOverride = contextWindow.toLongOrNull(),
            )
            val headers = if (headerName.isNotBlank()) mapOf(headerName.trim() to headerValue) else emptyMap()
            app.agentProviderStore.save(profile, apiKey)
            app.agentProviderStore.saveHeaders(profile.id, headers)
            profiles = app.agentProviderStore.profiles()
            providerName = profile.name
            agent = agent.copy(
                activeProviderId = profile.id,
                defaultModelId = profile.modelId,
                contextWindowOverride = profile.contextWindowOverride,
            )
            app.updateAgentSettings(agent)
            true
        }.onFailure { providerMessage = it.message ?: "Provider 保存失败" }.getOrDefault(false)
    }
    LaunchedEffect(association){associated=withContext(Dispatchers.IO){runCatching{association.isAssociated()}.getOrDefault(false)};associationLoaded=true}
    LaunchedEffect(agent.enabled) {
        if (agent.enabled) {
            profiles = app.agentProviderStore.profiles()
            val profile = app.agentProviderStore.active(agent.activeProviderId)
            providerId = profile?.id ?: providerId
            providerName = profile?.name ?: "OpenAI"
            protocol = profile?.protocol ?: AiProtocol.CHAT_COMPLETIONS
            endpoint = profile?.baseUrl ?: "https://api.openai.com/v1"
            model = profile?.modelId ?: agent.defaultModelId.orEmpty()
            apiKey = profile?.let { app.agentProviderStore.apiKey(it.id) }.orEmpty()
            val headers = profile?.let { app.agentProviderStore.headers(it.id) }.orEmpty()
            headerName = headers.keys.firstOrNull().orEmpty(); headerValue = headers.values.firstOrNull().orEmpty()
            contextWindow = profile?.contextWindowOverride?.toString().orEmpty()
            workspaces = app.agentWorkspaceManager.list()
            imageKey = app.agentProviderStore.imageApiKey().orEmpty()
            searchKey = app.agentProviderStore.searchApiKey().orEmpty()
            diagnostics = withContext(Dispatchers.IO) { desktopRuntimeDiagnostics(app.agentWorkspaceManager, workspaces.firstOrNull()?.id) }
        }
    }
    AlertDialog(onDismissRequest=onDismiss,title={
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设置", style = MaterialTheme.typography.titleMedium)
            TabRow(selectedTabIndex = page, modifier = Modifier.fillMaxWidth().height(38.dp), containerColor = Color.Transparent, divider = {}) {
                listOf("常规", "Agent").forEachIndexed { index, label ->
                    Tab(selected = page == index, onClick = { page = index }, modifier = Modifier.height(38.dp)) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    },text={Column(Modifier.widthIn(min=420.dp,max=620.dp).heightIn(max=560.dp).verticalScroll(settingsScroll),verticalArrangement=Arrangement.spacedBy(14.dp)) {
      if (page == 1) {
        Text("表盘 Agent",style=MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().testTag("agent-enabled-toggle").clickable {
            val next = !agent.enabled
            agent = agent.copy(enabled = next)
            app.updateAgentSettings(agent)
        }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { Text("启用右侧 AI 页面", fontWeight = FontWeight.SemiBold); Text("关闭时不会显示 AI 页，也不会启动 Agent 后台。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(agent.enabled, onCheckedChange = null)
        }
        if (agent.enabled) {
            HorizontalDivider()
            Text("Provider 与模型",style=MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { providerMenu = true }) { Text(providerName.ifBlank { "选择 Provider" }) }
                    DropdownMenu(providerMenu, { providerMenu = false }) {
                        profiles.forEach { profile -> DropdownMenuItem(text = { Text(profile.name) }, onClick = {
                            providerMenu = false; providerId = profile.id; providerName = profile.name; protocol = profile.protocol
                            endpoint = profile.baseUrl; model = profile.modelId.orEmpty(); contextWindow = profile.contextWindowOverride?.toString().orEmpty()
                            apiKey = app.agentProviderStore.apiKey(profile.id).orEmpty()
                            val headers = app.agentProviderStore.headers(profile.id)
                            headerName = headers.keys.firstOrNull().orEmpty(); headerValue = headers.values.firstOrNull().orEmpty()
                            agent = agent.copy(activeProviderId = profile.id, defaultModelId = profile.modelId, contextWindowOverride = profile.contextWindowOverride)
                            app.updateAgentSettings(agent)
                        }) }
                    }
                }
                OutlinedButton(onClick = { providerId = java.util.UUID.randomUUID().toString(); providerName = "OpenAI"; protocol = AiProtocol.CHAT_COMPLETIONS; endpoint = "https://api.openai.com/v1"; model = ""; apiKey = ""; headerName = ""; headerValue = ""; contextWindow = "" }) { Text("新增") }
            }
            OutlinedTextField(providerName, { providerName = it }, Modifier.fillMaxWidth(), label = { Text("Provider 名称") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AiProtocol.entries.forEach { option -> FilterChip(selected = protocol == option, onClick = { protocol = option }, label = { Text(when(option) { AiProtocol.CHAT_COMPLETIONS -> "Chat"; AiProtocol.RESPONSES -> "Responses"; AiProtocol.CLAUDE_MESSAGES -> "Claude" }) }) }
            }
            OutlinedTextField(endpoint,{ endpoint=it },Modifier.fillMaxWidth().testTag("agent-provider-endpoint"),label={Text("接口地址")},singleLine=true)
            OutlinedTextField(apiKey,{ apiKey=it },Modifier.fillMaxWidth(),label={Text("API Key · 可留空")},singleLine=true,visualTransformation=PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(headerName, { headerName = it }, Modifier.weight(1f), label = { Text("自定义 Header") }, singleLine = true)
                OutlinedTextField(headerValue, { headerValue = it }, Modifier.weight(1f), label = { Text("Header 值") }, singleLine = true)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(model,{ model=it },Modifier.weight(1f).testTag("agent-model-id"),label={Text("模型 ID（可手填）")},singleLine=true)
                Box {
                    OutlinedButton(onClick = { pullModels(); modelMenu = true }, enabled = !providerBusy, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(if (providerBusy) "拉取中…" else "拉取模型")
                    }
                    DropdownMenu(modelMenu, { modelMenu = false }) {
                        if (models.isEmpty()) DropdownMenuItem(text = { Text(if (providerBusy) "正在拉取…" else "暂无模型") }, enabled = false, onClick = {})
                        models.forEach { id -> DropdownMenuItem(text = { Text(id) }, onClick = { model = id; modelMenu = false }) }
                    }
                }
            }
            OutlinedTextField(contextWindow, { contextWindow = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("上下文窗口 Token · 100K 默认值") }, singleLine = true)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled=!providerBusy,onClick={
                    providerBusy=true; providerMessage=null
                    scope.launch {
                        try {
                            if (model.isBlank()) throw IllegalStateException("请先点击“拉取模型”并选择一个模型")
                            val old = profiles.firstOrNull { it.id == providerId }
                            val profile = (old ?: AiProviderProfile(providerId,providerName.ifBlank { "OpenAI" },protocol,endpoint)).copy(name=providerName.ifBlank { "OpenAI" },protocol=protocol,baseUrl=endpoint.trim(),modelId=model,contextWindowOverride=contextWindow.toLongOrNull())
                            val headers = if (headerName.isNotBlank()) mapOf(headerName.trim() to headerValue) else emptyMap()
                            withContext(Dispatchers.IO) {
                                AgentProviderClient().send(profile, apiKey, com.ailife.wftools.agent.provider.AgentProviderRequest(listOf(com.ailife.wftools.agent.provider.AgentRequestMessage(com.ailife.wftools.agent.model.AgentMessageRole.USER, "Reply OK")), stream = false), headers)
                            }
                            app.agentProviderStore.save(profile, apiKey)
                            app.agentProviderStore.saveHeaders(profile.id, headers)
                            profiles = app.agentProviderStore.profiles()
                            agent = agent.copy(activeProviderId=profile.id, defaultModelId=model, contextWindowOverride=contextWindow.toLongOrNull())
                            app.updateAgentSettings(agent)
                            providerMessage = "连接测试成功；视觉和工具调用能力以模型实际响应为准。"
                        } catch (error: Exception) { providerMessage = error.message ?: "Provider 连接失败" }
                        finally { providerBusy=false }
                    }
                }) { Text(if(providerBusy) "测试中…" else "测试连接") }
                if (models.isNotEmpty()) Text("可选：${models.take(3).joinToString(", ")}", style=MaterialTheme.typography.bodySmall)
            }
            providerMessage?.let { Text(it, style=MaterialTheme.typography.bodySmall, color=if(it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("上下文窗口未知时使用 200K 默认值；可在 Provider 配置中手工覆盖。", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("图片与搜索",style=MaterialTheme.typography.titleSmall)
            OutlinedTextField(imageEndpoint, { imageEndpoint = it; agent = agent.copy(imageGenerationEndpoint = it.takeIf(String::isNotBlank)); app.updateAgentSettings(agent) }, Modifier.fillMaxWidth(), label = { Text("图片生成接口 · 可选") }, singleLine = true)
            OutlinedTextField(imageModel, { imageModel = it; agent = agent.copy(imageModelId = it.takeIf(String::isNotBlank)); app.updateAgentSettings(agent) }, Modifier.fillMaxWidth(), label = { Text("图片模型 ID · 可选") }, singleLine = true)
            OutlinedTextField(imageKey, { imageKey = it }, Modifier.fillMaxWidth(), label = { Text("图片接口 API Key · 可留空") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(searchEndpoint, { searchEndpoint = it; agent = agent.copy(searchEndpoint = it.takeIf(String::isNotBlank)); app.updateAgentSettings(agent) }, Modifier.fillMaxWidth(), label = { Text("搜索接口 · 可选") }, singleLine = true)
            OutlinedTextField(searchKey, { searchKey = it }, Modifier.fillMaxWidth(), label = { Text("搜索接口 API Key · 可留空") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedButton(onClick = {
                app.agentProviderStore.saveImageApiKey(imageKey)
                app.agentProviderStore.saveSearchApiKey(searchKey)
                if (persistProviderDraft()) providerMessage = "Provider 与外部接口凭据已保存。"
            }) { Text("保存外部接口凭据") }
            Text("图片附件和本机预览可直接使用；外部图片生成、网络搜索和网页正文抓取需配置兼容接口。", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("工作区",style=MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(workspaceName, { workspaceName = it }, Modifier.weight(1f), label = { Text("新建工作区名称") }, singleLine = true)
                OutlinedTextField(workspaceQuotaMiB, { workspaceQuotaMiB = it.filter(Char::isDigit) }, Modifier.width(100.dp), label = { Text("配额 MiB") }, singleLine = true)
                OutlinedButton(enabled = workspaceName.isNotBlank() && workspaceQuotaMiB.toLongOrNull()?.let { it in 1L..102400L } == true, onClick = {
                    runCatching { app.agentWorkspaceManager.create(workspaceName.trim(), workspaceQuotaMiB.toLong() * 1024L * 1024L) }.onSuccess { created ->
                        workspaces = app.agentWorkspaceManager.list(); workspaceName = ""
                        agent = agent.copy(workspaceId = created.id); app.updateAgentSettings(agent)
                    }.onFailure { providerMessage = it.message }
                }, modifier = Modifier.align(Alignment.CenterVertically)) { Text("创建") }
            }
            workspaces.forEach { workspace ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = agent.workspaceId == workspace.id,
                        onClick = { agent = agent.copy(workspaceId = workspace.id); app.updateAgentSettings(agent) },
                        modifier = Modifier.weight(1f),
                        label = { Text("${workspace.displayName} · ${workspace.quota.usedBytes / 1024 / 1024}/${workspace.quota.limitBytes / 1024 / 1024} MiB", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    if (agent.workspaceId == workspace.id) {
                        OutlinedButton(
                            onClick = { runCatching { openDirectoryInFileManager(workspace.paths.root) }.onFailure { providerMessage = it.message ?: "无法打开工作区目录" } },
                            modifier = Modifier.testTag("agent-open-workspace"),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(fileManagerOpenLabel(), maxLines = 1)
                        }
                    }
                }
            }
            Text("每个表盘绑定一个隔离工作区；未选时首次进入 AI 页自动创建。", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("命令与权限",style=MaterialTheme.typography.titleSmall)
            HomeCanvasInputSwitch("允许读取当前表盘", agent.readAllowed, "agent-read-toggle") { agent = agent.copy(readAllowed = it); app.updateAgentSettings(agent) }
            HomeCanvasInputSwitch("允许修改当前表盘", agent.writeAllowed, "agent-write-toggle") { agent = agent.copy(writeAllowed = it); app.updateAgentSettings(agent) }
            HomeCanvasInputSwitch("允许导入图片附件", agent.attachmentImportAllowed, "agent-attachment-toggle") { agent = agent.copy(attachmentImportAllowed = it); app.updateAgentSettings(agent) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AgentBashMode.entries.forEach { mode -> FilterChip(selected = agent.bashMode == mode, onClick = { agent = agent.copy(bashMode = mode); app.updateAgentSettings(agent) }, label = { Text(when(mode) { AgentBashMode.ASK -> "每次询问"; AgentBashMode.ALLOW -> "自动允许"; AgentBashMode.DENY -> "禁用" }) }) }
            }
            Text("Windows 使用 PowerShell，Linux 使用 Bash，工作目录为绑定工作区。原生命令仍可访问本机其他路径，请审查命令后再允许；停止生成会终止子进程。", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("数据与诊断",style=MaterialTheme.typography.titleSmall)
            Text("会话历史按表盘隔离保存，API Key 使用本地加密文件保存。", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(diagnostics.ifBlank { "正在检测本机能力…" }, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        Text("外观",style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("跟随系统","浅色","深色").forEach{value->FilterChip(selected=theme==value,onClick={onTheme(when(value){"浅色"->ThemePreference.LIGHT;"深色"->ThemePreference.DARK;else->ThemePreference.SYSTEM})},label={Text(value)})}}
        HorizontalDivider()
        Text("语言",style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(selected=DesktopI18n.language()==DesktopLanguage.CHINESE,onClick={DesktopI18n.setLanguage(DesktopLanguage.CHINESE)},label={Text("中文")})
            FilterChip(selected=DesktopI18n.language()==DesktopLanguage.ENGLISH,onClick={DesktopI18n.setLanguage(DesktopLanguage.ENGLISH)},label={Text("English")})
        }
        Text("界面语言只影响 WFTools-PC 文案，不会修改表盘内容。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("画布操作",style=MaterialTheme.typography.titleSmall)
        HomeCanvasInputSwitch("中键拖动画布",canvasInput.middleButtonPan,"home-middle-button-pan-toggle"){onCanvasInput(canvasInput.copy(middleButtonPan=it))}
        HomeCanvasInputSwitch("右键拖动画布",canvasInput.rightButtonPan,"home-right-button-pan-toggle"){onCanvasInput(canvasInput.copy(rightButtonPan=it))}
        HomeCanvasInputSwitch("滚轮缩放选中元素",canvasInput.wheelScalesSelectedLayer,"home-wheel-layer-scale-toggle"){onCanvasInput(canvasInput.copy(wheelScalesSelectedLayer=it))}
        HomeCanvasInputSwitch("自动吸附",canvasInput.snapping,"home-automatic-snapping-toggle"){onCanvasInput(canvasInput.copy(snapping=it))}
        HomeCanvasInputSwitch("变换控制点",canvasInput.showTransformHandles,"home-transform-handles-toggle"){onCanvasInput(canvasInput.copy(showTransformHandles=it))}
        HomeCanvasInputSwitch("显示操作状态提示",canvasInput.showOperationStatus,"home-operation-status-toggle"){onCanvasInput(canvasInput.copy(showOperationStatus=it))}
        Text("鼠标直拖模式：按住图层即可移动，拖动未选中的图层会切换目标。\n先选后拖模式没有选择元素时，左键拖动表盘。选择元素后，可从画布任意位置拖动选区。\n中键或右键拖动：对应开关开启时平移整个表盘。\n滚轮：开关开启时可缩放光标下的选中元素；关闭后始终缩放整个表盘。\n自动吸附会在移动和变换时对齐画布及其他元素；变换控制点可显示八向缩放点和旋转拉杆；操作状态提示只用于显示耗时任务进度。\n空格 + 左键或 Ctrl + 左键：临时拖动画布；Ctrl + 滚轮：始终缩放整个表盘。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("文件格式关联",style=MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.InsertDriveFile,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)){Text(".watch 文件",fontWeight=FontWeight.SemiBold);Text(if(associated)"当前由 WFTools-PC 打开；可在此取消关联。" else "将 .watch 文件直接关联到当前安装的 WFTools-PC。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        OutlinedButton(onClick={scope.launch {
            associationBusy=true;associationError=null
            val result=withContext(Dispatchers.IO){performFileAssociationAction(associated,association::associate,association::unassociate,association::isAssociated)}
            associated=result.associated;associationError=result.error
            associationBusy=false
        }},enabled=association.available&&associationLoaded&&!associationBusy,modifier=Modifier.fillMaxWidth().testTag("watch-association-action")) {
            Icon(if(associated)Icons.Default.LinkOff else Icons.Default.Link,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text(when{associationBusy->"正在修改…";!association.available->"仅安装版可修改关联";associated->"取消 .watch 关联";else->"关联 .watch 文件"})
        }
        associationError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
      }
    }},confirmButton={Button(onClick={ if (persistProviderDraft()) onDismiss() }){Text("完成")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable private fun HomeCanvasInputSwitch(label:String,checked:Boolean,tag:String,onChecked:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable{onChecked(!checked)}.padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,Modifier.weight(1f))
        Switch(checked,onCheckedChange=null)
    }
}

private fun desktopRuntimeDiagnostics(manager: WorkspaceManager, workspaceId: String?): String {
    fun available(vararg command: String): Boolean = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); false }
        else process.exitValue() == 0
    }.getOrDefault(false)
    val windows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    val shell = if (windows) available("pwsh", "-Version") || available("powershell", "-Version") else available("bash", "--version")
    val python = if (windows) "python" else "python3"
    val pythonPresent = available(python, "--version")
    val pillow = pythonPresent && available(python, "-c", "import PIL")
    val numpy = pythonPresent && available(python, "-c", "import numpy")
    val bound = workspaceId?.let { DesktopShellRunner(manager).capability(it).bashAvailable } ?: false
    return "本机 Shell：${if(shell) "可用" else "未发现"} · Python：${if(pythonPresent) "可用" else "未发现"} · Pillow：${if(pillow) "可用" else "未发现"} · NumPy：${if(numpy) "可用" else "未发现"} · 工作区 Shell：${if(bound) "可用" else "未绑定"}"
}
