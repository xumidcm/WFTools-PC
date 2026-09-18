@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.ailife.wftools.pc.market

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ailife.wftools.pc.RecentProject
import com.ailife.wftools.pc.Text
import com.ailife.wftools.pc.WatchMarket
import com.ailife.wftools.pc.platform.chooseImageFiles
import com.ailife.wftools.pc.platform.chooseWatchFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path

private enum class MarketAccountSection(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Default.Dashboard),
    WATCHFACES("我的表盘", Icons.Default.Watch),
    UPLOAD("上传表盘", Icons.Default.CloudUpload),
    NOTIFICATIONS("通知", Icons.Default.Notifications),
    PROFILE("个人资料", Icons.Default.Person),
}

/** Full native account and creator dashboard hosted inside the market window. */
@Composable
internal fun MarketAccountDialog(
    api: MarketAccountApi,
    session: MarketSession?,
    recentProjects: List<RecentProject>,
    owner: Component?,
    onSessionChange: (MarketSession?) -> Unit,
    onUnreadChange: (Int) -> Unit,
    onOpenRecent: (RecentProject) -> Unit,
    openExternal: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(min = 720.dp, max = 980.dp).heightIn(min = 560.dp, max = 720.dp).fillMaxWidth(.88f).fillMaxHeight(.88f)
                .testTag("market-account-dialog"),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            if (session == null) {
                MarketAuthContent(api, onSessionChange, onDismiss)
            } else {
                MarketCreatorDashboard(
                    api = api,
                    session = session,
                    recentProjects = recentProjects,
                    owner = owner,
                    onSessionChange = onSessionChange,
                    onUnreadChange = onUnreadChange,
                    onOpenRecent = onOpenRecent,
                    openExternal = openExternal,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun MarketAuthContent(api: MarketAccountApi, onSessionChange: (MarketSession?) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var register by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var registrationEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching(api::config) }.onSuccess { registrationEnabled = it.registrationEnabled }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.AccountCircle, null, Modifier.padding(9.dp).size(25.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("连接表盘市场账号", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("登录后可上传、编辑和管理自己的表盘。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onDismiss) { Icon(Icons.Default.Close, "关闭") }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 430.dp).padding(24.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)) {
                Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !register, onClick = { register = false; error = null }, label = { Text("登录") }, leadingIcon = { Icon(Icons.Default.Login, null, Modifier.size(17.dp)) }, modifier = Modifier.weight(1f).testTag("market-login-tab"))
                        FilterChip(selected = register, enabled = registrationEnabled, onClick = { register = true; error = null }, label = { Text("注册") }, leadingIcon = { Icon(Icons.Default.PersonAdd, null, Modifier.size(17.dp)) }, modifier = Modifier.weight(1f).testTag("market-register-tab"))
                    }
                    if (!registrationEnabled) Text("网站当前关闭了新账号注册。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(username, { username = it; error = null }, Modifier.fillMaxWidth().testTag("market-auth-username"), label = { Text("用户名") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Person, null) })
                    OutlinedTextField(password, { password = it; error = null }, Modifier.fillMaxWidth().testTag("market-auth-password"), label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Default.Lock, null) })
                    if (register) OutlinedTextField(confirmation, { confirmation = it; error = null }, Modifier.fillMaxWidth().testTag("market-auth-confirm"), label = { Text("确认密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Default.LockReset, null) })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = {
                            val issue = when {
                                !username.matches(Regex("[A-Za-z0-9_]{3,50}")) -> "用户名需要 3–50 个 ASCII 字母、数字或下划线。"
                                password.length < 6 -> "密码至少需要 6 个字符。"
                                register && password != confirmation -> "两次输入的密码不一致。"
                                else -> null
                            }
                            if (issue != null) { error = issue; return@Button }
                            busy = true; error = null
                            scope.launch {
                                withContext(Dispatchers.IO) { runCatching { if (register) api.register(username, password, confirmation) else api.login(username, password) } }
                                    .onSuccess(onSessionChange).onFailure { error = it.message ?: "账号请求失败" }
                                busy = false
                            }
                        },
                        enabled = !busy && (!register || registrationEnabled),
                        modifier = Modifier.fillMaxWidth().testTag(if (register) "market-register-submit" else "market-login-submit"),
                    ) {
                        if (busy) { CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Icon(if (register) Icons.Default.PersonAdd else Icons.Default.Login, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
                        Text(if (busy) "正在连接…" else if (register) "创建账号" else "登录")
                    }
                    Text("账号密码只发送给 Watchmaker Community；WFTools-PC 不保存密码。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MarketCreatorDashboard(
    api: MarketAccountApi,
    session: MarketSession,
    recentProjects: List<RecentProject>,
    owner: Component?,
    onSessionChange: (MarketSession?) -> Unit,
    onUnreadChange: (Int) -> Unit,
    onOpenRecent: (RecentProject) -> Unit,
    openExternal: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var section by remember { mutableStateOf(MarketAccountSection.OVERVIEW) }
    var unread by remember(session.token) { mutableIntStateOf(0) }
    LaunchedEffect(session.token) {
        withContext(Dispatchers.IO) { runCatching { api.unreadCount(session.token) } }.onSuccess { unread = it; onUnreadChange(it) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.Person, null, Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(session.user.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("创作者 Dashboard · ${session.user.role}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { runCatching { openExternal(WatchMarket.HOME_URL.trimEnd('/') + "/dashboard") } }) {
                Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("网页版")
            }
            TextButton(onClick = { onSessionChange(null); onUnreadChange(0) }, modifier = Modifier.testTag("market-logout")) { Icon(Icons.Default.Logout, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("退出登录") }
            IconButton(onDismiss) { Icon(Icons.Default.Close, "关闭") }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxSize()) {
            Surface(Modifier.width(176.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .32f)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    MarketAccountSection.entries.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(if (item == MarketAccountSection.NOTIFICATIONS && unread > 0) "${item.label} ($unread)" else item.label) },
                            icon = { Icon(item.icon, null) },
                            selected = section == item,
                            onClick = { section = item },
                            modifier = Modifier.testTag("market-account-${item.name.lowercase()}"),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(18.dp)) {
                when (section) {
                    MarketAccountSection.OVERVIEW -> MarketAccountOverview(api, session)
                    MarketAccountSection.WATCHFACES -> MarketOwnedFaces(api, session, owner)
                    MarketAccountSection.UPLOAD -> MarketUploadFace(api, session, recentProjects, owner, onOpenRecent)
                    MarketAccountSection.NOTIFICATIONS -> MarketNotificationsPane(api, session) { unread = it; onUnreadChange(it) }
                    MarketAccountSection.PROFILE -> MarketProfilePane(api, session, owner)
                }
            }
        }
    }
}

@Composable
private fun MarketAccountOverview(api: MarketAccountApi, session: MarketSession) {
    var stats by remember(session.token) { mutableStateOf<MarketStats?>(null) }
    var faces by remember(session.token) { mutableStateOf<List<MarketOwnedWatchface>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(session.token) {
        val results = withContext(Dispatchers.IO) {
            runCatching { api.stats(session.token) } to runCatching { api.myWatchfaces(session.token) }
        }
        results.first.onSuccess { stats = it }.onFailure { error = it.message }
        results.second.onSuccess { faces = it }.onFailure { error = listOfNotNull(error, it.message).joinToString("；") }
        loading = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("创作者概览", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        when {
            loading -> AccountLoading()
            else -> {
                error?.let { AccountError(it) }
                val totals = stats?.totals ?: MarketStatsTotals()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("表盘", totals.watchfaces, Icons.Default.Watch, Modifier.weight(1f))
                    StatCard("已发布", totals.published, Icons.Default.Public, Modifier.weight(1f))
                    StatCard("待审核", totals.pending, Icons.Default.HourglassTop, Modifier.weight(1f))
                    StatCard("下载", totals.downloads, Icons.Default.Download, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("浏览", totals.views, Icons.Default.Visibility, Modifier.weight(1f))
                    StatCard("收藏", totals.favorites, Icons.Default.Favorite, Modifier.weight(1f))
                    StatCard("已拒绝", totals.rejected, Icons.Default.ReportProblem, Modifier.weight(1f))
                    StatCard("已下架", totals.delisted, Icons.Default.VisibilityOff, Modifier.weight(1f))
                }
                Text("最近上传", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (faces.isEmpty()) Text("还没有上传过表盘。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                faces.take(5).forEach { face -> OwnedFaceSummary(face) }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .52f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OwnedFaceSummary(face: MarketOwnedWatchface, trailing: @Composable RowScope.() -> Unit = {}) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .34f)) {
        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (face.protected) Icons.Default.Lock else Icons.Default.Watch, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(face.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${face.screenType.ifBlank { "未知屏幕" }} · ↓${face.downloadCount} · ♥${face.favorites} · 浏览 ${face.views}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MarketStatusChip(face.status)
            trailing()
        }
    }
}

@Composable
private fun MarketStatusChip(status: String) {
    val (label, colors) = when (status) {
        "published" -> "已发布" to (MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer)
        "rejected" -> "已拒绝" to (MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer)
        "delisted" -> "已下架" to (MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant)
        else -> "待审核" to (MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer)
    }
    Surface(shape = RoundedCornerShape(50), color = colors.first) { Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.second) }
}

@Composable
private fun MarketOwnedFaces(api: MarketAccountApi, session: MarketSession, owner: Component?) {
    val scope = rememberCoroutineScope()
    var faces by remember(session.token) { mutableStateOf<List<MarketOwnedWatchface>>(emptyList()) }
    var categories by remember { mutableStateOf<List<MarketCategory>>(emptyList()) }
    var editing by remember { mutableStateOf<MarketOwnedWatchface?>(null) }
    var deleting by remember { mutableStateOf<MarketOwnedWatchface?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(session.token, refresh) {
        val results = withContext(Dispatchers.IO) {
            runCatching { api.myWatchfaces(session.token) } to runCatching { api.categories() }
        }
        results.first.onSuccess { faces = it }.onFailure { error = it.message }
        results.second.onSuccess { categories = it }.onFailure { error = listOfNotNull(error, it.message).joinToString("；") }
        loading = false
    }
    deleting?.let { face ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除表盘？") }, text = { Text("将永久删除“${face.name}”及其市场文件，此操作不能撤销。") },
            confirmButton = { Button(onClick = { val target = face; deleting = null; scope.launch { withContext(Dispatchers.IO) { runCatching { api.deleteWatchface(session.token, target.id) } }.onSuccess { refresh++ }.onFailure { error = it.message } } }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("永久删除") } },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } })
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("我的表盘", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text("编辑资料、替换文件或调整发布状态", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton({ refresh++ }) { Icon(Icons.Default.Refresh, "刷新") }
        }
        error?.let { AccountError(it) }
        editing?.let { face ->
            OwnedFaceEditor(api, session, face, categories, owner, onDone = { editing = null; refresh++ }, onCancel = { editing = null }, onError = { error = it })
        }
        when {
            loading -> AccountLoading()
            faces.isEmpty() -> AccountEmpty(Icons.Default.Watch, "还没有上传过表盘")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(faces, key = { it.id }) { face ->
                    OwnedFaceSummary(face) {
                        IconButton({ editing = face }, Modifier.testTag("market-edit-${face.id}")) { Icon(Icons.Default.Edit, "编辑") }
                        if (face.status == "delisted") IconButton({ scope.launch { withContext(Dispatchers.IO) { runCatching { api.republishWatchface(session.token, face.id) } }.onSuccess { refresh++ }.onFailure { error = it.message } } }) { Icon(Icons.Default.Publish, "重新发布") }
                        else IconButton({ scope.launch { withContext(Dispatchers.IO) { runCatching { api.unlistWatchface(session.token, face.id) } }.onSuccess { refresh++ }.onFailure { error = it.message } } }) { Icon(Icons.Default.VisibilityOff, "下架") }
                        IconButton({ deleting = face }) { Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnedFaceEditor(
    api: MarketAccountApi,
    session: MarketSession,
    face: MarketOwnedWatchface,
    categories: List<MarketCategory>,
    owner: Component?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(face.id) { mutableStateOf(face.name) }
    var description by remember(face.id) { mutableStateOf(face.description) }
    var screenType by remember(face.id) { mutableStateOf(face.screenType.ifBlank { "round" }) }
    var categoryId by remember(face.id) { mutableStateOf(face.category?.id.orEmpty()) }
    var tags by remember(face.id) { mutableStateOf(face.tags.joinToString(", ")) }
    var replacement by remember(face.id) { mutableStateOf<Path?>(null) }
    var busy by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .32f)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("编辑“${face.name}”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("名称") }, singleLine = true)
                ScreenTypePicker(screenType, { screenType = it }, Modifier.weight(1f))
            }
            CategoryPicker(categoryId, categories, { categoryId = it }, Modifier.fillMaxWidth())
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("标签（逗号分隔）") }, singleLine = true)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("描述") }, minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ chooseWatchFiles(owner).firstOrNull()?.let { replacement = it } }) { Icon(Icons.Default.AttachFile, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("替换 .watch 文件") }
                Text(replacement?.fileName?.toString() ?: "只修改资料，不替换文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                TextButton(onCancel) { Text("取消") }
                Button(onClick = {
                    val issue = validateFaceForm(name, description, screenType, categoryId)
                    if (issue != null) { onError(issue); return@Button }
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { api.editWatchface(session.token, face.id, MarketWatchfaceForm(name.trim(), description.trim(), screenType, categoryId, tags), replacement) } }
                            .onSuccess { onDone() }.onFailure { onError(it.message ?: "保存失败") }
                        busy = false
                    }
                }, enabled = !busy) { if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }; Text("保存修改") }
            }
        }
    }
}

@Composable
private fun MarketUploadFace(api: MarketAccountApi, session: MarketSession, recentProjects: List<RecentProject>, owner: Component?, onOpenRecent: (RecentProject) -> Unit) {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(MarketConfig()) }
    var categories by remember { mutableStateOf<List<MarketCategory>>(emptyList()) }
    var selected by remember { mutableStateOf<Path?>(null) }
    var parsed by remember { mutableStateOf<MarketParseResult?>(null) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var screenType by remember { mutableStateOf("round") }
    var categoryId by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<MarketOwnedWatchface?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { api.config() to api.categories() } }.onSuccess { (cfg, cats) -> config = cfg; categories = cats; if (categoryId.isBlank()) categoryId = cats.firstOrNull()?.id.orEmpty() }.onFailure { error = it.message }
    }
    fun select(path: Path) { selected = path; parsed = null; success = null; error = null }
    val recentFiles = recentProjects.filter { it.draftId == null }.mapNotNull { recent -> runCatching { recent to Path.of(recent.path) }.getOrNull() }.filter { Files.isRegularFile(it.second) && it.second.fileName.toString().endsWith(".watch", true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("上传表盘", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("先由服务器扫描并读取信息，确认资料后再正式上传。单个文件上限 ${config.maxUploadMb} MB。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { chooseWatchFiles(owner).firstOrNull()?.let(::select) }, modifier = Modifier.testTag("market-upload-local")) { Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("选择本地 .watch") }
            selected?.let { Text(it.fileName.toString(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (recentFiles.isNotEmpty() && parsed == null) {
            Text("从最近编辑中选择", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            recentFiles.take(8).forEach { (recent, path) ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .34f)) {
                    Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.History, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) { Text(recent.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(path.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        TextButton({ onOpenRecent(recent) }) { Text("打开") }
                        FilledTonalButton({ select(path) }, modifier = Modifier.testTag("market-upload-recent-${path.fileName}")) { Text("选择上传") }
                    }
                }
            }
        }
        error?.let { AccountError(it) }
        success?.let { face ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(7.dp)); Text("“${face.name}”已上传", fontWeight = FontWeight.SemiBold) }
                    Text("当前状态：${face.status}。扫描未通过的表盘仍会进入待审核，不会被静默丢弃。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        selected?.let { path ->
            if (parsed == null) {
                Button(onClick = {
                    val size = runCatching { Files.size(path) }.getOrElse { 0L }
                    if (size > config.maxUploadMb.toLong() * 1024L * 1024L) { error = "文件超过 ${config.maxUploadMb} MB 上传上限。"; return@Button }
                    busy = true; error = null
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { api.parseWatchface(session.token, path) } }.onSuccess { result ->
                            parsed = result; name = result.parsed.title.ifBlank { path.fileName.toString().removeSuffix(".watch") }
                            description = result.parsed.description.ifBlank { "由 WFTools-PC 上传" }; tags = result.parsed.tags.joinToString(", ")
                            screenType = normalizeScreenType(result.parsed.screenType); if (categoryId.isBlank()) categoryId = categories.firstOrNull()?.id.orEmpty()
                        }.onFailure { error = it.message }
                        busy = false
                    }
                }, enabled = !busy, modifier = Modifier.testTag("market-upload-analyze")) { if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }; Text(if (busy) "正在分析…" else "分析并读取表盘信息") }
            } else {
                ScanSummary(requireNotNull(parsed))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("名称") }, singleLine = true)
                    ScreenTypePicker(screenType, { screenType = it }, Modifier.weight(1f))
                }
                CategoryPicker(categoryId, categories, { categoryId = it }, Modifier.fillMaxWidth())
                OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("标签（逗号分隔）") }, singleLine = true)
                OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("描述") }, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ parsed = null; error = null }) { Text("重新选择") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        val issue = validateFaceForm(name, description, screenType, categoryId)
                        if (issue != null) { error = issue; return@Button }
                        busy = true; error = null
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { api.uploadWatchface(session.token, path, MarketWatchfaceForm(name.trim(), description.trim(), screenType, categoryId, tags)) } }
                                .onSuccess { face -> success = face; parsed = null; selected = null }.onFailure { error = it.message }
                            busy = false
                        }
                    }, enabled = !busy, modifier = Modifier.testTag("market-upload-submit")) { if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }; Icon(Icons.Default.CloudUpload, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(if (busy) "正在上传…" else "确认上传") }
                }
            }
        }
    }
}

