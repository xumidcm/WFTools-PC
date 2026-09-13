package com.ailife.wftools.pc

import com.ailife.wftools.pc.core.EditorLayer
import com.ailife.wftools.agent.model.AgentMessage
import com.ailife.wftools.agent.model.AgentMessageRole
import com.ailife.wftools.agent.model.AgentToolCallRecord
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class EditorLogicTest {
    @Test fun `breakpoints structurally switch without changing text scale`() {
        assertEquals(EditorLayout.STACKED, editorLayout(720f)); assertEquals(EditorLayout.STACKED, editorLayout(799.99f))
        assertEquals(EditorLayout.SIDE_INSPECTOR, editorLayout(800f)); assertEquals(EditorLayout.SIDE_INSPECTOR, editorLayout(1099.99f))
        assertEquals(EditorLayout.THREE_COLUMNS, editorLayout(1100f)); assertEquals(EditorLayout.THREE_COLUMNS, editorLayout(1920f))
    }
    @Test fun `text focus owns clipboard select all delete and undo while file commands work`() {
        listOf(EditorCommand.DELETE, EditorCommand.COPY, EditorCommand.PASTE, EditorCommand.SELECT_ALL, EditorCommand.UNDO, EditorCommand.REDO).forEach { assertFalse(it.name, commandOwnsFocus(it, EditorFocus.TEXT)) }
        listOf(EditorCommand.OPEN, EditorCommand.NEW, EditorCommand.SAVE, EditorCommand.SAVE_AS, EditorCommand.CLOSE).forEach { assertTrue(it.name, commandOwnsFocus(it, EditorFocus.TEXT)) }
        assertTrue(commandOwnsFocus(EditorCommand.DELETE, EditorFocus.LAYERS)); assertTrue(commandOwnsFocus(EditorCommand.UNDO, EditorFocus.CANVAS))
    }
    @Test fun `visible order is reversed once and preserves stable IDs through filtering`() {
        val layers = listOf(EditorLayer("bottom", "image", mapOf("path" to "face.png")), EditorLayer("middle", "text", mapOf("text" to "same")), EditorLayer("top", "text", mapOf("text" to "same")))
        assertEquals(listOf("top", "middle", "bottom"), visibleLayers(layers, "", "").map { it.id })
        assertEquals(listOf("top", "middle"), visibleLayers(layers, "SAME", "text").map { it.id })
        assertEquals("middle", nextVisibleSelection(listOf("top","middle"), "top", 1))
        assertEquals("middle", nextVisibleSelection(listOf("top","middle"), "middle", 1))
        assertEquals("top", nextVisibleSelection(listOf("top","middle"), "hidden", 1))
        assertNull(nextVisibleSelection(emptyList(), null, 1))
    }
    @Test fun `group drag converts visual gaps after removing noncontiguous selection`() {
        val visual = listOf("E","D","C","B","A")
        val selected = setOf("D","B")
        assertEquals(3, documentInsertionIndex(visual, selected, 0))
        assertEquals(2, documentInsertionIndex(visual, selected, 2))
        assertEquals(1, documentInsertionIndex(visual, selected, 4))
        assertEquals(0, documentInsertionIndex(visual, selected, 5))
        assertEquals(0, documentInsertionIndex(visual, selected, 99))
    }
    @Test fun `mixed drop rejects before any mutation and watch extension is case insensitive`() {
        assertEquals(DropBatch.WATCHES, classifyDrop(listOf(Path.of("a.WATCH"), Path.of("b.watch"))))
        assertEquals(DropBatch.MIXED, classifyDrop(listOf(Path.of("a.watch"), Path.of("b.png"))))
        assertEquals(DropBatch.ASSETS, classifyDrop(listOf(Path.of("a.ttf"), Path.of("b.fnt"))))
        assertEquals(DropBatch.EMPTY, classifyDrop(emptyList()))
    }
    @Test fun `drop routing uses actual target bounds and fields take precedence`() {
        val panel = DropBounds(0f,0f,100f,100f)
        val preview = DropBounds(100f,0f,500f,400f)
        val font = PropertyDrop(DropBounds(110f,10f,200f,42f), "font", setOf("text-layer"))
        assertEquals(AssetDropTarget.Property("font", setOf("text-layer")), routeAssetDrop(150.0,20.0,listOf(font),panel,preview))
        assertEquals(AssetDropTarget.Library, routeAssetDrop(20.0,20.0,listOf(font),panel,preview))
        assertEquals(AssetDropTarget.Preview, routeAssetDrop(300.0,200.0,listOf(font),panel,preview))
        assertEquals(AssetDropTarget.Invalid, routeAssetDrop(500.0,200.0,listOf(font),panel,preview))
    }

    @Test fun `window file drops distinguish layer list from asset library and canvas`() {
        val layers = DropBounds(0f, 0f, 220f, 300f)
        val library = DropBounds(220f, 0f, 420f, 300f)
        val canvas = DropBounds(420f, 0f, 900f, 600f)
        assertEquals(ExternalDropTarget.LayerList, routeExternalDrop(100.0, 100.0, layers, library, canvas))
        assertEquals(ExternalDropTarget.AssetLibrary, routeExternalDrop(300.0, 100.0, layers, library, canvas))
        assertEquals(ExternalDropTarget.Canvas, routeExternalDrop(600.0, 300.0, layers, library, canvas))
        assertEquals(ExternalDropTarget.Invalid, routeExternalDrop(910.0, 300.0, layers, library, canvas))
    }

    @Test fun `clipboard text is only consumed for image or text payloads`() {
        assertTrue(isSupportedClipboardFlavor(java.awt.datatransfer.DataFlavor.imageFlavor))
        assertTrue(isSupportedClipboardFlavor(java.awt.datatransfer.DataFlavor.stringFlavor))
        assertFalse(isSupportedClipboardFlavor(java.awt.datatransfer.DataFlavor.javaFileListFlavor))
    }
    @Test fun `system clipboard payload is routed by content and editor focus`() {
        assertEquals(ClipboardPayload.LAYERS, classifyClipboardPayload(false, "<WFToolsLayers version=\"2\"/>"))
        assertEquals(ClipboardPayload.IMAGE, classifyClipboardPayload(true, "ordinary text"))
        assertEquals(ClipboardPayload.TEXT, classifyClipboardPayload(false, "ordinary text"))
        assertEquals(ClipboardPayload.NONE, classifyClipboardPayload(false, null))
        assertTrue(documentPasteOwnsFocus(EditorFocus.CANVAS))
        assertTrue(documentPasteOwnsFocus(EditorFocus.LAYERS))
        assertFalse(documentPasteOwnsFocus(EditorFocus.TEXT))
    }
    @Test fun `streaming and final assistant text do not repeat an earlier tool preamble`() {
        val user = AgentMessage.user("c", "画一个图", 1L)
        val earlier = AgentMessage.assistant("c", "上一轮内容", 2L).copy(toolCalls = listOf(AgentToolCallRecord("a", "inspect_face", "{}")))
        val current = AgentMessage.assistant("c", "可以生成图片", 3L).copy(toolCalls = listOf(AgentToolCallRecord("b", "generate_image", "{}")))
        val streaming = visibleAgentMessages(listOf(user, earlier, current), "可以生成图片", true)
        assertEquals(listOf(user.id, earlier.id), streaming.map { it.id })
        val final = AgentMessage.assistant("c", "可以生成图片", 4L)
        val completed = visibleAgentMessages(listOf(user, earlier, current, final), "", false)
        assertEquals(listOf(user.id, earlier.id, final.id), completed.map { it.id })
    }
    @Test fun `atlas pages and sounds cannot be applied as fonts or image paths`() {
        fun asset(relative: String) = AssetItem(Path.of(relative),relative,relative.substringBefore('/'))
        assertNull(assetTargetAttribute(asset("fonts_bm/clock.png"))); assertNull(assetTargetAttribute(asset("sfx/tap.mp3")))
        assertEquals("font",assetTargetAttribute(asset("fonts_bm/clock.fnt"))); assertEquals("font",assetTargetAttribute(asset("fonts/clock.ttf")))
        val layers = listOf(EditorLayer("text","text",emptyMap()),EditorLayer("image","image",emptyMap()),EditorLayer("video","video",emptyMap()))
        val all = layers.map { it.id }.toSet()
        assertEquals(setOf("text"),compatibleAssetSelection(asset("fonts/clock.ttf"),layers,all))
        assertEquals(setOf("video"),compatibleAssetSelection(asset("videos/movie.mp4"),layers,all))
        assertEquals(setOf("image"),compatibleAssetSelection(asset("images/face.png"),layers,all))
    }
    @Test fun `missing image hints ignore dynamic expressions and resolve archive paths case insensitively`() {
        val image = EditorLayer("image","image",mapOf("path" to "face.png"))
        assertEquals("face.png",missingLayerImage(image,emptyList()))
        assertNull(missingLayerImage(image,listOf(AssetItem(Path.of("images/FACE.PNG"),"images/FACE.PNG","images"))))
        assertNull(missingLayerImage(image.copy(attrs = mapOf("path" to "face_{ds}.png")),emptyList()))
    }
    @Test fun `cross document script notice is only shown for referenced source definitions`() {
        val script = "function wf_label() return 'hello' end\nvar_tone=42"
        fun marker(expression: String) = "<WFToolsLayers sourceDocumentId=\"source\"><Layer text=\"$expression\"/></WFToolsLayers>"
        assertTrue(clipboardNeedsScriptMerge(marker("{wf_label()}"),"target",script))
        assertTrue(clipboardNeedsScriptMerge(marker("{var_tone}"),"target",script))
        assertFalse(clipboardNeedsScriptMerge(marker("{dh}:{dmz}"),"target",script))
        assertFalse(clipboardNeedsScriptMerge(marker("{wf_label()}"),"source",script))
        assertFalse(clipboardNeedsScriptMerge(marker("{wf_label()}"),"target",""))
    }
}
