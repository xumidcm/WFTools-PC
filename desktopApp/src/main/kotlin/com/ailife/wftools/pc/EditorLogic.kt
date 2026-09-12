package com.ailife.wftools.pc

import com.ailife.wftools.pc.core.EditorLayer
import java.nio.file.Path
import java.util.Locale

enum class EditorLayout { THREE_COLUMNS, SIDE_INSPECTOR, STACKED }
fun editorLayout(width: Float) = when { width >= 1100f -> EditorLayout.THREE_COLUMNS; width >= 800f -> EditorLayout.SIDE_INSPECTOR; else -> EditorLayout.STACKED }
enum class EditorFocus { TEXT, LAYERS, CANVAS, OTHER }
enum class ClipboardPayload { LAYERS, IMAGE, TEXT, NONE }
fun classifyClipboardPayload(imageAvailable: Boolean, text: String?): ClipboardPayload = when {
    text?.trimStart()?.startsWith("<WFToolsLayers", ignoreCase = true) == true -> ClipboardPayload.LAYERS
    imageAvailable -> ClipboardPayload.IMAGE
    !text.isNullOrEmpty() -> ClipboardPayload.TEXT
    else -> ClipboardPayload.NONE
}
fun documentPasteOwnsFocus(focus: EditorFocus): Boolean = focus != EditorFocus.TEXT
enum class EditorCommand(val label: String) {
    OPEN("打开…"), NEW("新建表盘"), SAVE("保存"), SAVE_AS("另存为…"), CLOSE("关闭窗口"),
    UNDO("撤销"), REDO("重做"), COPY("复制图层"), PASTE("粘贴图层"), DUPLICATE("创建副本"), DELETE("删除图层"),
    SELECT_ALL("全选可见图层"), HIDE("隐藏"), SHOW("显示"), TOP("置于顶层"), BOTTOM("置于底层"), UP("上移一层"), DOWN("下移一层")
}
fun commandOwnsFocus(command: EditorCommand, focus: EditorFocus): Boolean = focus != EditorFocus.TEXT || command in setOf(EditorCommand.OPEN, EditorCommand.NEW, EditorCommand.SAVE, EditorCommand.SAVE_AS, EditorCommand.CLOSE)
fun visibleLayers(layers: List<EditorLayer>, query: String, type: String): List<EditorLayer> = layers.asReversed().filter {
    (type.isEmpty() || it.type == type) && (query.isBlank() || (it.type + " " + it.attrs.values.joinToString(" ")).contains(query, true))
}
fun nextVisibleSelection(order: List<String>, primary: String?, direction: Int): String? {
    if (order.isEmpty()) return null
    val current = order.indexOf(primary)
    return order[if (current < 0) (if (direction > 0) 0 else order.lastIndex) else (current + direction).coerceIn(order.indices)]
}
/** A visual gap index includes selected rows. The core expects an insertion index after removing them. */
fun documentInsertionIndex(visualOrder: List<String>, selected: Set<String>, visualGap: Int): Int {
    val remaining = visualOrder.count { it !in selected }
    val before = visualOrder.take(visualGap.coerceIn(0, visualOrder.size)).count { it !in selected }
    return remaining - before
}
fun canonicalIdentity(path: Path): String = runCatching { path.toRealPath().toString() }.getOrElse { path.toAbsolutePath().normalize().toString() }.lowercase(Locale.ROOT)
enum class DropBatch { WATCHES, ASSETS, MIXED, EMPTY }

/** Device dimensions are a preview concern; the mask always follows the document's saved shape. */
fun effectivePreviewDevice(preferences: PreviewPreferences) = if(preferences.devicePreviewEnabled)
    preferences.device.copy(shape = com.ailife.wftools.pc.preview.ScreenShape.DOCUMENT)
else com.ailife.wftools.pc.preview.DevicePreviewSettings(
    512,512,com.ailife.wftools.pc.preview.ScreenShape.DOCUMENT,com.ailife.wftools.pc.preview.CanvasPreset.FIT,
)
fun classifyDrop(paths: List<Path>): DropBatch {
    if (paths.isEmpty()) return DropBatch.EMPTY
    val watches = paths.count { it.fileName.toString().endsWith(".watch", true) }
    return when (watches) { paths.size -> DropBatch.WATCHES; 0 -> DropBatch.ASSETS; else -> DropBatch.MIXED }
}
data class DropBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Double, y: Double) = x >= left && y >= top && x < right && y < bottom
}
data class PropertyDrop(val bounds: DropBounds, val attribute: String, val ids: Set<String>)
enum class ExternalDropTarget { LayerList, AssetLibrary, Canvas, Invalid }
fun routeExternalDrop(x: Double, y: Double, layerList: DropBounds?, library: DropBounds?, canvas: DropBounds?): ExternalDropTarget =
    when {
        layerList?.contains(x, y) == true -> ExternalDropTarget.LayerList
        library?.contains(x, y) == true -> ExternalDropTarget.AssetLibrary
        canvas?.contains(x, y) == true -> ExternalDropTarget.Canvas
        else -> ExternalDropTarget.Invalid
    }
