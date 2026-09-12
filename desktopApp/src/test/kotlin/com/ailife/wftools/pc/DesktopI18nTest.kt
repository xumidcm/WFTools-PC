package com.ailife.wftools.pc

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopI18nTest {
    @Test
    fun translatesKnownChineseTextToEnglish() {
        assertEquals("Save", DesktopI18n.text("保存", Locale.ENGLISH))
    }

    @Test
    fun translatesNewDocumentDefaultTitle() {
        assertEquals("New Watch Face", DesktopI18n.text("新建表盘", Locale.ENGLISH))
        assertEquals("新建表盘", DesktopI18n.text("新建表盘", Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun keepsChineseTextForChineseLocale() {
        assertEquals("保存", DesktopI18n.text("保存", Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun fallsBackToSourceTextWhenNoTranslationExists() {
        assertEquals("project-file-name", DesktopI18n.text("project-file-name", Locale.ENGLISH))
    }

    @Test
    fun translatesDynamicStatusFragmentsWithoutChangingChineseMode() {
        assertEquals("Saving face.watch…", DesktopI18n.text("正在保存 face.watch…", Locale.ENGLISH))
        assertEquals("正在保存 face.watch…", DesktopI18n.text("正在保存 face.watch…", Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun translatesScreenshotSettingsCopyAndDirtyDocumentTitle() {
        assertEquals(
            "When disabled, WFTools shows the original 512 × 512 canvas; enable it to apply the device appearance below.",
            DesktopI18n.text("关闭时按 WFTools 的 512 × 512 原始画布显示；开启后才应用下列设备外观。", Locale.ENGLISH),
        )
        assertEquals(
            "The canvas updates immediately after switching and is written to the watch file when saved.",
            DesktopI18n.text("切换后画布立即更新，保存时写入表盘文件。", Locale.ENGLISH),
        )
        assertEquals("Save changes to “New Watch Face”?", DesktopI18n.text("保存对“新建表盘”的修改？", Locale.ENGLISH))
    }

    @Test
    fun translatesCanvasOperationGuide() {
        val source = "鼠标直拖模式：按住图层即可移动，拖动未选中的图层会切换目标。\n先选后拖模式没有选择元素时，左键拖动表盘。选择元素后，可从画布任意位置拖动选区。\n中键或右键拖动：对应开关开启时平移整个表盘。\n滚轮：开关开启时可缩放光标下的选中元素；关闭后始终缩放整个表盘。\n自动吸附会在移动和变换时对齐画布及其他元素；变换控制点可显示八向缩放点和旋转拉杆；操作状态提示只用于显示耗时任务进度。\n空格 + 左键或 Ctrl + 左键：临时拖动画布；Ctrl + 滚轮：始终缩放整个表盘。"
        assertEquals(
            "Direct-drag mode: hold a layer to move it; dragging an unselected layer switches the target.\nSelect-then-drag mode: when nothing is selected, drag the watch face with the left button. After selecting an element, drag the selection from anywhere on the canvas.\nMiddle- or right-button drag pans the whole watch face when enabled.\nWheel: when enabled, resize the selected element under the cursor; otherwise always zoom the whole watch face.\nSnapping aligns moved and transformed elements to the canvas and each other. Transform handles show eight resize points and a rotation arm. Operation status is shown only for long-running tasks.\nSpace + left button or Ctrl + left button temporarily pans the canvas; Ctrl + wheel always zooms the whole watch face.",
            DesktopI18n.text(source, Locale.ENGLISH),
        )
    }

    @Test
    fun translatesRemainingScreenshotCopy() {
        assertEquals("−1 day", DesktopI18n.text("−1天", Locale.ENGLISH))
        assertEquals("+5 min", DesktopI18n.text("+5分", Locale.ENGLISH))
        assertEquals("Manage sound files in sfx/. A confirmation is required before deletion.", DesktopI18n.text("管理 sfx/ 中的声音文件。删除前会再次确认。", Locale.ENGLISH))
        assertEquals("Upstream AILIFE · GitLab", DesktopI18n.text("上游 AILIFE · GitLab", Locale.ENGLISH))
    }
}
