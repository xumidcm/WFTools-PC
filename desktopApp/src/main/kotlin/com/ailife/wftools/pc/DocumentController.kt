package com.ailife.wftools.pc

import androidx.compose.runtime.*
import com.ailife.wftools.pc.core.*
import com.ailife.wftools.pc.preview.*
import com.ailife.wftools.pc.render.PreviewCaptureExporter
import com.ailife.wftools.pc.render.RenderSession
import com.ailife.wftools.pc.render.ThumbnailExporter
import com.ailife.wftools.agent.model.AgentFeatureSettings
import com.ailife.wftools.agent.storage.AgentConversationRepository
import com.ailife.wftools.agent.storage.AgentProviderStore
import com.ailife.wftools.agent.workspace.WorkspaceManager
import com.ailife.wftools.pc.platform.appData
import com.ailife.wftools2.domain.editor.watchmaker.WmScriptRuntime
import com.ailife.wftools2.domain.editor.watchmaker.WmTagsData
import com.ailife.wftools2.domain.editor.watchmaker.WmWatchfaceProject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class AssetItem(val path: Path, val relative: String, val kind: String)
enum class NoticeAction { SCRIPT }
internal fun noticeActions(action: NoticeAction?): List<String> = if (action == NoticeAction.SCRIPT) listOf("查看脚本", "确定") else listOf("确定")
data class DocumentView(
    val title: String, val source: Path?, val layers: List<EditorLayer>, val selection: LayerSelection,
    val project: WmWatchfaceProject, val revision: Long, val dirty: Boolean, val canUndo: Boolean, val canRedo: Boolean,
    val assets: List<AssetItem>, val draftError: String?, val identity: String,
)

