package com.ailife.wftools.pc

import androidx.compose.ui.geometry.Rect
import com.ailife.wftools.pc.core.WatchDocumentStore
import com.ailife.wftools.pc.preview.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.JLabel
import javax.swing.JPanel
import javax.imageio.ImageIO

class NativeDropRoutingRedTest {
    @get:Rule val temp = TemporaryFolder()
    private suspend fun scenario(block: suspend (EditorApplicationModel,DocumentController,DropRegistry,java.nio.file.Path) -> Unit) {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store = WatchDocumentStore(temp.newFolder().toPath()); val preferences = EditorPreferences(temp.newFolder().toPath())
        val doc = DocumentController.create(store.newDocument(),store,scope,preferences)
        doc.render.playing = false
        val image = temp.newFile("drop-${System.nanoTime()}.png").toPath()
        ImageIO.write(BufferedImage(12,12,BufferedImage.TYPE_INT_ARGB),"png",image.toFile())
        val original = Files.readAllBytes(image)
        try { block(EditorApplicationModel(scope,preferences,store),doc,DropRegistry(),image); assertArrayEquals("Drops must copy external files",original,Files.readAllBytes(image)) }
        finally { withTimeoutOrNull(5000) { while(doc.busy) delay(5) }; doc.render.close(); doc.session.close(); scope.cancel() }
    }
    private suspend fun idle(doc: DocumentController) { withTimeout(5000) { while(doc.busy) delay(5) } }
    @Test fun `asset pane drop imports to library without creating a layer`(): Unit = runBlocking {
        scenario { app,doc,drops,image ->
            drops.library = DropBounds(0f,0f,200f,300f)
            drops.drop(app,doc,listOf(image),50.0,70.0); idle(doc)
            assertNull(doc.error); assertEquals(1,doc.view.assets.size); assertTrue(doc.view.layers.isEmpty())
        }
    }
    @Test fun `property target replaces an existing layer rather than creating another`(): Unit = runBlocking {
        scenario { app,doc,drops,image ->
            doc.assetCommand { importAssets(listOf(image)) }; idle(doc)
            val id = doc.view.layers.single().id
            drops.library = DropBounds(0f,0f,200f,300f)
            drops.fields["path"] = PropertyDrop(DropBounds(10f,10f,150f,50f),"path",setOf(id))
            val oldPath = doc.view.layers.single().attrs["path"]
            drops.drop(app,doc,listOf(image),40.0,25.0); idle(doc)
            assertNull(doc.error); assertEquals(id,doc.view.layers.single().id); assertNotEquals(oldPath,doc.view.layers.single().attrs["path"])
        }
    }
    @Test fun `preview drop uses the design point rather than the default centre`(): Unit = runBlocking {
        scenario { app,doc,drops,image ->
            val device = DevicePreviewSettings()
            drops.preview = PreviewDropGeometry(Rect(100f,100f,580f,580f),ViewportTransform.create(DesignExtent(512.0,512.0),device),WorkspaceTransform(1.0,0.0,0.0),device,false)
            drops.drop(app,doc,listOf(image),400.0,340.0); idle(doc)
            assertNull(doc.error); assertEquals("64",doc.view.layers.single().attrs["x"]); assertEquals("0",doc.view.layers.single().attrs["y"])
        }
    }
    @Test fun `layer page and other editor space tolerate drops by creating at design centre with feedback`(): Unit = runBlocking {
        scenario { app,doc,drops,image ->
            // Neither location is a property, asset library, or actual preview target.
            drops.drop(app,doc,listOf(image),24.0,140.0); idle(doc)
            assertNull("Layer-page drops must not be rejected as Invalid",doc.error)
            assertEquals("0",doc.view.layers.single().attrs["x"]); assertEquals("0",doc.view.layers.single().attrs["y"])
            assertFalse("A successful native import needs non-interrupting feedback",doc.notice.isNullOrBlank())
            drops.drop(app,doc,listOf(image),700.0,24.0); idle(doc)
            assertEquals(2,doc.view.layers.size); assertEquals(2,doc.view.assets.size)
        }
    }
    @Test fun `already converted AWT window points are not multiplied by DPI again`() {
        // Reflection keeps all behavioural RED tests compilable before this small pure seam exists.
        val owner = Class.forName("com.ailife.wftools.pc.platform.DesktopFilesKt")
        val method = owner.declaredMethods.firstOrNull { it.name == "awtDropPointToWindow" && it.parameterTypes.contentEquals(arrayOf(java.awt.Point::class.java,Float::class.javaPrimitiveType)) }
        assertNotNull("Provide pure awtDropPointToWindow(java.awt.Point, Float): preview.Point; both inputs already use window pixels",method)
        method!!.isAccessible = true
        for(dpi in listOf(1f,1.25f,1.5f,2f)) {
            val point = method.invoke(null,java.awt.Point(240,180),dpi) as Point
            assertEquals("X must not be scaled twice at DPI $dpi",240.0,point.x,0.0)
            assertEquals("Y must not be scaled twice at DPI $dpi",180.0,point.y,0.0)
        }
    }
    @Test fun `native drop registration covers the compose host descendants`() {
        val root = JPanel()
        val composeHost = JPanel()
        val skiaReceiver = JLabel("canvas")
        composeHost.add(skiaReceiver)
        root.add(composeHost)

        val owner = Class.forName("com.ailife.wftools.pc.platform.DesktopFilesKt")
        val method = owner.declaredMethods.firstOrNull {
            it.name == "dropReceiverComponents" && it.parameterTypes.contentEquals(arrayOf(Component::class.java))
        }
        assertNotNull("Native drops must enumerate the top-level host and every nested Compose/Skia component", method)
        method!!.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val receivers = method.invoke(null, root) as List<Component>

        assertEquals(listOf(root, composeHost, skiaReceiver), receivers)
        assertTrue(receivers.filterIsInstance<Container>().all { receiver -> receiver.components.all { it in receivers } })
    }
    @Test fun `watch dropped on an editor waits for an open-or-import decision`() = runBlocking {
        scenario { app,doc,drops,_ ->
            val watch = temp.root.toPath().resolve("incoming.watch")
            Files.writeString(watch,"not opened yet")
            drops.drop(app,doc,listOf(watch),24.0,140.0)
            assertEquals(watch,drops.pendingWatch?.path)
        }
    }
}
