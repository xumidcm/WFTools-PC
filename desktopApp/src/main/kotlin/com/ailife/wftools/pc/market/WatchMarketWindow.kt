@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.ailife.wftools.pc.market

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ailife.wftools.pc.MarketDownloadAction
import com.ailife.wftools.pc.RecentProject
import com.ailife.wftools.pc.Text
import com.ailife.wftools.pc.WatchMarket
import com.ailife.wftools.pc.marketWatchActions
import com.ailife.wftools.pc.marketRevealLabel
import com.ailife.wftools.pc.platform.chooseSavePath
import com.ailife.wftools.pc.platform.openExternalUrl
import com.ailife.wftools.pc.platform.showInFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Where a market download landed before the user decides what to do with it. */
data class MarketDownload(val path: Path, val item: MarketItem)

/** Store preview assets are normalised to this fixed square before Compose sees them. */
const val MARKET_PREVIEW_SIZE_PX: Int = 512

/**
 * The in-app market window.
 *
 * Browsing and filtering stay in the app; the "open in browser" button hands the
 * same store over to the default browser for anything this view does not cover.
 */
@Composable
fun WatchMarketWindow(
    api: WatchMarketApi,
    downloadRoot: Path,
    onOpenWatch: (Path) -> Unit,
    onClose: () -> Unit,
    onError: (String) -> Unit = {},
    openExternal: (String) -> Unit = ::openExternalUrl,
    recentProjects: List<RecentProject> = emptyList(),
    owner: Component? = null,
    accountApi: MarketAccountApi? = null,
    marketSession: MarketSession? = null,
    onSessionChange: (MarketSession?) -> Unit = {},
    onOpenRecent: (RecentProject) -> Unit = {},
    accountStore: MarketCredentialStore? = null,
    sessionStore: MarketSessionStore? = null,
) {
    val scope = rememberCoroutineScope()
    val resolvedAccountApi = accountApi ?: remember { MarketAccountApi() }
    val resolvedAccountStore = accountStore ?: remember { PersistentMarketCredentialStore() }
    val resolvedSessionStore = sessionStore ?: remember { PersistentMarketSessionStore() }
    var sort by remember { mutableStateOf(MarketSort.LATEST) }
    var screenType by remember { mutableStateOf(MarketScreenType.ANY) }
    var category by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<MarketItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var nextPage by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var categories by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MarketItem?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf<MarketDownload?>(null) }
    var accountOpen by remember { mutableStateOf(false) }
    var session by remember(marketSession) { mutableStateOf(marketSession) }
    var unreadCount by remember { mutableIntStateOf(0) }

    val gridState = rememberLazyGridState()

    suspend fun load(reset: Boolean) {
        if (!reset && (loadingMore || !hasMore)) return
        val requestedPage = if (reset) 1 else nextPage
        if (reset) { loading = true; error = null; hasMore = true }
        else { loadingMore = true; error = null }
        val result = withContext(Dispatchers.IO) {
            runCatching { api.list(page = requestedPage, sort = sort, category = category, search = query, screenType = screenType) }
        }
        result.onSuccess { pageResult ->
            val merged = if (reset) pageResult.items else (items + pageResult.items).distinctBy(MarketItem::id)
            items = merged
            total = pageResult.total
            nextPage = requestedPage + 1
            hasMore = pageResult.items.isNotEmpty() && merged.size < pageResult.total
        }.onFailure {
            error = it.message ?: "无法加载市场列表"
            if (reset) { items = emptyList(); total = 0; nextPage = 1 }
        }
        if (reset) loading = false else loadingMore = false
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { api.categories() } }.onSuccess { categories = it }
    }
    LaunchedEffect(Unit) {
        val persisted = withContext(Dispatchers.IO) { runCatching { resolvedSessionStore.load() }.getOrNull() }
        val candidate = marketSession ?: persisted
        if (candidate == null) return@LaunchedEffect
        val checked = withContext(Dispatchers.IO) { runCatching { resolvedAccountApi.me(candidate.token) } }
        val profile = checked.getOrNull()
        if (profile != null) {
            val next = candidate.copy(
                user = candidate.user.copy(
                    id = profile.id.ifBlank { candidate.user.id },
                    username = profile.username.ifBlank { candidate.user.username },
                    role = profile.role.ifBlank { candidate.user.role },
                    avatarUrl = profile.avatarUrl,
                    avatarVersion = profile.avatarVersion,
                ),
            )
            withContext(Dispatchers.IO) { runCatching { resolvedSessionStore.save(next) } }
            session = next
            onSessionChange(next)
        } else {
            val failure = checked.exceptionOrNull()
            if (failure is MarketAccountException && failure.status == 401) {
                withContext(Dispatchers.IO) { runCatching { resolvedSessionStore.clear() } }
                session = null
                onSessionChange(null)
            } else {
                // Keep a locally restored token usable while the market is temporarily offline.
                session = candidate
                onSessionChange(candidate)
            }
        }
    }
    LaunchedEffect(sort, screenType, category) { load(reset = true) }
    // Debounce the search box so typing does not fire a request per keystroke.
    LaunchedEffect(query) {
        if (query.isBlank() && items.isEmpty()) return@LaunchedEffect
        delay(400)
        load(reset = true)
    }
    // Do not key this effect on loadingMore: toggling it would cancel the in-flight page request
    // before its result can be appended.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("market-window")) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Storefront, null, Modifier.size(22.dp))
            Text("表盘市场", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Watchmaker Community", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { runCatching { openExternal(WatchMarket.HOME_URL) }.onFailure { onError("无法打开浏览器：${it.message}") } }, modifier = Modifier.testTag("market-open-web")) {
                Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("在浏览器打开")
            }
            BadgedBox(
                badge = { if (unreadCount > 0) Badge { Text(unreadCount.coerceAtMost(99).toString()) } },
            ) {
                OutlinedButton(onClick = { accountOpen = true }, modifier = Modifier.testTag("market-account-button")) {
                    session?.let { current ->
                        MarketAvatar(resolvedAccountApi, current.user.username, current.user.avatarUrl, 24.dp, version = current.user.avatarVersion, tag = "market-account-avatar")
                    } ?: Icon(Icons.Default.AccountCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(session?.user?.username ?: "登录")
                }
            }
            IconButton(onClick = { scope.launch { load(reset = true) } }, enabled = !loading) { Icon(Icons.Default.Refresh, "刷新") }
        }
        }
        HorizontalDivider()
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("market-search"), label = { Text("搜索表盘") }, singleLine = true, trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Clear, "清除") } })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MarketCategoryPicker(category, categories, onSelected = { category = it })
                Spacer(Modifier.width(2.dp))
                MarketSort.entries.forEach { option -> FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.label) }, modifier = Modifier.testTag("market-sort-${option.wire}")) }
                Spacer(Modifier.width(8.dp))
                MarketScreenType.entries.forEach { option -> FilterChip(selected = screenType == option, onClick = { screenType = option }, label = { Text(option.label) }, modifier = Modifier.testTag("market-screen-${option.wire}")) }
            }
        }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val gridState = rememberLazyGridState()
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.testTag("market-loading"))
                        Text("正在加载表盘…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CloudOff, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.error)
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        OutlinedButton({ scope.launch { load(reset = true) } }) { Text("重试") }
                    }
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有匹配的表盘", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> LazyVerticalGrid(GridCells.Adaptive(200.dp), Modifier.fillMaxSize().testTag("market-grid"), state = gridState, contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(items, key = { it.id }) { item -> MarketCard(item, api, onClick = { selected = item }) }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MarketLoadMoreFooter(
                            itemCount = items.size,
                            hasMore = hasMore,
                            loadingMore = loadingMore,
                            onLoadMore = { scope.launch { load(reset = false) } },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已加载 ${items.size} / $total 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (loadingMore) Text("正在加载…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    selected?.let { item ->
        MarketDetailDialog(
            item = item,
            api = api,
            downloading = downloading == item.id,
            onDismiss = { selected = null },
            openExternal = { url -> runCatching { openExternal(url) }.onFailure { onError("无法打开浏览器：${it.message}") } },
            onError = onError,
            onDownload = {
                downloading = item.id
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { downloadTo(api, downloadRoot, item) } }
                    downloading = null
                    result.onSuccess { selected = null; finished = it }
                        .onFailure { onError(it.message ?: "下载失败") }
                }
            },
        )
    }

    finished?.let { download ->
        MarketDownloadDialog(
            download = download,
            onDismiss = { finished = null },
            onOpen = { finished = null; onOpenWatch(download.path) },
            onReveal = { runCatching { showInFileManager(download.path) }.onFailure { onError("无法定位文件：${it.message}") } },
            onSaveAs = { dest ->
                runCatching {
                    Files.createDirectories(dest.parent)
                    runCatching { Files.move(download.path, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                        .getOrElse { Files.move(download.path, dest, StandardCopyOption.REPLACE_EXISTING) }
                }.onSuccess { finished = null }.onFailure { onError("另存为失败：${it.message}") }
            },
            onCopyPath = {
                runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(download.path.toAbsolutePath().toString()), null) }
                    .onFailure { onError("无法复制路径：${it.message}") }
            },
            onError = onError,
        )
    }

    if (accountOpen) {
        MarketAccountDialog(
            api = resolvedAccountApi,
            credentialStore = resolvedAccountStore,
            session = session,
            recentProjects = recentProjects,
            owner = owner,
            onSessionChange = { next ->
                session = next
                onSessionChange(next)
            },
            onUnreadChange = { unreadCount = it },
            onOpenRecent = onOpenRecent,
            openExternal = { url -> runCatching { openExternal(url) }.onFailure { onError("无法打开浏览器：${it.message}") } },
            onDismiss = { accountOpen = false },
            sessionStore = resolvedSessionStore,
        )
    }
}

