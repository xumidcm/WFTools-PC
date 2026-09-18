package com.ailife.wftools.pc

import com.ailife.wftools.agent.model.AgentFeatureSettings
import com.ailife.wftools.agent.model.AgentBashMode
import com.ailife.wftools.pc.platform.appData
import com.ailife.wftools.pc.preview.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.util.Properties

enum class ThemePreference { SYSTEM, LIGHT, DARK }
data class PanelPreferences(val left: Float = 272f, val right: Float = 348f, val bottom: Float = 180f)
data class WindowPreferences(val width: Int = 1280, val height: Int = 820)
data class CanvasInputPreferences(
    val middleButtonPan: Boolean = true,
    val rightButtonPan: Boolean = true,
    val wheelScalesSelectedLayer: Boolean = true,
    val snapping:Boolean=true,
    val showTransformHandles:Boolean=true,
    val showOperationStatus:Boolean=true,
)
data class RecentProject(val path: String,val title: String,val openedAt: Long,val draftId: String? = null)

/**
 * 受保护表盘的解锁与编辑开关。
 *
 * [unlocked] 由两级彩蛋触发（先连点左上角图标，再连点标题），决定设置里是否出现这一项；
 * [allowEditing] 才是真正让受保护表盘直接打开编辑的开关。未解锁时 [allowEditing] 一律按
 * false 处理，即使配置文件被手工改过也不会生效。
 */
data class ProtectedFacePreferences(val unlocked: Boolean = false, val allowEditing: Boolean = false) {
    /** 实际生效的编辑许可：解锁是硬前提。 */
    val effectiveAllowEditing: Boolean get() = unlocked && allowEditing
}


data class PreviewPreferences(val device: DevicePreviewSettings = DevicePreviewSettings(), val workspace: WorkspaceViewState = WorkspaceViewState(), val simulation: Map<String, String> = emptyMap(), val snapping: Boolean = true,
    val operationMode: CanvasOperationMode = CanvasOperationMode.DIRECT_DRAG, val devicePreviewEnabled: Boolean = false, val showTransformHandles: Boolean = true,
    val showOperationStatus: Boolean = true)

