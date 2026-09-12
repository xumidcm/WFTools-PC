package com.ailife.wftools.pc

import com.ailife.wftools.pc.platform.RegistryAccess
import com.ailife.wftools.pc.platform.WindowsWatchFileAssociation
import com.ailife.wftools.pc.platform.decodeRegistryOutput
import com.ailife.wftools.pc.platform.nativeDialogIconImages
import com.ailife.wftools.pc.platform.registryAddArguments
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.Charset

/** Regression contracts for Windows shell and media-import integration. */
class PlatformCorrectionsRedTest {
    @get:Rule val temp=TemporaryFolder()

    private class FakeRegistry:RegistryAccess {
        val values=mutableMapOf<Pair<String,String?>,String>()
        override fun read(key:String,name:String?)=values[key to name]
        override fun write(key:String,name:String?,value:String,type:String){values[key to name]=value}
        override fun deleteValue(key:String,name:String?){values.remove(key to name)}
    }

    @Test fun `watch association writes the current launcher and restores the previous default`() {
        val registry=FakeRegistry();val extension="HKCU\\Software\\Classes\\.watch"
        registry.write(extension,null,"Other.Watch")
        val launcher=Path.of("C:/Program Files/WFTools-PC/WFTools-PC.exe")
        val association=WindowsWatchFileAssociation(registry){launcher}
        assertFalse(association.isAssociated())
        association.associate()
        assertEquals("ProgID.WFToolsPC.watch",registry.read(extension,null))
        assertEquals("\"$launcher\" \"%1\"",registry.read("HKCU\\Software\\Classes\\ProgID.WFToolsPC.watch\\shell\\open\\command",null))
        assertTrue(association.isAssociated())
        association.unassociate()
        assertEquals("Other.Watch",registry.read(extension,null))
    }

    @Test fun `failed shell registration leaves the previous watch default untouched`() {
        val extension="HKCU\\Software\\Classes\\.watch"
        val values=mutableMapOf<Pair<String,String?>,String>(extension to null to "Other.Watch")
        val registry=object:RegistryAccess {
            override fun read(key:String,name:String?)=values[key to name]
            override fun write(key:String,name:String?,value:String,type:String) {
                if(key.endsWith("\\shell\\open\\command")) error("shell write failed")
                values[key to name]=value
            }
            override fun deleteValue(key:String,name:String?){values.remove(key to name)}
        }
        val association=WindowsWatchFileAssociation(registry){Path.of("C:/Program Files/WFTools-PC/WFTools-PC.exe")}

        assertThrows(IllegalStateException::class.java){association.associate()}

        assertEquals("Other.Watch",registry.read(extension,null))
    }

    @Test fun `association action refreshes the real state even after a late failure`() {
        var actual=false

        val result=performFileAssociationAction(
            currentlyAssociated=false,
            associate={actual=true;error("late shell failure")},
            unassociate={actual=false},
            readAssociated={actual},
        )

        assertTrue(result.associated)
        assertEquals("late shell failure",result.error)
    }

    @Test fun `quoted registry data remains one Windows process argument`() {
        val key="HKCU\\Software\\Classes\\ProgID.WFToolsPC.watch\\shell\\open\\command"
        val command="\"C:\\Program Files\\WFTools-PC\\WFTools-PC.exe\" \"%1\""
        assertEquals(listOf("reg.exe","add",key,"/ve","/t","REG_SZ","/d","\\\"C:\\Program Files\\WFTools-PC\\WFTools-PC.exe\\\" \\\"%1\\\"","/f"),registryAddArguments(key,null,command,"REG_SZ"))
    }

    @Test fun `registry errors use the Windows native character encoding`() {
        val message="错误：参数无效。"
        assertEquals(message,decodeRegistryOutput(message.toByteArray(Charset.forName("GBK")),Charset.forName("GBK")))
    }

    @Test fun `native file dialogs always receive the rounded WFTools icon`() {
        val icons=nativeDialogIconImages(null)
        assertTrue(icons.isNotEmpty())
        assertTrue(icons.all{it.getWidth(null)>0&&it.getWidth(null)==it.getHeight(null)})
    }

    @Test fun `non h264 videos are transcoded to temporary mp4 while h264 files remain untouched`() {
        val image=temp.newFile("photo.jpg").toPath();val h264=temp.newFile("ready.mp4").toPath();val vp9=temp.newFile("source.webm").toPath()
        Files.writeString(image,"image");Files.writeString(h264,"ready");Files.writeString(vp9,"vp9")
        val transcoded=mutableListOf<Pair<Path,Path>>()
        val preparer=VideoImportPreparer(
            codec={path->if(path==vp9)"vp9" else "h264"},
            transcode={source,target->transcoded+=source to target;Files.writeString(target,"converted")},
        )
        val prepared=preparer.prepare(listOf(image,h264,vp9))
        val converted=prepared.paths.last()
        assertEquals(listOf(vp9),transcoded.map{it.first})
        assertEquals(image,prepared.paths[0]);assertEquals(h264,prepared.paths[1])
        assertTrue(converted.fileName.toString().endsWith(".mp4"));assertEquals("converted",Files.readString(converted))
        prepared.close()
        assertFalse(Files.exists(converted))
    }
}