/** Downloads one face into [root] under a sanitised name. */
private fun downloadTo(api: WatchMarketApi, root: Path, item: MarketItem): MarketDownload {
    val bytes = api.download(item.id)
    if (bytes.isEmpty()) throw MarketException("下载内容为空")
    Files.createDirectories(root)
    val safe = item.name.replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "watchface-${item.id}" }
    var target = root.resolve("$safe.watch")
    // Never overwrite a previous download; the dialog's "save as" is the deliberate path for that.
    var index = 2
    while (Files.exists(target)) target = root.resolve("$safe ($index).watch").also { index++ }
    val temporary = Files.createTempFile(root, ".market-", ".tmp")
    try {
        Files.write(temporary, bytes)
        runCatching { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temporary) }
    return MarketDownload(target, item)
}

/**
 * The category filter, collapsed into one button that opens a menu.
 *
 * [selected] keeps the window's existing contract: an empty slug means "all". The menu items carry
 * `market-category-<slug>` tags so the existing UI tests can still drive a category selection.
 */
@Composable private fun MarketCategoryPicker(selected: String, categories: List<Pair<String, String>>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("market-category-button")) {
            Icon(Icons.Default.Category, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(categories.firstOrNull { it.first == selected }?.second ?: "全部", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("全部") }, modifier = Modifier.testTag("market-category-all"), onClick = { expanded = false; onSelected("") })
            categories.forEach { (slug, name) ->
                DropdownMenuItem(text = { Text(name) }, modifier = Modifier.testTag("market-category-$slug"), onClick = { expanded = false; onSelected(slug) })
            }
        }
    }
}