fun isSupportedClipboardFlavor(flavor: java.awt.datatransfer.DataFlavor): Boolean =
    flavor == java.awt.datatransfer.DataFlavor.imageFlavor || flavor == java.awt.datatransfer.DataFlavor.stringFlavor
sealed interface AssetDropTarget {
    data class Property(val attribute: String, val ids: Set<String>) : AssetDropTarget
    data object Library : AssetDropTarget
    data object Preview : AssetDropTarget
    data object Invalid : AssetDropTarget
}
fun routeAssetDrop(x: Double, y: Double, fields: List<PropertyDrop>, library: DropBounds?, preview: DropBounds?): AssetDropTarget =
    fields.lastOrNull { it.bounds.contains(x, y) }?.let { AssetDropTarget.Property(it.attribute, it.ids) }
        ?: if (library?.contains(x, y) == true) AssetDropTarget.Library
        else if (preview?.contains(x, y) == true) AssetDropTarget.Preview else AssetDropTarget.Invalid

/** Atlas pages and sound files remain visible resources, but are not standalone layer/path choices. */
fun assetTargetAttribute(asset: AssetItem): String? {
    val ext = asset.relative.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        asset.kind == "fonts" && ext in setOf("ttf","otf") -> "font"
        asset.kind == "fonts_bm" && ext == "fnt" -> "font"
        asset.kind == "images" && ext in setOf("png","jpg","jpeg","gif","webp","bmp") -> "path"
        asset.kind == "videos" && ext in setOf("mp4","webm","mov","m4v","avi","mkv") -> "path"
        else -> null
    }
}
fun compatibleAssetSelection(asset: AssetItem, layers: List<EditorLayer>, selection: Set<String>): Set<String> {
    val attribute = assetTargetAttribute(asset) ?: return emptySet()
    return layers.filter { layer -> layer.id in selection && when {
        attribute == "font" -> layer.type.startsWith("text") || layer.type in setOf("series","calendar")
        asset.kind == "videos" -> layer.type == "video"
        else -> layer.type in setOf("image","image_gif","image_cond")
    } }.map { it.id }.toSet()
}
fun offsetEditorExpression(raw: String, delta: Float): String {
    val value = raw.ifBlank { "0" }.toDoubleOrNull()?.takeIf { it.isFinite() }
    val change = java.math.BigDecimal.valueOf(delta.toDouble()).stripTrailingZeros().toPlainString()
    return if(value == null) "($raw)+($change)" else java.math.BigDecimal.valueOf(value + delta).stripTrailingZeros().toPlainString()
}
fun missingLayerImage(layer: EditorLayer, assets: List<AssetItem>): String? {
    val path = layer.attrs["path"].orEmpty().replace('\\','/')
    if(layer.type !in setOf("image","image_gif","image_cond","video") || path.isBlank() || '{' in path) return null
    if(path.substringAfterLast('.',"").lowercase() !in setOf("png","jpg","jpeg","gif","webp","bmp","mp4","webm","mov","m4v","avi","mkv")) return null
    val folder = if(layer.type == "video") "videos" else "images"
    return path.takeIf { assets.none { asset -> asset.relative.equals(path,true) || asset.relative.equals("$folder/$path",true) } }
}
fun clipboardSourceId(marker: String): String? = Regex("""\bsourceDocumentId\s*=\s*["']([^"']+)["']""").find(marker)?.groupValues?.get(1)
fun clipboardNeedsScriptMerge(marker: String, targetId: String, sourceScript: String): Boolean {
    val source = clipboardSourceId(marker) ?: return false
    if(source == targetId || sourceScript.isBlank()) return false
    val definitions = Regex("""(?m)^\s*(?:local\s+)?function\s+([A-Za-z_]\w*)""").findAll(sourceScript).map { it.groupValues[1] }.toList() +
        Regex("""(?m)^\s*(?!local\b)([A-Za-z_]\w*)\s*=(?!=)""").findAll(sourceScript).map { it.groupValues[1] }.toList()
    val expressions = Regex("""\{([^{}]+)}|(?:lua|script):([^"<]*)""").findAll(marker).map { it.value }.toList()
    return definitions.any { name -> val reference = Regex("\\b"+Regex.escape(name)+"\\b"); expressions.any(reference::containsMatchIn) }
}
