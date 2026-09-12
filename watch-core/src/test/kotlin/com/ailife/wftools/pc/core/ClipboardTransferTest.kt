package com.ailife.wftools.pc.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ClipboardTransferTest {
    private val dir=Files.createTempDirectory("watch-transfer-")
    private val store=WatchDocumentStore(dir.resolve("sessions"))
    private fun fixture(layers:String, assets:Map<String,String> = emptyMap()):Path {
        val path=dir.resolve("${System.nanoTime()}.watch")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val entries=linkedMapOf("watch.xml" to "<Watch name='Transfer' width='454' height='390'><Layers>$layers</Layers><Unknown keep='yes'/></Watch>")
            entries.putAll(assets)
            entries.forEach { (name,text) -> zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry() }
        };return path
    }
    @Test fun crossDocumentChineseImageCopyIsDurableAndSingleUndo() {
        val source=store.open(fixture("<Layer type='image' path='雪.png'><Future texture='images/雪.png'><Deep/></Future></Layer>",mapOf("images/雪.png" to "SOURCE")))
        source.selectAll(source.layers.map { it.id }); val revision=source.revision; val clipboard=source.copySelection()
        assertFalse(source.hasUnsavedChanges); assertFalse(source.canUndo); assertEquals(revision,source.revision)
        assertTrue(clipboard.contains("sourceDocumentId=\"${source.id}\"")); assertTrue(clipboard.length<4096)
        val sourceHome=source.workDir.parent; source.close(); Files.move(sourceHome,dir.resolve("source-offline"))
        store.open(fixture("",mapOf("images/雪.png" to "TARGET"))).use { target ->
            target.pasteLayers(clipboard); val added=target.layers.single(); val name=added.attrs.getValue("path")
            assertNotEquals("雪.png",name); assertEquals("SOURCE",Files.readString(target.workDir.resolve("images/$name")))
            assertEquals("TARGET",Files.readString(target.workDir.resolve("images/雪.png")))
            val output=dir.resolve("copied.watch");store.save(target,output)
            ZipFile(output.toFile()).use { zip ->
                val xml=zip.getInputStream(zip.getEntry("watch.xml")).readBytes().toString(Charsets.UTF_8)
                assertTrue(xml.contains("<Deep"));assertTrue(xml.contains("texture=\"images/$name\""))
            }
            target.undo();assertTrue(target.layers.isEmpty());assertFalse(Files.exists(target.workDir.resolve("images/$name")))
            assertFalse(target.canUndo);target.redo();assertEquals(added.id,target.layers.single().id)
        }
    }
    @Test fun vectorAndBitmapFontsCopyWithAtlasAndDistinctChineseIdentifiers() {
        val fnt="info face=\"中文\" size=20\ncommon lineHeight=20 base=16 scaleW=1 scaleH=1 pages=1\npage id=0 file=\"图集.png\"\n"
        store.open(fixture("<Layer type='text' font='中文'/><Layer type='text' font='bm:位图'/><Layer type='chart' font='中文' font2='bm:位图'/>",
            mapOf("fonts/中文.ttf" to "SOURCE_FONT","fonts_bm/位图.fnt" to fnt,"fonts_bm/图集.png" to "SOURCE_ATLAS"))).use { source ->
            source.selectAll(source.layers.map { it.id });val clipboard=source.copySelection()
            store.open(fixture("",mapOf("fonts/中文.otf" to "TARGET_FONT","fonts_bm/位图.fnt" to fnt,"fonts_bm/图集.png" to "TARGET_ATLAS"))).use { target ->
                target.pasteLayers(clipboard)
                val vector=target.layers[0].attrs.getValue("font");val bitmap=target.layers[1].attrs.getValue("font").removePrefix("bm:")
                assertNotEquals("中文",vector);assertNotEquals("位图",bitmap)
                assertEquals("SOURCE_FONT",Files.readString(target.workDir.resolve("fonts/$vector.ttf")))
                val descriptor=Files.readString(target.workDir.resolve("fonts_bm/$bitmap.fnt"))
                val atlas=Regex("file=\"([^\"]+)\"").find(descriptor)!!.groupValues[1]
                assertNotEquals("图集.png",atlas);assertEquals("SOURCE_ATLAS",Files.readString(target.workDir.resolve("fonts_bm/$atlas")))
                assertEquals(vector,target.layers[2].attrs["font"]);assertEquals("bm:$bitmap",target.layers[2].attrs["font2"])
                assertEquals("TARGET_ATLAS",Files.readString(target.workDir.resolve("fonts_bm/图集.png")))
                target.undo();assertTrue(target.layers.isEmpty());assertFalse(target.canUndo)
            }
        }
    }
    @Test fun missingOrTamperedClipboardPackageCannotPartiallyPaste() {
        store.open(fixture("<Layer type='image' path='a.png'/>",mapOf("images/a.png" to "SOURCE"))).use { source ->
            source.selectAll(source.layers.map { it.id });val clipboard=source.copySelection()
            val transfer=Regex("transfer=\"([^\"]+)\"").find(clipboard)!!.groupValues[1]
            val manifest=store.workspaceRoot.resolve("clipboard/$transfer/manifest.json")
            Files.writeString(manifest,"invalid")
            store.newDocument().use { target ->
                assertThrows(WatchDocumentException.InvalidCommand::class.java) { target.pasteLayers(clipboard) }
                assertTrue(target.layers.isEmpty());assertFalse(target.canUndo)
                assertFalse(Files.exists(target.workDir.resolve("images/a.png")))
            }
        }
    }
    @Test fun missingAndDynamicAssetReferencesAreRejectedWithoutMutation() {
        store.open(fixture("<Layer type='image' path=\"'frame_'..{ds}..'.png'\"/>")).use { source ->
            source.selectAll(source.layers.map { it.id })
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { source.copySelection() }
            assertFalse(source.hasUnsavedChanges);assertFalse(source.canUndo)
        }
        store.newDocument().use { target ->
            assertThrows(WatchDocumentException.InvalidCommand::class.java) { target.pasteLayers("<WFToolsLayers version='1'><Layer type='image' path='missing.png'/></WFToolsLayers>") }
            assertTrue(target.layers.isEmpty());assertFalse(target.canUndo)
        }
    }
    @Test fun incompatibleReplacementBatchIsRejectedBeforeImportingAnything() {
        val png=dir.resolve("图片.png");val mp4=dir.resolve("视频.mp4");Files.writeString(png,"IMAGE");Files.writeString(mp4,"VIDEO")
        store.open(fixture("<Layer type='text'/><Layer type='image'/><Layer type='video'/><Layer type='image_gif'/>")).use { session ->
            val before=session.layers
            for ((ids,path,attr) in listOf(Triple(setOf(before[0].id),png,"font"),Triple(setOf(before[1].id),mp4,"path"),
                Triple(setOf(before[2].id),png,"path"),Triple(setOf(before[1].id,before[2].id),png,"path"),Triple(setOf(before[0].id),png,"arbitrary"))) {
                assertThrows(WatchDocumentException.AssetImportFailed::class.java) { session.replaceAssetForLayers(ids,listOf(path),attr) }
                assertEquals(before,session.layers);assertFalse(session.canUndo)
                assertFalse(Files.exists(session.workDir.parent.resolve("blobs")))
            }
            session.replaceAssetForLayers(setOf(before[3].id),listOf(png),"path")
            assertEquals("图片.png",session.layers[3].attrs["path"])
        }
    }
    @Test fun staticUnknownFolderReferencesCopyWholeTreeWithOneRemap() {
        store.open(fixture("<Layer type='future' resource='images/相册'><Unknown keep='yes'/></Layer>",
            mapOf("images/相册/第一张.png" to "ONE","images/相册/子目录/第二张.png" to "TWO"))).use { source ->
            source.selectAll(source.layers.map { it.id })
            store.open(fixture("",mapOf("images/相册/第一张.png" to "OLD"))).use { target ->
                target.pasteLayers(source.copySelection())
                val folder=target.layers.single().attrs.getValue("resource")
                assertNotEquals("images/相册",folder)
                assertEquals("ONE",Files.readString(target.workDir.resolve("$folder/第一张.png")))
                assertEquals("TWO",Files.readString(target.workDir.resolve("$folder/子目录/第二张.png")))
                assertEquals("OLD",Files.readString(target.workDir.resolve("images/相册/第一张.png")))
                target.undo();assertTrue(target.layers.isEmpty());assertFalse(target.canUndo)
            }
        }
    }
    @Test fun soundActionsFollowRendererSfxFolderAndExtensionFallback() {
        store.open(fixture("<Layer type='shape' tap_action='play_sound:提示'/><Layer type='shape' tap_action='sfx:提示'/>",mapOf("sfx/提示.mp3" to "SOURCE_SOUND"))).use { source ->
            source.selectAll(source.layers.map { it.id })
            store.open(fixture("",mapOf("sfx/提示.mp3" to "TARGET_SOUND"))).use { target ->
                target.pasteLayers(source.copySelection())
                val name=target.layers[0].attrs.getValue("tap_action").substringAfter(':')
                assertNotEquals("提示.mp3",name)
                assertEquals("sfx:$name",target.layers[1].attrs["tap_action"])
                assertEquals("SOURCE_SOUND",Files.readString(target.workDir.resolve("sfx/$name")))
            }
        }
    }
    @Test(timeout=1000) fun directoryAllocatorRejectsBlockedAncestorWithoutLooping() {
        assertThrows(WatchDocumentException.InvalidCommand::class.java) { AssetNameAllocator(listOf("bundle")).reserveDirectory("bundle/icons") }
        assertThrows(WatchDocumentException.InvalidCommand::class.java) { AssetNameAllocator(listOf("images")).reserveDirectory("images") }
        val allocator=AssetNameAllocator(listOf("bundle/old.png"))
        assertEquals("bundle_2",allocator.reserveDirectory("bundle"))
        assertThrows(WatchDocumentException.InvalidCommand::class.java) { allocator.reserveDirectory("bundle_2/icons") }
    }
    @Test fun blockedFolderPasteIsTransactional() {
        store.open(fixture("<Layer type='future' resource='bundle/icons'/>",mapOf("bundle/icons/图.png" to "SOURCE"))).use { source ->
            source.selectAll(source.layers.map { it.id })
            store.open(fixture("",mapOf("bundle" to "ORIGINAL_FILE"))).use { target ->
                assertThrows(WatchDocumentException.InvalidCommand::class.java) { target.pasteLayers(source.copySelection()) }
                assertTrue(target.layers.isEmpty());assertFalse(target.canUndo)
                assertEquals("ORIGINAL_FILE",Files.readString(target.workDir.resolve("bundle")))
            }
        }
    }
    @Test fun namespaceDeclarationsAndExternalMetadataLinksRemainVerbatim() {
        store.open(fixture("<Layer type='shape'><Future xmlns='https://example.invalid/schema' href='https://example.invalid/info'><Nested/></Future></Layer>")).use { source ->
            source.selectAll(source.layers.map { it.id })
            store.newDocument().use { target ->
                target.pasteLayers(source.copySelection())
                val path=dir.resolve("namespace.watch");store.save(target,path)
                ZipFile(path.toFile()).use { zip ->
                    val xml=zip.getInputStream(zip.getEntry("watch.xml")).readBytes().toString(Charsets.UTF_8)
                    assertTrue(xml.contains("https://example.invalid/schema"));assertTrue(xml.contains("https://example.invalid/info"));assertTrue(xml.contains("<Nested"))
                }
            }
        }
    }
    @Test fun conditionalWeatherTransfersNightImageWithCoupledConflictNames() {
        store.open(fixture("<Layer type='image_cond' path='天气.png' weather_night='Y'/>",
            mapOf("images/天气.png" to "DAY", "images/天气_n.png" to "NIGHT"))).use { source ->
            source.selectAll(source.layers.map { it.id });val copied=source.copySelection()
            for (conflict in listOf("images/天气.png", "images/天气_n.png")) {
                store.open(fixture("",mapOf(conflict to "EXISTING"))).use { target ->
                    target.pasteLayers(copied)
                    val day=target.layers.single().attrs.getValue("path")
                    val night=day.substringBeforeLast('.')+"_n."+day.substringAfterLast('.')
                    assertNotEquals("天气.png",day)
                    assertEquals("DAY",Files.readString(target.workDir.resolve("images/$day")))
                    assertEquals("NIGHT",Files.readString(target.workDir.resolve("images/$night")))
                    assertEquals("EXISTING",Files.readString(target.workDir.resolve(conflict)))
                    target.undo();assertTrue(target.layers.isEmpty());assertFalse(target.canUndo)
                    assertFalse(Files.exists(target.workDir.resolve("images/$day")));assertFalse(Files.exists(target.workDir.resolve("images/$night")))
                    target.redo();assertEquals("NIGHT",Files.readString(target.workDir.resolve("images/$night")))
                }
            }
        }
    }
    @Test fun conditionalWeatherWithoutNightImageKeepsDayFallbackInDestination() {
        store.open(fixture("<Layer type='image_cond' path='天气.png' weather_night='Y'/>",mapOf("images/天气.png" to "DAY"))).use { source ->
            source.selectAll(source.layers.map { it.id })
            store.open(fixture("",mapOf("images/天气_n.png" to "UNRELATED_NIGHT"))).use { target ->
                target.pasteLayers(source.copySelection())
                val day=target.layers.single().attrs.getValue("path")
                assertNotEquals("天气.png",day)
                assertEquals("DAY",Files.readString(target.workDir.resolve("images/$day")))
                assertFalse(Files.exists(target.workDir.resolve("images/"+day.substringBeforeLast('.')+"_n."+day.substringAfterLast('.'))))
            }
        }
    }
    @Test fun fallbackTapAliasCopiesAndRemapsSoundDependency() {
        store.open(fixture("<Layer type='shape' tap_action='' tap='play_sound:提示'/><Layer type='shape' tap='sfx:提示'/>",mapOf("sfx/提示.mp3" to "SOURCE_SOUND"))).use { source ->
            source.selectAll(source.layers.map { it.id })
            store.open(fixture("",mapOf("sfx/提示.mp3" to "TARGET_SOUND"))).use { target ->
                target.pasteLayers(source.copySelection())
                val name=target.layers[0].attrs.getValue("tap").substringAfter(':')
                assertNotEquals("提示",name);assertEquals("sfx:$name",target.layers[1].attrs["tap"])
                assertEquals("SOURCE_SOUND",Files.readString(target.workDir.resolve("sfx/$name")))
                assertEquals("TARGET_SOUND",Files.readString(target.workDir.resolve("sfx/提示.mp3")))
                target.undo();assertTrue(target.layers.isEmpty());assertFalse(target.canUndo)
            }
        }
    }
}