/** A lazy-grid sentinel: it is composed only when the user reaches the end of the current list. */
@Composable
private fun MarketLoadMoreFooter(
    itemCount: Int,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(itemCount, hasMore) {
        if (hasMore) onLoadMore()
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        when {
            loadingMore -> CircularProgressIndicator(Modifier.size(24.dp).testTag("market-loading-more"), strokeWidth = 2.dp)
            !hasMore -> Text("已加载全部表盘", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun MarketCard(item: MarketItem, api: WatchMarketApi, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("market-card-${item.id}"), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column {
            MarketPreview(item, api, Modifier.fillMaxWidth().aspectRatio(1f))
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(item.category.takeIf(String::isNotBlank), item.screenType.takeIf(String::isNotBlank)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("↓${item.downloadCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("♥${item.favoriteCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Loads a preview once per card and keeps it for the session.
 *
 * Faces are authored 512x512, so every preview is normalised into that square and letterboxed
 * rather than cropped: the whole design stays visible whatever aspect ratio the store serves.
 */
@Composable private fun MarketPreview(item: MarketItem, api: WatchMarketApi, modifier: Modifier) {
    var bitmap by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    LaunchedEffect(item.id) {
        val cached = cachedPreview(item.id)
        if (cached != null) {
            bitmap = cached
            loading = false
            return@LaunchedEffect
        }
        // Fetch and decode off the main thread: a 512x512 decode per card would stutter the grid.
        val decoded = withContext(Dispatchers.IO) { api.preview(item.id)?.let(::normalizePreview) }
        bitmap = decoded?.also { cachePreview(item.id, it) }
        loading = false
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)), contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null -> Image(image, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            loading -> CircularProgressIndicator(Modifier.size(28.dp).testTag("market-preview-loading"), strokeWidth = 3.dp)
            else -> Icon(Icons.Default.Watch, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Decodes a preview and letterboxes it into a 512x512 square.
 *
 * Returns null for bytes Skia cannot decode, so a broken image degrades to the placeholder icon
 * instead of propagating an exception into composition. Every Skia handle is closed; the bitmap
 * handed to Compose owns its own pixels and stays valid afterwards.
 */
private fun normalizePreview(bytes: ByteArray): ImageBitmap? = runCatching {
    org.jetbrains.skia.Image.makeFromEncoded(bytes).use { decoded ->
        org.jetbrains.skia.Surface.makeRasterN32Premul(MARKET_PREVIEW_SIZE_PX, MARKET_PREVIEW_SIZE_PX).use { surface ->
            val source = org.jetbrains.skia.Rect.makeWH(decoded.width.toFloat(), decoded.height.toFloat())
            val scale = MARKET_PREVIEW_SIZE_PX / maxOf(decoded.width, decoded.height).toFloat()
            val drawnWidth = scale * decoded.width
            val drawnHeight = scale * decoded.height
            surface.canvas.drawImageRect(
                decoded,
                source,
                org.jetbrains.skia.Rect.makeXYWH(
                    (MARKET_PREVIEW_SIZE_PX - drawnWidth) / 2f,
                    (MARKET_PREVIEW_SIZE_PX - drawnHeight) / 2f,
                    drawnWidth,
                    drawnHeight,
                ),
            )
            surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
        }
    }
}.getOrNull()

/** Bounded in-memory preview cache; cards are re-composed when the list scrolls. */
private const val PREVIEW_CACHE_MAX_ENTRIES = 48
private val previewCache = object : LinkedHashMap<String, ImageBitmap>(PREVIEW_CACHE_MAX_ENTRIES + 1, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > PREVIEW_CACHE_MAX_ENTRIES
}

private fun cachedPreview(id: String): ImageBitmap? = synchronized(previewCache) { previewCache[id] }

private fun cachePreview(id: String, bitmap: ImageBitmap) {
    synchronized(previewCache) { previewCache[id] = bitmap }
}

@Composable private fun MarketDetailDialog(
    item: MarketItem,
    api: WatchMarketApi,
    downloading: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    openExternal: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var detail by remember(item.id) { mutableStateOf<MarketItem?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var detailFallback by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        loading = true
        val loaded = withContext(Dispatchers.IO) { runCatching { api.detail(item.id) }.getOrNull() }
        detailFallback = loaded == null
        // Keep the list flag visible if an older detail endpoint omits wm_protected.
        detail = (loaded ?: item).copy(protected = (loaded?.protected == true) || item.protected)
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("market-detail"),
        title = {
            if (loading) {
                Text("表盘详情", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            } else {
                val shown = requireNotNull(detail)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Default.Watch, null, Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(shown.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (detailFallback) "表盘详情 · 已使用列表信息" else "表盘详情", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (shown.protected) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(50), modifier = Modifier.testTag("market-detail-protected")) {
                            Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Default.Lock, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Text("受保护表盘", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }
                }
            }
        },
        text = {
            if (loading) {
                Box(Modifier.widthIn(min = 420.dp, max = 640.dp).heightIn(min = 360.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(42.dp).testTag("market-detail-loading"), strokeWidth = 4.dp)
                }
            } else {
                val shown = requireNotNull(detail)
                Column(Modifier.widthIn(min = 420.dp, max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .46f),
                        tonalElevation = 1.dp,
                    ) {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            MarketPreview(shown, api, Modifier.size(280.dp).clip(RoundedCornerShape(12.dp)))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (shown.author.isNotBlank()) MarketDetailInfo(Icons.Default.Person, "作者", shown.author)
                        if (shown.category.isNotBlank()) MarketDetailInfo(Icons.Default.Category, "分类", shown.category)
                        if (shown.screenType.isNotBlank()) MarketDetailInfo(Icons.Default.Smartphone, "屏幕", shown.screenType)
                        if (shown.version.isNotBlank()) MarketDetailInfo(Icons.Default.NewReleases, "版本", shown.version)
                        if (shown.createdAt.isNotBlank()) MarketDetailInfo(Icons.Default.CalendarToday, "发布", shown.createdAt)
                        MarketDetailInfo(Icons.Default.Download, "下载", shown.downloadCount.toString())
                        MarketDetailInfo(Icons.Default.Favorite, "收藏", shown.favoriteCount.toString())
                        MarketDetailInfo(Icons.Default.Visibility, "浏览", shown.views.toString())
                        if (shown.tags.isNotEmpty()) MarketDetailInfo(Icons.Default.Label, "标签", shown.tags.joinToString(", "))
                    }
                    if (shown.description.isNotBlank()) {
                        HorizontalDivider()
                        Text(shown.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            if (loading) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                val shown = requireNotNull(detail)
                Row(Modifier.fillMaxWidth().testTag("market-detail-actions"), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { runCatching { openExternal(WatchMarket.watchFaceUrl(shown.id)) }.onFailure { onError("无法打开浏览器：${it.message}") } }, modifier = Modifier.testTag("market-detail-open-web")) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("在浏览器打开")
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Button(onClick = onDownload, enabled = !downloading) {
                        if (downloading) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(if (downloading) "下载中…" else "下载 .watch")
                    }
                }
            }
        },
        dismissButton = {},
    )
}

@Composable private fun MarketDetailInfo(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Shown once a download finishes. The actions come from [marketWatchActions] so each platform
 * only offers what it can actually do.
 */
@Composable fun MarketDownloadDialog(
    download: MarketDownload,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
    onSaveAs: (Path) -> Unit,
    onCopyPath: () -> Unit,
    onError: (String) -> Unit = {},
) {
    val actions = remember { marketWatchActions() }
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("market-download-dialog"),
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("下载完成") } },
        text = { Column(Modifier.widthIn(min = 360.dp, max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(download.item.name, fontWeight = FontWeight.SemiBold)
            Text("已保存到：${download.path.toAbsolutePath()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (copied) Text("路径已复制到剪贴板。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider()
            Text("接下来要做什么？", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        } },
        confirmButton = { Button(onClick = onOpen, enabled = MarketDownloadAction.OPEN_IN_EDITOR in actions, modifier = Modifier.testTag("market-action-open")) { Text("打开编辑") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (MarketDownloadAction.REVEAL_IN_FILE_MANAGER in actions) TextButton(onClick = onReveal, modifier = Modifier.testTag("market-action-reveal")) { Text(marketRevealLabel()) }
                if (MarketDownloadAction.SAVE_AS in actions) TextButton(onClick = {
                    runCatching { chooseSavePath(null, download.path, download.item.name, allowExisting = true) }
                        .onSuccess { destination -> if (destination != null) onSaveAs(destination) }
                        .onFailure { onError("无法打开保存对话框：${it.message}") }
                }, modifier = Modifier.testTag("market-action-save-as")) { Text("另存为…") }
                if (MarketDownloadAction.COPY_PATH in actions) TextButton(onClick = { onCopyPath(); copied = true }, modifier = Modifier.testTag("market-action-copy-path")) { Text("复制路径") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
