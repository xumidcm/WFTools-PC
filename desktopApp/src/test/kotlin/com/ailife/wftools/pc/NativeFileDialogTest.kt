package com.ailife.wftools.pc

import com.ailife.wftools.pc.platform.DesktopOperatingSystem
import com.ailife.wftools.pc.platform.WindowsCommonDialog
import com.ailife.wftools.pc.platform.fileDialogFilters
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Contracts for the native file dialog filters and the Windows common item dialog.
 *
 * The dialog itself cannot be driven headlessly, but its COM vtable slots are the one part the
 * compiler cannot check, so [WindowsCommonDialog.verifyOptionRoundTrip] calls the real object.
 */
class NativeFileDialogTest {
    @Test fun `an empty extension list asks for every file`() {
        val filters = fileDialogFilters(null, "打开")
        assertEquals(1, filters.size)
        assertEquals("*.*", filters.single().pattern)
    }

    @Test fun `a single extension becomes one named filter plus everything`() {
        val filters = fileDialogFilters("watch", "打开 WatchMaker 表盘")
        assertEquals("打开 WatchMaker 表盘 (*.watch)", filters.first().label)
        assertEquals("*.watch", filters.first().pattern)
        assertEquals("*.*", filters.last().pattern)
    }

    @Test fun `mixed extensions are grouped and every extension stays reachable`() {
        val filters = fileDialogFilters("png,jpg,mp4,ttf,mp3", "导入素材")
        val patterns = filters.map { it.pattern }
        // Each kind gets its own group so the dropdown reads like a real filter list.
        assertTrue("图片组缺失", patterns.any { it.contains("*.png") && it.contains("*.jpg") })
        assertTrue("视频组缺失", patterns.any { it.contains("*.mp4") })
        assertTrue("字体组缺失", patterns.any { it.contains("*.ttf") })
        assertTrue("音频组缺失", patterns.any { it.contains("*.mp3") })
        listOf("png", "jpg", "mp4", "ttf", "mp3").forEach { extension ->
            assertTrue("$extension 必须出现在某个过滤器中", patterns.any { it.contains("*.$extension") })
        }
    }

    @Test fun `the all supported entry covers every requested extension`() {
        val filters = fileDialogFilters("png,mp4", "导入素材")
        val all = filters.first()
        assertTrue(all.pattern.contains("*.png"))
        assertTrue(all.pattern.contains("*.mp4"))
    }

    @Test fun `filter patterns never repeat`() {
        val filters = fileDialogFilters("png,png,jpg", "导入")
        assertEquals(filters.size, filters.map { it.pattern }.distinct().size)
    }

    @Test fun `extension matching ignores case and stray separators`() {
        val filters = fileDialogFilters("PNG, *.jpg", "导入")
        assertTrue(filters.any { it.pattern.contains("*.png") })
        assertTrue(filters.any { it.pattern.contains("*.jpg") })
    }

    @Test fun `com vtable offsets scale with the pointer size`() {
        // Slot 0 is the first function after the vtable pointer; on 64-bit each entry is 8 bytes.
        assertEquals(0L, WindowsCommonDialog.vtableOffset(0, 8))
        assertEquals(8L, WindowsCommonDialog.vtableOffset(1, 8))
        assertEquals(160L, WindowsCommonDialog.vtableOffset(20, 8))
        // A 32-bit build must not read 64-bit slots.
        assertEquals(80L, WindowsCommonDialog.vtableOffset(20, 4))
    }

    @Test fun `a negative vtable slot is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WindowsCommonDialog.vtableOffset(-1) }
        assertThrows(IllegalArgumentException::class.java) { WindowsCommonDialog.vtableOffset(1, 0) }
    }

    @Test fun `the common item dialog resolves set options and get options on the real com object`() {
        val os = System.getProperty("os.name").orEmpty()
        assumeTrue("仅在 Windows 上验证 COM 虚表", os.contains("Win", ignoreCase = true))
        val roundTrip = WindowsCommonDialog.verifyOptionRoundTrip()
        assertNotNull("Windows 上应能创建 IFileDialog", roundTrip)
        assertTrue("SetOptions/GetOptions 的虚表槽位必须正确", roundTrip == true)
        assertTrue(
            "COM 对话框必须在专用 STA 线程执行，避免 AWT/Compose 线程 apartment 冲突",
            WindowsCommonDialog.lastApartmentThreadName?.startsWith("WFTools-FileDialog-STA") == true,
        )
    }

    @Test fun `the dialog is only attempted on Windows`() {
        assertEquals(
            System.getProperty("os.name").orEmpty().contains("Win", ignoreCase = true),
            WindowsCommonDialog.usable,
        )
    }

    @Test fun `every desktop platform has a reveal label`() {
        DesktopOperatingSystem.entries.forEach { os ->
            assertTrue("$os 缺少文件管理器文案", marketRevealLabel(os).isNotBlank())
        }
    }
}