/** All mutable DocumentSession access is serialized here and performed off the UI thread. */
class DocumentController private constructor(
    val session: DocumentSession, private val store: WatchDocumentStore, private val scope: CoroutineScope,
    val render: RenderSession, initial: DocumentView, initialPreview: PreviewPreferences, private val preferences: EditorPreferences,
    private val videoImportPreparer:VideoImportPreparer,
) {
    private val mutex = Mutex()
    private var closed = false
    private var agentWriteLock by mutableStateOf(false)
    private var pending by mutableStateOf(0)
    private var blocking by mutableStateOf(0)
    private var progressing by mutableStateOf(0)
    var view by mutableStateOf(initial); private set
    var windowId: String = session.id
        internal set
    val busy get() = pending > 0
    val showProgress get() = progressing > 0
    val editingBlocked get() = blocking > 0 || agentWriteLock || closed
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var noticeAction by mutableStateOf<NoticeAction?>(null)
        private set
    var externalModification by mutableStateOf(false)
    var reservedSaveIdentity by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<String?>(null)
    var preview by mutableStateOf(initialPreview)
    var window: Window? = null
    var focus by mutableStateOf(EditorFocus.OTHER)
    var query by mutableStateOf("")
    var typeFilter by mutableStateOf("")
    var requestedInspectorTab by mutableStateOf<String?>(null)
    var agentFullscreen by mutableStateOf(false)
    var globalScriptEditorOpen by mutableStateOf(false)
    var globalScriptDraft by mutableStateOf(initial.project.globalScript); private set
    var globalScriptDraftError by mutableStateOf<String?>(null); private set
    var globalScriptDraftValidating by mutableStateOf(false); private set
    private var globalScriptValidationToken = 0L
    val hasGlobalScriptDraft get() = globalScriptDraft != view.project.globalScript
    val hasUnsavedChanges get() = view.dirty || hasGlobalScriptDraft
    val visible get() = visibleLayers(view.layers, query, typeFilter)
    var closing by mutableStateOf(false)
    var commitFocusedField: (() -> Unit)? = null
    var transientAttributes by mutableStateOf<Map<String,Map<String,String>>>(emptyMap()); private set
    private var previewToken = 0L
    private data class NumericGesture(val token: Long,val key: String,val base: Map<String,String>,val evaluated: Map<String,Double>,val relative: Boolean)
    private var numericGesture: NumericGesture? = null
    val previewSnapshot: PreviewDocumentSnapshot get() {
        val updates = transientAttributes
        if(updates.isEmpty()) return PreviewDocumentSnapshot(view.project,view.layers,view.selection)
        val layers = view.layers.map { layer -> updates[layer.id]?.let { layer.copy(attrs = layer.attrs+it) } ?: layer }
        val project = view.project.copy(wmLayers = view.project.wmLayers.mapIndexed { index,layer ->
            updates[view.layers[index].id]?.entries?.fold(layer) { current,(key,value) -> current.withAttr(key,value) } ?: layer
        })
        return PreviewDocumentSnapshot(project,layers,view.selection)
    }
    fun previewNumeric(ids: Set<String>, key: String, value: Float, default: String, relative: Boolean = false) {
        if(editingBlocked) return
        var gesture = numericGesture
        if(gesture == null || gesture.key != key || gesture.base.keys != ids || gesture.relative != relative) {
            val base = previewSnapshot.layers.filter { it.id in ids }.associate { it.id to it.attrs[key].orEmpty().ifBlank { default } }
            gesture = NumericGesture(++previewToken,key,base,base.mapValues { (_,raw) -> raw.toDoubleOrNull() ?: render.evaluate(raw).toDoubleOrNull() ?: 0.0 },relative)
            numericGesture = gesture
        }
        val active = requireNotNull(gesture)
        transientAttributes = active.base.mapValues { (id,raw) -> mapOf(key to if(relative) offsetEditorExpression(raw,value) else if(raw.toDoubleOrNull() != null) decimal(value.toDouble()) else "($raw)+(${decimal(value-active.evaluated.getValue(id))})") }
    }
    fun commitNumeric(onComplete: (Boolean) -> Unit = {}) {
        numericGesture = null
        val updates = transientAttributes
        if(updates.isEmpty()) { onComplete(true); return }
        commitLayerAttributes(updates,onComplete)
    }
    fun commitLayerAttributes(updates: Map<String,Map<String,String>>, onComplete: (Boolean) -> Unit) {
        if(closed) { onComplete(false); return }
        val token = ++previewToken
        transientAttributes = updates
        fun finish(success: Boolean) { if(token == previewToken) transientAttributes = emptyMap(); onComplete(success) }
        task("正在应用图层变换…",onSuccess = { finish(true) },onFailure = { finish(false) }) { session.setLayerAttributes(updates) }
    }
    fun previewLayerText(ids: Set<String>, text: String) {
        if (editingBlocked || ids.isEmpty()) return
        transientAttributes = ids.associateWith { mapOf("text" to text) }
    }
    fun commitLayerText(ids: Set<String>, text: String, onComplete: (Boolean) -> Unit = {}) {
        if (editingBlocked || ids.isEmpty()) return
        commitLayerAttributes(ids.associateWith { mapOf("text" to text) }, onComplete)
    }
    fun openGlobalScriptEditor() {
        if(!hasGlobalScriptDraft) globalScriptDraft=view.project.globalScript
        globalScriptEditorOpen=true
    }
    fun updateGlobalScriptDraft(text: String) { globalScriptDraft=text;globalScriptDraftError=null }
    fun discardGlobalScriptDraft(closeEditor: Boolean = false) {
        globalScriptDraft=view.project.globalScript;globalScriptDraftError=null
        if(closeEditor)globalScriptEditorOpen=false
    }
    fun validateGlobalScriptDraft(save: Boolean = false,onComplete: (Boolean)->Unit = {}) {
        if(closed) { onComplete(false);return }
        val source=globalScriptDraft
        if(save&&!hasGlobalScriptDraft) { onComplete(true);return }
        val token=++globalScriptValidationToken
        pending++;globalScriptDraftValidating=true
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            val result=withContext(Dispatchers.Default){WmScriptRuntime().validateScript(source)}
            if(token!=globalScriptValidationToken) { pending--;return@launch }
            globalScriptDraftError=result;globalScriptDraftValidating=false
            if(result!=null) {
                if(save)error=result
                pending--;onComplete(false);return@launch
            }
            if(save&&globalScriptDraft==source) task("正在保存全局脚本…",onSuccess={onComplete(true)},onFailure={onComplete(false)}) { session.setGlobalScript(source) }
            else onComplete(!save)
            pending--
        }
    }
    fun saveGlobalScriptDraft(onComplete: (Boolean)->Unit = {})=validateGlobalScriptDraft(save=true,onComplete=onComplete)
    fun showNotice(message:String, action:NoticeAction?=null) { notice=message; noticeAction=action }
    fun clearNotice() { notice=null; noticeAction=null }
    fun createLayerFromAsset(asset: AssetItem,x: Double = 0.0,y: Double = 0.0) = task("正在创建素材图层…",onSuccess = { showNotice("已使用素材创建图层。") }) {
        val attribute = assetTargetAttribute(asset) ?: throw WatchDocumentException.InvalidCommand("此文件是配套资源，不能直接创建图层。")
        val attrs = linkedMapOf("x" to decimal(x),"y" to decimal(y),"rotation" to "0","opacity" to "100","display" to "bd","alignment" to "cc","anim_scale_x" to "100","anim_scale_y" to "100","color" to "ffffff")
        val type = when {
            attribute == "font" -> { attrs["font"] = (if(asset.kind=="fonts_bm")"bm:" else "")+asset.relative.substringAfter('/').substringBeforeLast('.'); attrs["text"]="{dh}:{dmz}";attrs["text_size"]="40";"text" }
            asset.kind == "videos" -> { attrs["path"]=asset.relative.substringAfter('/');attrs["width"]="512";attrs["height"]="512";"video" }
            else -> {
                val bounds = com.ailife.wftools.pc.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                com.ailife.wftools.pc.graphics.BitmapFactory.decodeFile(asset.path.toString(),bounds)
                attrs["path"]=asset.relative.substringAfter('/');attrs["width"]=bounds.outWidth.coerceAtLeast(1).toString();attrs["height"]=bounds.outHeight.coerceAtLeast(1).toString()
                if(asset.relative.endsWith(".gif",true))"image_gif" else "image"
            }
        }
        fun escaped(value: String)=value.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;")
        session.pasteLayers("<WFToolsLayers version=\"1\"><Layer type=\"$type\" "+attrs.entries.joinToString(" "){(key,value)->"$key=\"${escaped(value)}\""}+"/></WFToolsLayers>")
    }
    fun deleteLibraryAsset(asset: AssetItem) = assetCommand("正在删除素材…",onSuccess = { showNotice("素材已删除，可使用撤销恢复。") }) { deleteAsset(asset.relative) }
    private fun decimal(value: Double) = java.math.BigDecimal.valueOf(value).setScale(5,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    val preferenceIdentity get() = view.identity
    fun afterPending(action: () -> Unit) { scope.launch { while (busy) delay(10); action() } }

    fun command(label: String = "正在处理…", refreshAssets: Boolean = false, block: DocumentSession.() -> Unit) = task(label, refreshAssets, block = { session.block() })
    fun assetCommand(label: String = "正在导入素材…", onSuccess: () -> Unit = {}, longOperation:Boolean=false, block: DocumentSession.() -> Unit) = task(label, refreshAssets = true, blockingOperation = true, showProgress=longOperation, onSuccess = onSuccess, block = { session.block() })
    fun importExternalAssets(paths:List<Path>,createLayers:Boolean=true,x:Double=0.0,y:Double=0.0,label:String="正在导入素材…",onSuccess:()->Unit={}) =
        assetCommand(label,onSuccess,longOperation=VideoImportPreparer.containsVideo(paths)) {
            videoImportPreparer.prepare(paths).use{prepared->importAssets(prepared.paths,createLayers,x,y)}
        }
    fun replaceExternalAssets(ids:Set<String>,paths:List<Path>,attribute:String="path",label:String="正在替换素材…",onSuccess:()->Unit={}) =
        assetCommand(label,onSuccess,longOperation=VideoImportPreparer.containsVideo(paths)) {
            videoImportPreparer.prepare(paths).use{prepared->replaceAssetForLayers(ids,prepared.paths,attribute)}
        }
    fun importExternalWatchAssets(source:Path,onSuccess:()->Unit={}) = assetCommand("正在导入表盘素材…",onSuccess,longOperation=true) {
        val prepared=mutableListOf<PreparedVideoImports>()
        try { importWatchAssets(source){asset->videoImportPreparer.prepare(listOf(asset)).also(prepared::add).paths.single()} }
        finally { prepared.asReversed().forEach(PreparedVideoImports::close) }
    }
    fun refreshFromPreview() = task("", block = {})
    /** Publish an Agent transaction before it releases the editor write barrier. */
    fun refreshFromAgent() {
        val next = withAgentSession { snapshot(session, view, refreshAssets = true) }
        view = next
        if (!hasGlobalScriptDraft) globalScriptDraft = next.project.globalScript
    }
    /** Shares the document mutex with normal editor commands. Agent calls originate off the UI thread. */
    fun <T> withAgentSession(block: () -> T): T = runBlocking { mutex.withLock { withContext(Dispatchers.IO) { block() } } }
    fun beginAgentWrite() { agentWriteLock = true }
    fun endAgentWrite() { try { refreshFromAgent() } finally { agentWriteLock = false } }
    private fun task(label: String, refreshAssets: Boolean = false, blockingOperation: Boolean = false, showProgress:Boolean=false, allowAgentWrite: Boolean = false, onSuccess: () -> Unit = {}, onFinished: () -> Unit = {}, onFailure: (Exception) -> Unit = {}, block: suspend () -> Unit) {
        if(closed || (agentWriteLock && !allowAgentWrite)) return
        pending++; if(blockingOperation) blocking++; if(showProgress){progressing++;status=label}; error = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var success = false
            try {
                // Acquire in caller order before dispatching IO; a faster worker cannot overtake an earlier command.
                mutex.withLock {
                    val preserveScriptDraft=hasGlobalScriptDraft
                    val next=withContext(Dispatchers.IO) { block(); snapshot(session, view, refreshAssets) }
                    view=next
                    if(!preserveScriptDraft)globalScriptDraft=next.project.globalScript
                }
                success = true
            } catch (e: Exception) { error = e.message ?: "操作失败，请重试。"; if(e is WatchDocumentException.ExternalModification) externalModification = true; onFailure(e) }
            finally { pending--; if(blockingOperation) blocking--; if(showProgress)progressing--;if(progressing==0)status=null; onFinished() }
            if(success) onSuccess()
        }
    }
    fun save(target: Path, onSuccess: () -> Unit = {}) {
        if(closed) return
        reservedSaveIdentity = canonicalIdentity(target)
        task("正在保存 ${target.fileName}…", blockingOperation = true, onSuccess = onSuccess, onFinished = { reservedSaveIdentity = null }) {
            val derived = if(session.hasUnsavedChanges) ThumbnailExporter.generate(session.toWatchProject(), session.workDir.toFile()) else emptyMap()
            store.save(session, target, derivedResources = derived)
        }
    }
    fun exportScreenshot(target: Path) = exportCapture("正在生成截图…", "截图已导出：${target.fileName}", target, showProgress = false) { project, state, bright, theme ->
        PreviewCaptureExporter.capturePng(project,session.workDir.toFile(),state,bright,theme)
    }
    fun exportGif(target: Path, durationMillis: Long = PreviewCaptureExporter.DEFAULT_DURATION_MILLIS, fps: Int = PreviewCaptureExporter.DEFAULT_FPS) =
        exportCapture("正在录制 GIF…", "GIF 已导出：${target.fileName}", target, showProgress = true) { project, state, bright, theme ->
            PreviewCaptureExporter.captureGif(project,session.workDir.toFile(),state,bright,theme,durationMillis,fps)
        }
    fun exportProtected(target: Path, onSuccess: () -> Unit = {}) = fileExport("正在生成受保护表盘…", "受保护表盘已导出：${target.fileName}", target, onSuccess) {
        val derived = if(session.hasUnsavedChanges) ThumbnailExporter.generate(session.toWatchProject(),session.workDir.toFile()) else emptyMap()
        store.exportProtected(session,target,derived)
    }
    private fun exportCapture(label: String, successMessage: String, target: Path, showProgress: Boolean, capture: suspend (WmWatchfaceProject,WmTagsData.WmPreviewState,Boolean,Int) -> ByteArray) {
        if(closed || busy) return
        val snapshot = previewSnapshot
        val state = render.frame
        val bright = render.isBright
        val theme = render.themeIndex
        pending++; blocking++; if(showProgress) { progressing++; status = label }; error = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                mutex.withLock {
                    val bytes = capture(snapshot.project,state,bright,theme)
                    withContext(Dispatchers.IO) { writeExportAtomically(target,bytes) }
                }
                showNotice(successMessage)
            } catch(e: Exception) { error = "导出失败：${e.message}" }
            finally { pending--; blocking--; if(showProgress) { progressing--; if(progressing==0)status=null } }
        }
    }
    private fun fileExport(label: String, successMessage: String, target: Path, onSuccess: () -> Unit, block: suspend () -> Unit) {
        if(closed || busy) return
        pending++; blocking++; progressing++; status = label; error = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { mutex.withLock { withContext(Dispatchers.IO) { block() } }; showNotice(successMessage); onSuccess() }
            catch(e: Exception) { error = "导出失败：${e.message}" }
            finally { pending--; blocking--; progressing--;if(progressing==0)status=null }
        }
    }
    private fun writeExportAtomically(target: Path, bytes: ByteArray) {
        val absolute = target.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw IllegalArgumentException("无效的导出路径。")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent,".wftools-export-",".tmp")
        try {
            Files.write(temporary,bytes)
            try { Files.move(temporary,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            catch(_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary,absolute,StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
    fun copy(onCopied: (String) -> Unit) {
        if(closed) return
        pending++
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { onCopied(mutex.withLock { withContext(Dispatchers.IO) { session.copySelection() } }) }
            catch (e: Exception) { error = e.message }
            finally { pending-- }
        }
    }
    fun finish(discard: Boolean, onClosed: () -> Unit) {
        if (busy || closed) return
        pending++; blocking++; status = "正在关闭…"
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                mutex.withLock { withContext(Dispatchers.IO) {
                    preferences.savePreview(view.identity,preview)
                    // Closing with "discard" only means "do not commit the source file".
                    // The session journal remains the durable last-session draft so the
                    // launcher can continue it next time. Explicit dismissal from the
                    // recovery list is the operation that writes the discarded marker.
                    session.close()
                    render.close()
                } }
                closed = true
                onClosed()
            } catch (e: Exception) { error = "关闭失败：${e.message}。窗口已保留，请重试。" }
            finally { pending--; blocking--; status = null }
        }
    }
    fun reload(preferences: EditorPreferences, onReloaded: (DocumentController) -> Unit) {
        val source = view.source ?: return
        if(busy || closed) return
        pending++; blocking++; status = "正在重新加载原文件…"; externalModification = false; error = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val replacement = mutex.withLock { withContext(Dispatchers.IO) {
                    // Prepare first so a corrupt external replacement does not destroy the open local draft.
                    val fresh = store.open(source)
                    val next = try { create(fresh,store,scope,preferences) } catch(e: Exception) { runCatching { fresh.close() }; throw e }
                    try { store.discardRecovery(session.id) } catch(e: Exception) { next.render.close(); fresh.close(); throw e }
                    runCatching { session.close() }; runCatching { render.close() }
                    next
                } }
                closed = true; replacement.windowId = windowId; replacement.preview = preview
                onReloaded(replacement)
            } catch(e: Exception) { error = "重新加载失败，当前窗口和修改已保留：${e.message}" }
            finally { pending--; blocking--; status = null }
        }
    }
    companion object {
        suspend fun create(session: DocumentSession, store: WatchDocumentStore, scope: CoroutineScope, preferences: EditorPreferences,videoImportPreparer:VideoImportPreparer=VideoImportPreparer()): DocumentController = withContext(Dispatchers.IO) {
            val initial = snapshot(session)
            val identity = initial.source?.let(::canonicalIdentity) ?: "draft:${session.id}"
            DocumentController(session, store, scope, RenderSession(session.workDir.toFile()), initial, preferences.preview(identity), preferences,videoImportPreparer)
        }
        private fun snapshot(session: DocumentSession, previous: DocumentView? = null, refreshAssets: Boolean = true): DocumentView {
            val unchanged = previous?.revision == session.revision
            val project = if(unchanged) previous!!.project else session.toWatchProject()
            val layers = if(unchanged) previous!!.layers else session.layers
            val assets = if(previous != null && !refreshAssets) previous.assets else Files.walk(session.workDir).use { stream -> stream.filter(Files::isRegularFile).map { path ->
                val relative = session.workDir.relativize(path).toString().replace('\\','/')
                AssetItem(path, relative, relative.substringBefore('/'))
            }.filter { it.kind in setOf("images", "videos", "fonts", "fonts_bm", "sfx") }.sorted(compareBy<AssetItem> { it.relative }).toList() }
            return DocumentView(project.title.ifBlank { "未命名表盘" }, session.sourcePath, layers, session.selection, project, session.revision,
                session.hasUnsavedChanges, session.canUndo, session.canRedo, assets, session.lastDraftError,
                if(previous != null && previous.source == session.sourcePath) previous.identity else session.sourcePath?.let(::canonicalIdentity) ?: "draft:${session.id}")
        }
    }
}

