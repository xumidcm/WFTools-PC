package com.ailife.wftools.pc.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class DocumentAssetDeletionTest {
    private val root=Files.createTempDirectory("watch-delete-asset-")
    private val store=WatchDocumentStore(root.resolve("sessions"))
    private val font="info face=\"Digits\" size=12\ncommon lineHeight=12 base=10 scaleW=1 scaleH=1 pages=1\npage id=0 file=\"atlas.png\"\n"
    private fun fixture(layers:String="",assets:Map<String,String> = emptyMap()):Path {
        val path=root.resolve("${System.nanoTime()}.watch")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val content=linkedMapOf("watch.xml" to "<Watch name='Assets' width='512' height='512'><Layers>$layers</Layers><Unknown keep='yes'/></Watch>")
            content.putAll(assets)
            content.forEach { (name,text) ->zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry() }
        };return path
    }
    private fun has(path:Path,name:String)=ZipFile(path.toFile()).use { it.getEntry(name)!=null }
    @Test fun originalOrphanDeletionIsUndoableSavedAndRecoverable() {
        val source=fixture("<Layer type='image' path='used.png'/>",mapOf("images/used.png" to "USED","images/空闲.png" to "ORPHAN"))
        val original=Files.readAllBytes(source);val session=store.open(source);val before=session.layers
        session.deleteAsset("IMAGES/空闲.png")
        assertFalse(Files.exists(session.workDir.resolve("images/空闲.png")));assertEquals(before,session.layers);assertTrue(session.canUndo)
        assertArrayEquals(original,Files.readAllBytes(source))
        val deleted=root.resolve("deleted.watch");store.save(session,deleted)
        assertFalse(has(deleted,"images/空闲.png"));assertTrue(has(deleted,"images/used.png"))
        session.undo();assertEquals("ORPHAN",Files.readString(session.workDir.resolve("images/空闲.png")))
        val restored=root.resolve("restored.watch");store.save(session,restored);assertTrue(has(restored,"images/空闲.png"))
        session.redo();assertFalse(Files.exists(session.workDir.resolve("images/空闲.png")))
        val id=session.id;session.close()
        store.recover(id).use { recovered ->
            assertFalse(Files.exists(recovered.workDir.resolve("images/空闲.png")))
            val exported=root.resolve("recovered.watch");store.save(recovered,exported);assertFalse(has(exported,"images/空闲.png"))
            recovered.undo();assertEquals("ORPHAN",Files.readString(recovered.workDir.resolve("images/空闲.png")))
        }
        assertArrayEquals(original,Files.readAllBytes(source))
    }
    @Test fun importedOrphanDeletionKeepsExternalFileAndUndoBytes() {
        val external=root.resolve("输入.png");Files.writeString(external,"EXTERNAL")
        store.newDocument().use { session ->
            session.importAssets(listOf(external),createLayers=false)
            session.deleteAsset("images/输入.png")
            assertFalse(Files.exists(session.workDir.resolve("images/输入.png")))
            assertEquals("EXTERNAL",Files.readString(external))
            session.undo();assertEquals("EXTERNAL",Files.readString(session.workDir.resolve("images/输入.png")))
            session.redo();val output=root.resolve("imported-deleted.watch");store.save(session,output)
            assertFalse(has(output,"images/输入.png"));assertEquals("EXTERNAL",Files.readString(external))
        }
    }
    @Test fun layerFontAtlasAndSoundReferencesRejectDeletionWithoutHistory() {
        val layers="<Layer type='image' path='used.png'/><Layer type='text' font='正文'/><Layer type='calendar' font2='标题'/><Layer type='text' font='bm:digits'/><Layer type='shape' tap_action='sfx:tap'/>"
        val assets=mapOf("images/used.png" to "IMAGE","fonts/正文.ttf" to "FONT","fonts/标题.otf" to "FONT2","fonts_bm/digits.fnt" to font,"fonts_bm/atlas.png" to "ATLAS","sfx/tap.mp3" to "SOUND")
        store.open(fixture(layers,assets)).use { session ->
            for(path in assets.keys) {
                assertThrows(WatchDocumentException.InvalidCommand::class.java) { session.deleteAsset(path) }
                assertTrue(Files.exists(session.workDir.resolve(path)));assertFalse(session.canUndo);assertFalse(session.hasUnsavedChanges)
            }
        }
    }
    @Test fun orphanBitmapDescriptorStillProtectsItsAtlasUntilDescriptorIsDeleted() {
        store.open(fixture(assets=mapOf("fonts_bm/orphan.fnt" to font,"fonts_bm/atlas.png" to "ATLAS"))).use { session ->
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { session.deleteAsset("fonts_bm/atlas.png") }
            session.deleteAsset("fonts_bm/orphan.fnt");session.deleteAsset("fonts_bm/atlas.png")
            session.undo();assertTrue(Files.exists(session.workDir.resolve("fonts_bm/atlas.png")));assertFalse(Files.exists(session.workDir.resolve("fonts_bm/orphan.fnt")))
            session.undo();assertTrue(Files.exists(session.workDir.resolve("fonts_bm/orphan.fnt")))
        }
    }
    @Test fun dynamicPathsAndScriptReferencesRejectUnsafeDeletion() {
        store.open(fixture("<Layer type='image' path='{var_image}'/>",mapOf("images/unused.png" to "IMAGE"))).use { session ->
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { session.deleteAsset("images/unused.png") }
            assertFalse(session.canUndo)
        }
        store.open(fixture(assets=mapOf("images/scripted.png" to "IMAGE","sfx/tap.mp3" to "SOUND","scripts/script.txt" to "var_icon='images/scripted.png'\nfunction tap() wm_sfx('tap') end"))).use { session ->
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { session.deleteAsset("images/scripted.png") }
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { session.deleteAsset("sfx/tap.mp3") }
            assertFalse(session.canUndo)
        }
    }
    @Test fun protectedAndUnknownPathsAreNeverDeleted() {
        store.open(fixture(assets=mapOf("scripts/script.txt" to "var_count=1","preview.jpg" to "PREVIEW","preview_dim.jpg" to "DIM","images/unused.png" to "IMAGE"))).use { session ->
            for(path in listOf("watch.xml","scripts/script.txt","preview.jpg","preview_dim.jpg","../outside.png","images","images/missing.png")) {
                assertThrows(WatchDocumentException.InvalidCommand::class.java) { session.deleteAsset(path) }
            }
            assertFalse(session.canUndo);assertFalse(session.hasUnsavedChanges)
            session.deleteAsset("images/unused.png");assertFalse(Files.exists(session.workDir.resolve("images/unused.png")))
        }
    }
    @Test fun conditionalWeatherNightImageCannotBeDeleted() {
        store.open(fixture("<Layer type='image_cond' path='天气.png' weather_night='Y'/>",
            mapOf("images/天气.png" to "DAY", "images/天气_n.png" to "NIGHT"))).use { session ->
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { session.deleteAsset("images/天气_n.png") }
            assertEquals("NIGHT",Files.readString(session.workDir.resolve("images/天气_n.png")))
            assertFalse(session.canUndo);assertFalse(session.hasUnsavedChanges)
        }
    }
    @Test fun fallbackTapAliasSoundCannotBeDeleted() {
        store.open(fixture("<Layer type='shape' tap_action='' tap='play_sound:提示'/>",mapOf("sfx/提示.mp3" to "SOUND"))).use { session ->
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { session.deleteAsset("sfx/提示.mp3") }
            assertEquals("SOUND",Files.readString(session.workDir.resolve("sfx/提示.mp3")))
            assertFalse(session.canUndo);assertFalse(session.hasUnsavedChanges)
        }
    }
}