/** Only application data is written here. No document or undo state is accepted. */
class EditorPreferences(private val directory: Path = appData().resolve("preferences")) {
    private fun read(name: String): Properties = Properties().apply {
        runCatching { Files.newInputStream(directory.resolve(name)).use { load(it) } }
    }
    private fun write(name: String, values: Properties) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "preferences-", ".tmp")
        Files.newOutputStream(temp).use { values.store(it, "WFTools-PC user preferences") }
        Files.move(temp, directory.resolve(name), REPLACE_EXISTING, ATOMIC_MOVE)
    }
    private fun key(identity: String) = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) } + ".properties"
    fun recentProjects(): List<RecentProject> = read("recent.properties").let { p -> (0 until p.int("count",0,0,30)).mapNotNull { index ->
        p.getProperty("$index.path")?.let { path ->
            val title = p.getProperty("$index.title") ?: path.substringAfterLast('/', path.substringAfterLast('\\'))
            RecentProject(path,title,p.getProperty("$index.time")?.toLongOrNull() ?: 0L,p.getProperty("$index.draftId")?.takeIf(String::isNotBlank))
        }
    } }
    private fun writeRecent(recent: List<RecentProject>) {
        write("recent.properties",Properties().apply {
            setProperty("count",recent.size.toString())
            recent.forEachIndexed { i,r ->
                setProperty("$i.path",r.path); setProperty("$i.title",r.title); setProperty("$i.time",r.openedAt.toString())
                r.draftId?.let { setProperty("$i.draftId",it) }
            }
        })
    }
    @Synchronized fun recordRecent(path: Path,title: String): List<RecentProject> {
        val identity = canonicalIdentity(path)
        val recent = (listOf(RecentProject(path.toAbsolutePath().normalize().toString(),title,System.currentTimeMillis()))+recentProjects().filterNot { it.draftId == null && canonicalIdentity(Path.of(it.path)) == identity }).take(20)
        writeRecent(recent)
        return recent
    }
    @Synchronized fun recordRecentDraft(id: String,title: String): List<RecentProject> {
        require(id.isNotBlank()) { "草稿标识不能为空。" }
        val recent = (listOf(RecentProject("draft:$id",title,System.currentTimeMillis(),id)) + recentProjects().filterNot { it.draftId == id }).take(20)
        writeRecent(recent)
        return recent
    }
    @Synchronized fun removeRecent(target: RecentProject): List<RecentProject> {
        val remaining = recentProjects().filterNot { current ->
            if (target.draftId != null) current.draftId == target.draftId
            else current.draftId == null && canonicalIdentity(Path.of(current.path)) == canonicalIdentity(Path.of(target.path))
        }
        writeRecent(remaining)
        return remaining
    }
    fun lastSession(): List<Path> = read("session.properties").let { p -> (0 until p.int("count",0,0,64)).mapNotNull { p.getProperty("$it.path")?.let(Path::of) } }
    @Synchronized fun recordSession(paths: List<Path>) { if(paths.isNotEmpty()) write("session.properties",Properties().apply { setProperty("count",paths.size.toString()); paths.forEachIndexed { i,p -> setProperty("$i.path",p.toString()) } }) }
    fun lastActive(): Path? = read("last-active.properties").getProperty("path")?.let { Path.of(it) }
    fun lastActiveDraftId(): String? = read("last-active.properties").getProperty("draftId")?.takeIf { it.isNotBlank() }
    @Synchronized fun saveLastActive(path: Path?, draftId: String? = null) {
        write("last-active.properties",Properties().apply {
            path?.let { setProperty("path",it.toAbsolutePath().normalize().toString()) }
            draftId?.takeIf { it.isNotBlank() }?.let { setProperty("draftId",it) }
        })
    }
    fun theme(): ThemePreference = runCatching { ThemePreference.valueOf(read("application.properties").getProperty("theme", "SYSTEM")) }.getOrDefault(ThemePreference.SYSTEM)
    fun savedLanguage(): DesktopLanguage? = read("application.properties").getProperty("language")?.let { value -> runCatching { DesktopLanguage.valueOf(value) }.getOrNull() }
    fun panels(): PanelPreferences = read("application.properties").let { PanelPreferences(it.float("left", 272f, 180f, 520f), it.float("right", 348f, 240f, 560f), it.float("bottom", 180f, 160f, 600f)) }
    fun window(): WindowPreferences = read("application.properties").let { WindowPreferences(it.int("width", 1280, 640, 7680), it.int("height", 820, 420, 4320)) }
    fun canvasInput(): CanvasInputPreferences = read("application.properties").let { p -> CanvasInputPreferences(
        middleButtonPan=p.getProperty("middleButtonPan")?.toBooleanStrictOrNull() ?: true,
        rightButtonPan=p.getProperty("rightButtonPan")?.toBooleanStrictOrNull() ?: true,
        wheelScalesSelectedLayer=p.getProperty("wheelScalesSelectedLayer")?.toBooleanStrictOrNull() ?: true,
        snapping=p.getProperty("canvasSnapping")?.toBooleanStrictOrNull() ?: true,
        showTransformHandles=p.getProperty("canvasTransformHandles")?.toBooleanStrictOrNull() ?: true,
        showOperationStatus=p.getProperty("canvasOperationStatus")?.toBooleanStrictOrNull() ?: true,
    ) }
    fun agentSettings(): AgentFeatureSettings = read("application.properties").let { p ->
        AgentFeatureSettings(
            enabled = p.getProperty("agent.enabled")?.toBooleanStrictOrNull() ?: false,
            activeProviderId = p.getProperty("agent.provider")?.takeIf(String::isNotBlank),
            defaultModelId = p.getProperty("agent.model")?.takeIf(String::isNotBlank),
            workspaceId = p.getProperty("agent.workspace")?.takeIf(String::isNotBlank),
            contextWindowOverride = p.getProperty("agent.contextWindow")?.toLongOrNull()?.takeIf { it > 0L },
            bashMode = runCatching { AgentBashMode.valueOf(p.getProperty("agent.bashMode", "ASK")) }.getOrDefault(AgentBashMode.ASK),
            readAllowed = p.getProperty("agent.readAllowed")?.toBooleanStrictOrNull() ?: true,
            writeAllowed = p.getProperty("agent.writeAllowed")?.toBooleanStrictOrNull() ?: true,
            attachmentImportAllowed = p.getProperty("agent.attachmentAllowed")?.toBooleanStrictOrNull() ?: true,
            imageGenerationEndpoint = p.getProperty("agent.imageEndpoint")?.takeIf(String::isNotBlank),
            imageModelId = p.getProperty("agent.imageModel")?.takeIf(String::isNotBlank),
            searchEndpoint = p.getProperty("agent.searchEndpoint")?.takeIf(String::isNotBlank),
            searchMaxResults = SearchResultCount.parse(p.getProperty("agent.searchMaxResults").orEmpty()),
            searchDepth = SearchDepth.parse(p.getProperty("agent.searchDepth")).wire,
        )
    }
    fun protectedFaces(): ProtectedFacePreferences = read("application.properties").let { p ->
        val unlocked = p.getProperty("protectedFaceUnlocked")?.toBooleanStrictOrNull() ?: false
        ProtectedFacePreferences(
            unlocked = unlocked,
            // 未解锁时不允许"编辑受保护表盘"生效，避免手工改配置文件绕过彩蛋门控。
            allowEditing = unlocked && (p.getProperty("protectedFaceAllowEditing")?.toBooleanStrictOrNull() ?: false),
        )
    }
    @Synchronized fun saveProtectedFaces(value: ProtectedFacePreferences) = write("application.properties", read("application.properties").apply {
        setProperty("protectedFaceUnlocked", value.unlocked.toString())
        setProperty("protectedFaceAllowEditing", value.allowEditing.toString())
    })
    @Synchronized fun saveApplication(theme: ThemePreference, panels: PanelPreferences, window: WindowPreferences) = saveApplication(theme,panels,window,canvasInput())
    @Synchronized fun saveApplication(theme: ThemePreference, panels: PanelPreferences, window: WindowPreferences, canvasInput: CanvasInputPreferences) = write("application.properties", read("application.properties").apply {
        setProperty("theme", theme.name); setProperty("left", panels.left.toString()); setProperty("right", panels.right.toString()); setProperty("bottom", panels.bottom.toString())
        setProperty("width", window.width.toString()); setProperty("height", window.height.toString())
        setProperty("language", DesktopI18n.language().name)
        setProperty("middleButtonPan",canvasInput.middleButtonPan.toString());setProperty("rightButtonPan",canvasInput.rightButtonPan.toString());setProperty("wheelScalesSelectedLayer",canvasInput.wheelScalesSelectedLayer.toString())
        setProperty("canvasSnapping",canvasInput.snapping.toString());setProperty("canvasTransformHandles",canvasInput.showTransformHandles.toString());setProperty("canvasOperationStatus",canvasInput.showOperationStatus.toString())
    })
    @Synchronized fun saveAgentSettings(value: AgentFeatureSettings) {
        val current = read("application.properties")
        fun optional(name: String, raw: String?) { if (raw == null) current.remove(name) else current.setProperty(name, raw) }
        current.setProperty("agent.enabled", value.enabled.toString())
        optional("agent.provider", value.activeProviderId)
        optional("agent.model", value.defaultModelId)
        optional("agent.workspace", value.workspaceId)
        optional("agent.contextWindow", value.contextWindowOverride?.toString())
        current.setProperty("agent.bashMode", value.bashMode.name)
        current.setProperty("agent.readAllowed", value.readAllowed.toString())
        current.setProperty("agent.writeAllowed", value.writeAllowed.toString())
        current.setProperty("agent.attachmentAllowed", value.attachmentImportAllowed.toString())
        optional("agent.imageEndpoint", value.imageGenerationEndpoint)
        optional("agent.imageModel", value.imageModelId)
        optional("agent.searchEndpoint", value.searchEndpoint)
        current.setProperty("agent.searchMaxResults", SearchResultCount.clamp(value.searchMaxResults).toString())
        current.setProperty("agent.searchDepth", SearchDepth.parse(value.searchDepth).wire)
        write("application.properties", current)
    }
    fun preview(identity: String): PreviewPreferences {
        val p = read(key(identity))
        return PreviewPreferences(DevicePreviewSettings(p.int("width", 480, 64, 2048), p.int("height", 480, 64, 2048),
            runCatching { ScreenShape.valueOf(p.getProperty("shape", "DOCUMENT")) }.getOrDefault(ScreenShape.DOCUMENT),
            runCatching { CanvasPreset.valueOf(p.getProperty("preset", "FILL")) }.getOrDefault(CanvasPreset.FILL),
            p.double("zoomX", 1.0, .01, 20.0), p.double("zoomY", 1.0, .01, 20.0), p.double("offsetX", 0.0, -16384.0, 16384.0), p.double("offsetY", 0.0, -16384.0, 16384.0)),
            WorkspaceViewState(p.getProperty("fit", "true").toBoolean(), p.double("zoom", 1.0, .05, 16.0), p.double("panX", 0.0, -1e6, 1e6), p.double("panY", 0.0, -1e6, 1e6)),
            p.stringPropertyNames().filter { it.startsWith("simulation.") }.associate { it.removePrefix("simulation.") to p.getProperty(it) },
            p.getProperty("snapping")?.toBooleanStrictOrNull() ?: true,
            runCatching { CanvasOperationMode.valueOf(p.getProperty("operationMode","DIRECT_DRAG")) }.getOrDefault(CanvasOperationMode.DIRECT_DRAG),
            p.getProperty("devicePreviewEnabled")?.toBooleanStrictOrNull() ?: false,p.getProperty("showTransformHandles")?.toBooleanStrictOrNull() ?: true,
            p.getProperty("showOperationStatus")?.toBooleanStrictOrNull() ?: true)
    }
    @Synchronized fun savePreview(identity: String, value: PreviewPreferences) = write(key(identity), Properties().apply {
        val d = value.device; val w = value.workspace
        mapOf("width" to d.width, "height" to d.height, "shape" to d.shape, "preset" to d.preset, "zoomX" to d.zoomX, "zoomY" to d.zoomY,
            "offsetX" to d.offsetX, "offsetY" to d.offsetY, "fit" to w.fit, "zoom" to w.zoom, "panX" to w.panX, "panY" to w.panY).forEach { (k,v) -> setProperty(k,v.toString()) }
        value.simulation.forEach { (k,v) -> setProperty("simulation.$k", v) }
        setProperty("snapping",value.snapping.toString())
        setProperty("operationMode",value.operationMode.name)
        setProperty("devicePreviewEnabled",value.devicePreviewEnabled.toString()); setProperty("showTransformHandles",value.showTransformHandles.toString()); setProperty("showOperationStatus",value.showOperationStatus.toString())
    })
}
private fun Properties.int(k: String, default: Int, min: Int, max: Int) = getProperty(k)?.toIntOrNull()?.takeIf { it in min..max } ?: default
private fun Properties.float(k: String, default: Float, min: Float, max: Float) = getProperty(k)?.toFloatOrNull()?.takeIf { it.isFinite() && it in min..max } ?: default
private fun Properties.double(k: String, default: Double, min: Double, max: Double) = getProperty(k)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in min..max } ?: default
