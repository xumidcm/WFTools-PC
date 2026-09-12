package com.ailife.wftools.pc.platform

import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Frame
import java.awt.Image
import java.awt.Window
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.io.FilenameFilter
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.imageio.ImageIO

fun nativeDialogIconImages(owner:Component?):List<Image> {
    val inherited=owner?.let(SwingUtilities::getWindowAncestor)?.iconImages.orEmpty().filterNotNull()
    if(inherited.isNotEmpty())return inherited
    val resource=Thread.currentThread().contextClassLoader.getResource("icons/wftools.png") ?: return emptyList()
    return listOfNotNull(runCatching{ImageIO.read(resource)}.getOrNull())
}

private fun nativeFileDialog(owner: Component?, title: String, mode: Int): FileDialog {
    val parent = SwingUtilities.getWindowAncestor(owner)
    return (when (parent) {
        is Frame -> FileDialog(parent, title, mode)
        is Dialog -> FileDialog(parent, title, mode)
        else -> FileDialog(null as Frame?, title, mode)
    }).apply { iconImages=nativeDialogIconImages(owner) }
}

private fun chooseOpenWithNativeDialog(owner: Component?, title: String, extensions: String): List<Path> {
    val dialog = nativeFileDialog(owner, title, FileDialog.LOAD).apply {
        isMultipleMode = true
        filenameFilter = FilenameFilter { _, name -> extensions.split(',').any { name.endsWith(".$it", true) } }
    }
    dialog.isVisible = true
    return dialog.files?.map { it.toPath() }.orEmpty()
}
fun chooseWatchFiles(owner: Component?): List<Path> = chooseOpenWithNativeDialog(owner, "打开 WatchMaker 表盘", "watch")
fun chooseAssets(owner: Component?): List<Path> = chooseOpenWithNativeDialog(owner, "导入图片、视频或字体", "png,jpg,jpeg,gif,webp,bmp,mp4,webm,mov,m4v,avi,mkv,ttf,otf,fnt")
fun chooseAudioFiles(owner: Component?): List<Path> = chooseOpenWithNativeDialog(owner, "添加音频资源", "mp3,wav,ogg,m4a,aac,flac")
fun chooseSavePath(owner: Component?, source: Path?, title: String, allowExisting: Boolean = true): Path? {
    val dialog = nativeFileDialog(owner, "保存 WatchMaker 表盘", FileDialog.SAVE).apply {
        filenameFilter = FilenameFilter { _, name -> name.endsWith(".watch", true) }
        file = source?.fileName?.toString() ?: title.replace(Regex("[<>:\"/\\\\|?*]"), "_") + ".watch"
    }
    dialog.isVisible = true
    val selected = dialog.file ?: return null
    val parent = dialog.directory ?: return null
    val file = if (selected.endsWith(".watch", true)) java.io.File(parent, selected) else java.io.File(parent, selected + ".watch")
    if (file.exists() && (!allowExisting || JOptionPane.showConfirmDialog(owner, "“${file.name}”已存在，要替换它吗？", "确认保存", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION)) return null
    return file.toPath()
}
fun chooseCapturePath(owner: Component?, title: String, extension: String, description: String): Path? {
    val dialog = nativeFileDialog(owner, description, FileDialog.SAVE).apply {
        filenameFilter = FilenameFilter { _, name -> name.endsWith(".$extension", true) }
        file = title.replace(Regex("[<>:\"/\\\\|?*]"), "_") + "." + extension
    }
    dialog.isVisible = true
    val selected = dialog.file ?: return null
    val parent = dialog.directory ?: return null
    val file = if (selected.endsWith(".$extension", true)) java.io.File(parent, selected) else java.io.File(parent, selected + ".$extension")
    if (file.exists() && JOptionPane.showConfirmDialog(owner, "“${file.name}”已存在，要替换它吗？", "确认保存", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return null
    return file.toPath()
}

/**
 * Compose Desktop renders through nested AWT components. Windows dispatches a native drag to
 * the deepest component under the pointer, so registering only the outer ComposeWindow leaves
 * most of the editor reporting a forbidden drop cursor.
 */
fun dropReceiverComponents(root: Component): List<Component> = buildList {
    fun visit(component: Component) {
        add(component)
        if (component is Container) component.components.forEach(::visit)
    }
    visit(root)
}

/** Native file drops copy inputs; no MOVE or external-file deletion is ever requested. */
class FileDropRegistration(private val component: Component, private val window: Window, private val density: () -> Float,
    private val onDrop: (List<Path>, Double, Double) -> Unit, private val onError: (String) -> Unit) : AutoCloseable {
    private data class Receiver(val component: Component, val previous: DropTarget?, val target: DropTarget)
    private val content = (window as? javax.swing.RootPaneContainer)?.contentPane ?: component
    private fun listener(receiver: Component) = object : DropTargetAdapter() {
        private fun accept(event: DropTargetDragEvent) {
            if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) event.acceptDrag(DnDConstants.ACTION_COPY) else event.rejectDrag()
        }
        override fun dragEnter(event: DropTargetDragEvent) = accept(event)
        override fun dragOver(event: DropTargetDragEvent) = accept(event)
        override fun dropActionChanged(event: DropTargetDragEvent) = accept(event)
        override fun drop(event: DropTargetDropEvent) {
            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) { event.rejectDrop(); return }
            try {
                event.acceptDrop(DnDConstants.ACTION_COPY)
                val files = (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<java.io.File>().orEmpty()
                if (files.isEmpty()) { event.dropComplete(false); return }
                val p = SwingUtilities.convertPoint(receiver, event.location, content)
                val point = awtDropPointToWindow(p,density())
                onDrop(files.map { it.toPath() },point.x,point.y)
                event.dropComplete(true)
            } catch (e: Exception) { onError("无法读取拖放文件：${e.message}。请使用“打开”或“导入”。"); event.dropComplete(false) }
        }
    }
    private val receivers = dropReceiverComponents(component).map { receiver ->
        val previous = receiver.dropTarget
        Receiver(receiver, previous, DropTarget(receiver, DnDConstants.ACTION_COPY, listener(receiver), true))
    }
    override fun close() {
        receivers.asReversed().forEach { receiver ->
            if (receiver.component.dropTarget === receiver.target) receiver.component.dropTarget = receiver.previous
        }
    }
}
/** AWT content coordinates and boundsInWindow already share window pixels on desktop. */
fun awtDropPointToWindow(point: java.awt.Point,@Suppress("UNUSED_PARAMETER") density: Float) = com.ailife.wftools.pc.preview.Point(point.x.toDouble(),point.y.toDouble())