@Composable
private fun ScanSummary(result: MarketParseResult) {
    val good = result.scan.success
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = if (good) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (good) Icons.Default.Verified else Icons.Default.Warning, null); Spacer(Modifier.width(7.dp)); Text(if (good) "扫描通过" else "扫描需要人工复核", fontWeight = FontWeight.SemiBold) }
            Text("${result.parsed.wmVersion.ifBlank { "未知版本" }} · ${result.parsed.width}×${result.parsed.height} · ${if (result.parsed.protected) "受保护" else "可编辑"} · ${result.parsed.layerCount} 个图层", style = MaterialTheme.typography.bodySmall)
            result.scan.errors.take(6).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            result.scan.warnings.take(4).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (!good) Text("仍可提交，服务器会保存文件并置为待审核。", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MarketNotificationsPane(api: MarketAccountApi, session: MarketSession, onUnreadChange: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember(session.token) { mutableStateOf<MarketNotifications?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(session.token, refresh) {
        withContext(Dispatchers.IO) { runCatching { api.notifications(session.token, 100) } }.onSuccess { data = it; onUnreadChange(it.unread) }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("通知", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Text("审核、发布和互动消息", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if ((data?.unread ?: 0) > 0) TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { api.markAllNotificationsRead(session.token) } }.onSuccess { refresh++ }.onFailure { error = it.message } } }) { Icon(Icons.Default.DoneAll, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("全部已读") }
            IconButton({ refresh++ }) { Icon(Icons.Default.Refresh, "刷新") }
        }
        error?.let { AccountError(it) }
        val notifications = data?.items
        when {
            notifications == null -> AccountLoading()
            notifications.isEmpty() -> AccountEmpty(Icons.Default.NotificationsNone, "暂无通知")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(notifications, key = { it.id }) { notification ->
                    Surface(onClick = {
                        if (notification.unread) scope.launch { withContext(Dispatchers.IO) { runCatching { api.markNotificationRead(session.token, notification.id) } }.onSuccess { refresh++ }.onFailure { error = it.message } }
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = if (notification.unread) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .62f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .30f)) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(if (notification.unread) Icons.Default.MarkEmailUnread else Icons.Default.Drafts, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) { Text(notification.title, fontWeight = if (notification.unread) FontWeight.SemiBold else FontWeight.Normal); Text(notification.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); if (notification.createdAt.isNotBlank()) Text(notification.createdAt.take(16).replace('T', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketProfilePane(api: MarketAccountApi, session: MarketSession, owner: Component?) {
    val scope = rememberCoroutineScope()
    var account by remember(session.token) { mutableStateOf<MarketAccount?>(null) }
    var description by remember { mutableStateOf("") }
    var timezone by remember { mutableStateOf("UTC") }
    var language by remember { mutableStateOf("zh-CN") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() { scope.launch { withContext(Dispatchers.IO) { runCatching { api.me(session.token) } }.onSuccess { account = it; description = it.description; timezone = it.timezone; language = it.language }.onFailure { error = it.message } } }
    LaunchedEffect(session.token) { load() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("个人资料", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        account?.let { me ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(58.dp)) { Box(contentAlignment = Alignment.Center) { Text(me.username.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) } }
                Column(Modifier.weight(1f)) {
                    Text(me.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${me.role} · ${me.createdAt.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (me.avatarUrl.isNullOrBlank()) "未设置头像" else "头像已设置", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = {
                    chooseImageFiles(owner).firstOrNull()?.let { image ->
                        val size = runCatching { Files.size(image) }.getOrElse { 0L }
                        if (size > 2L * 1024L * 1024L) {
                            error = "头像文件不能超过 2 MB。"
                        } else {
                            busy = true
                            scope.launch {
                                withContext(Dispatchers.IO) { runCatching { api.uploadAvatar(session.token, image) } }
                                    .onSuccess { message = "头像已更新"; load() }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        }
                    }
                }, enabled = !busy) { Icon(Icons.Default.AddAPhoto, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("更换头像") }
            }
        } ?: AccountLoading()
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { AccountError(it) }
        val profileReady = account != null
        OutlinedTextField(description, { description = it.take(1000) }, Modifier.fillMaxWidth(), enabled = profileReady && !busy, label = { Text("个人简介") }, minLines = 3, supportingText = { Text("${description.length}/1000") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(timezone, { timezone = it.take(64) }, Modifier.weight(1f), enabled = profileReady && !busy, label = { Text("时区") }, singleLine = true, supportingText = { Text("最多 64 个字符") })
            LanguagePicker(language, { language = it }, Modifier.weight(1f), enabled = profileReady && !busy)
        }
        Button(onClick = {
            busy = true; error = null; message = null
            scope.launch { withContext(Dispatchers.IO) { runCatching { api.updateProfile(session.token, description.trim(), timezone.trim(), language) } }.onSuccess { message = "个人资料已保存"; load() }.onFailure { error = it.message }; busy = false }
        }, enabled = profileReady && !busy) { Icon(Icons.Default.Save, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("保存个人资料") }
        HorizontalDivider()
        Text("修改密码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(currentPassword, { currentPassword = it }, Modifier.fillMaxWidth(), label = { Text("当前密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newPassword, { newPassword = it }, Modifier.weight(1f), label = { Text("新密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(confirmPassword, { confirmPassword = it }, Modifier.weight(1f), label = { Text("确认新密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        }
        OutlinedButton(onClick = {
            when {
                currentPassword.isBlank() -> error = "请输入当前密码。"
                newPassword.length < 6 -> error = "新密码至少需要 6 个字符。"
                newPassword != confirmPassword -> error = "两次输入的新密码不一致。"
                currentPassword == newPassword -> error = "新密码需要与当前密码不同。"
                else -> { busy = true; error = null; scope.launch { withContext(Dispatchers.IO) { runCatching { api.changePassword(session.token, currentPassword, newPassword) } }.onSuccess { currentPassword = ""; newPassword = ""; confirmPassword = ""; message = "密码已修改" }.onFailure { error = it.message }; busy = false } }
            }
        }, enabled = !busy) { Icon(Icons.Default.Password, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("修改密码") }
    }
}

@Composable
private fun ScreenTypePicker(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Smartphone, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("屏幕：$value", Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(expanded, { expanded = false }) { listOf("round", "square", "rectangular").forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { expanded = false; onValue(item) }) } }
    }
}

@Composable
private fun CategoryPicker(value: String, categories: List<MarketCategory>, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Category, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("分类：${categories.firstOrNull { it.id == value }?.name ?: "请选择"}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(expanded, { expanded = false }) { categories.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { expanded = false; onValue(item.id) }) } }
    }
}

@Composable
private fun LanguagePicker(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    val labels = linkedMapOf(
        "zh-CN" to "简体中文", "zh-TW" to "繁體中文", "en" to "English", "ja" to "日本語", "ko" to "한국어",
        "de" to "Deutsch", "fr" to "Français", "es" to "Español", "pt" to "Português", "ru" to "Русский",
    )
    Box(modifier) {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth(), enabled = enabled) { Icon(Icons.Default.Language, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(labels[value] ?: value, Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(expanded, { expanded = false }) { labels.forEach { (code, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onValue(code) }) } }
    }
}

private fun normalizeScreenType(raw: String): String = when (raw.lowercase()) {
    "round", "circle" -> "round"
    "rectangular", "rectangle" -> "rectangular"
    else -> "square"
}

private fun validateFaceForm(name: String, description: String, screenType: String, categoryId: String): String? = when {
    name.trim().isEmpty() -> "请输入表盘名称。"
    name.length > 100 -> "表盘名称不能超过 100 个字符。"
    description.trim().isEmpty() -> "请输入表盘描述。"
    screenType !in setOf("round", "square", "rectangular") -> "请选择有效的屏幕类型。"
    categoryId.isBlank() -> "请选择分类。"
    else -> null
}

@Composable private fun AccountLoading() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("正在加载…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun AccountError(message: String) = Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) { Icon(Icons.Default.Error, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer); Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun AccountEmpty(icon: ImageVector, message: String) = Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