class EditorApplicationModel(val scope: CoroutineScope, val preferences: EditorPreferences = EditorPreferences(), val store: WatchDocumentStore = WatchDocumentStore(), private val agentRoot: Path = appData().resolve("agent")) {
    val documents = mutableStateListOf<DocumentController>()
    private val agentModels = mutableMapOf<String, DesktopAgentViewModel>()
    internal fun agentModel(doc: DocumentController): DesktopAgentViewModel = agentModels.getOrPut(doc.session.id) { DesktopAgentViewModel(this, doc) }
    var canvasInput by mutableStateOf(preferences.canvasInput())
    var agentSettings by mutableStateOf(preferences.agentSettings())
        private set
    /** These stores stay lazy so a disabled Agent never creates a backend or touches its files. */
    val agentProviderStore: AgentProviderStore by lazy { AgentProviderStore(agentRoot) }
    val agentConversationRepository: AgentConversationRepository by lazy { AgentConversationRepository(agentRoot) }
    val agentWorkspaceManager: WorkspaceManager by lazy { WorkspaceManager(agentRoot.resolve("runtime")) }
    var recovery by mutableStateOf<List<RecoveryDraft>>(emptyList())
    var launcher by mutableStateOf(true)
    var launcherWindow: Window? = null
    var copiedScriptContext: Pair<String,String>? = null
    var recentProjects by mutableStateOf<List<RecentProject>>(emptyList()); private set
    var lastSession by mutableStateOf<List<Path>>(emptyList()); private set
    var lastActivePath by mutableStateOf<Path?>(null); private set
    var lastActiveDraftId by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    private val openMutex = Mutex()
    private val agentSaveMutex = Mutex()
    fun updateAgentSettings(value: AgentFeatureSettings) {
        agentSettings = value
        if (!value.enabled) {
            agentModels.values.forEach(DesktopAgentViewModel::close); agentModels.clear()
            documents.forEach { it.agentFullscreen = false; it.requestedInspectorTab = "属性" }
        }
        scope.launch { agentSaveMutex.withLock { runCatching { withContext(Dispatchers.IO) { preferences.saveAgentSettings(agentSettings) } } } }
    }
    init { scope.launch { withContext(Dispatchers.IO) { listOf(preferences.recentProjects(),preferences.lastSession(),preferences.lastActive(),preferences.lastActiveDraftId()) }.let { values ->
        @Suppress("UNCHECKED_CAST") val recent = values[0] as List<RecentProject>
        @Suppress("UNCHECKED_CAST") val last = values[1] as List<Path>
        val active = values[2] as Path?; val draftId = values[3] as String?
        recentProjects = recent; lastActivePath = active ?: last.lastOrNull(); lastSession = listOfNotNull(lastActivePath); lastActiveDraftId = draftId
    } } }
    fun recordOpened(doc: DocumentController) { val source = doc.view.source; scope.launch {
        try {
            markActive(doc)
            recentProjects = withContext(Dispatchers.IO) {
                if (source != null) preferences.recordRecent(source,doc.view.title)
                else preferences.recordRecentDraft(doc.session.id,doc.view.title)
            }
        } catch(e: Exception) { doc.showNotice("项目已打开，但最近项目记录失败：${e.message}") }
    } }
    fun continueSession() {
        val active = lastActivePath ?: lastSession.lastOrNull()
        val draft = recovery.firstOrNull { it.id == lastActiveDraftId } ?: recovery.firstOrNull { draft ->
            val draftSource = draft.sourcePath
            active != null && draftSource != null && canonicalIdentity(draftSource) == canonicalIdentity(active)
        }
        if (draft != null) recover(draft.id) else open(active?.let(::listOf).orEmpty())
    }
    fun markActive(doc: DocumentController) {
        val source = doc.view.source
        lastActivePath = source
        lastActiveDraftId = doc.session.id
        lastSession = listOfNotNull(source)
        scope.launch { runCatching { withContext(Dispatchers.IO) { preferences.saveLastActive(source,doc.session.id) } } }
    }
    val lastActiveTitle: String?
        get() = recentProjects.firstOrNull { recent ->
            (recent.draftId != null && recent.draftId == lastActiveDraftId) ||
                (recent.draftId == null && lastActivePath != null && runCatching { canonicalIdentity(Path.of(recent.path)) == canonicalIdentity(lastActivePath!!) }.getOrDefault(false))
        }?.title ?: recovery.firstOrNull { it.id == lastActiveDraftId }?.title
    val lastActiveDisplayName: String?
        get() = lastActivePath?.fileName?.toString() ?: lastActiveTitle
    val canContinueSession: Boolean get() = lastActivePath != null || lastActiveDraftId != null
    private fun isLastActive(recent: RecentProject): Boolean =
        (recent.draftId != null && recent.draftId == lastActiveDraftId) ||
            (recent.draftId == null && lastActivePath != null && runCatching { canonicalIdentity(Path.of(recent.path)) == canonicalIdentity(lastActivePath!!) }.getOrDefault(false))
    private fun clearLastActive() {
        lastActivePath = null; lastActiveDraftId = null; lastSession = emptyList()
        scope.launch { runCatching { withContext(Dispatchers.IO) { preferences.saveLastActive(null, null) } } }
    }
    fun removeRecentRecord(recent: RecentProject) = scope.launch {
        try {
            if (isLastActive(recent)) clearLastActive()
            recentProjects = withContext(Dispatchers.IO) { preferences.removeRecent(recent) }
        } catch (e: Exception) { error = "无法删除最近记录：${e.message}" }
    }
    fun deleteRecentWatch(recent: RecentProject) = scope.launch {
        try {
            val draftId = recent.draftId
            if (draftId != null) {
                withContext(Dispatchers.IO) {
                    store.discardRecovery(draftId)
                    preferences.removeRecent(recent)
                }
            } else {
                val source = Path.of(recent.path)
                documents.firstOrNull { doc -> doc.view.source?.let(::canonicalIdentity) == canonicalIdentity(source) }?.let {
                    error = "该表盘正在编辑，请先关闭编辑窗口后再删除。"; return@launch
                }
                withContext(Dispatchers.IO) {
                    if (!Files.isRegularFile(source)) throw IllegalStateException("找不到原始表盘文件：$source")
                    Files.delete(source)
                    recovery.filter { draft -> draft.sourcePath != null && canonicalIdentity(draft.sourcePath!!) == canonicalIdentity(source) }
                        .forEach { draft -> runCatching { store.discardRecovery(draft.id) } }
                    preferences.removeRecent(recent)
                }
            }
            if (isLastActive(recent)) clearLastActive()
            recentProjects = withContext(Dispatchers.IO) { preferences.recentProjects() }
            refreshRecovery()
        } catch (e: Exception) { error = "无法删除表盘：${e.message}" }
    }
    fun focus(doc: DocumentController) { markActive(doc); doc.window?.apply { isVisible = true; if (this is java.awt.Frame) extendedState = extendedState and java.awt.Frame.ICONIFIED.inv(); toFront(); requestFocus() } }
    fun refreshRecovery() = scope.launch {
        try { recovery = withContext(Dispatchers.IO) { store.listRecoveryDrafts() }.filter { draft -> documents.none { it.session.id == draft.id } } }
        catch (e: Exception) { error = e.message }
    }
    fun dismiss(id: String) = scope.launch {
        try { withContext(Dispatchers.IO) { store.discardRecovery(id) }; refreshRecovery() }
        catch (e: Exception) { error = "无法放弃恢复草稿：${e.message}" }
    }
    fun newDocument() = openSessions(listOf(null), null)
    fun recover(id: String) = openSessions(emptyList(), id)
    fun open(paths: List<Path>) {
        if (paths.isEmpty()) { launcher = true; launcherWindow?.apply { toFront(); requestFocus() }; return }
        if (classifyDrop(paths) != DropBatch.WATCHES) { error = "请选择 .watch 表盘文件；表盘与素材不能混合打开。"; launcher = true; return }
        openSessions(paths, null)
    }
    private fun openSessions(paths: List<Path?>, recoveryId: String?) = scope.launch {
        openMutex.withLock {
            error = null
            val failures = mutableListOf<String>()
            val items = if (recoveryId != null) listOf<Path?>(null) else paths
            items.forEachIndexed { index, path ->
                status = "正在打开 ${index + 1}/${items.size}：${path?.fileName ?: if (recoveryId != null) "恢复草稿" else "新建表盘"}"
                try {
                    val identity = withContext(Dispatchers.IO) { (path ?: recoveryId?.let { id -> recovery.firstOrNull { it.id == id }?.sourcePath })?.let(::canonicalIdentity) }
                    val existing = documents.firstOrNull { (recoveryId != null && it.session.id == recoveryId) || (identity != null && (it.view.identity == identity || it.reservedSaveIdentity == identity)) }
                    if (existing != null) {
                        focus(existing)
                        if(path != null && existing.reservedSaveIdentity == identity && existing.view.identity != identity) existing.afterPending {
                            if(existing.view.identity == identity) focus(existing) else open(listOf(path))
                        }
                        if(recoveryId != null && existing.session.id != recoveryId) failures.add("此草稿的原文件已在另一个窗口打开。请先关闭该窗口，再恢复草稿；草稿仍然保留。")
                    }
                    else {
                        val session = withContext(Dispatchers.IO) { when { recoveryId != null -> store.recover(recoveryId); path == null -> store.newDocument(DesktopI18n.text("新建表盘")); else -> store.open(path) } }
                        val doc = DocumentController.create(session, store, scope, preferences)
                        documents.add(doc)
                        recordOpened(doc)
                    }
                } catch (e: Exception) { failures.add("${path?.fileName ?: "表盘"}：${e.message}") }
            }
            status = null
            if (failures.isNotEmpty()) { error = "${failures.size} 个文件打开失败：\n${failures.joinToString("\n")}"; launcher = true }
            else if (documents.isNotEmpty()) launcher = false
            refreshRecovery()
        }
    }
    fun closed(doc: DocumentController) { agentModels.remove(doc.session.id)?.close(); documents.remove(doc); if (documents.isEmpty()) launcher = true; refreshRecovery() }
    fun reload(doc: DocumentController) = doc.reload(preferences) { replacement ->
        agentModels.remove(doc.session.id)?.close()
        val index = documents.indexOf(doc)
        if(index >= 0) documents[index] = replacement
        refreshRecovery()
    }
}
