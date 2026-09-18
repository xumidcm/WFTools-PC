package com.ailife.wftools.pc

import com.ailife.wftools.pc.market.MarketScreenType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        val source = "鼠标直拖模式：侧边栏选中的图层优先，按住画布任意位置拖动都会移动当前选区；没有选中时按住某个图层即可选中并直接移动。\n先选后拖模式与直拖相同，区别是没有选中时按住图层只选中、不移动。\n单击其它图层会改选它，单击空白处清空选择。\n中键或右键拖动：对应开关开启时平移整个表盘。\n滚轮：开关开启时可缩放光标下的选中元素；关闭后始终缩放整个表盘。\n自动吸附会在移动和变换时对齐画布及其他元素；变换控制点可显示八向缩放点和旋转拉杆；操作状态提示只用于显示耗时任务进度。\n空格 + 左键或 Ctrl + 左键：临时拖动画布；Ctrl + 滚轮：始终缩放整个表盘。"
        assertEquals(
            "Direct-drag mode: the layer selected in the sidebar wins, so dragging anywhere on the canvas moves the current selection; with nothing selected, holding a layer selects and moves it in one step.\nSelect-then-drag behaves the same, except that with nothing selected, holding a layer only selects it.\nClicking another layer selects it; clicking empty space clears the selection.\nMiddle- or right-button drag pans the whole watch face when enabled.\nWheel: when enabled, resize the selected element under the cursor; otherwise always zoom the whole watch face.\nSnapping aligns moved and transformed elements to the canvas and each other. Transform handles show eight resize points and a rotation arm. Operation status is shown only for long-running tasks.\nSpace + left button or Ctrl + left button temporarily pans the canvas; Ctrl + wheel always zooms the whole watch face.",
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

    @Test
    fun translatesSearchSettingsAndMarketCopy() {
        assertEquals("Result count", DesktopI18n.text("搜索条数", Locale.ENGLISH))
        assertEquals("Search depth", DesktopI18n.text("搜索深度", Locale.ENGLISH))
        assertEquals("Fast", DesktopI18n.text(SearchDepth.BASIC.label, Locale.ENGLISH))
        assertEquals("Advanced", DesktopI18n.text(SearchDepth.ADVANCED.label, Locale.ENGLISH))
        assertEquals("Watch face market", DesktopI18n.text("表盘市场", Locale.ENGLISH))
        assertEquals("Open in browser", DesktopI18n.text("在浏览器打开", Locale.ENGLISH))
    }

    @Test
    fun translatesMarketWindowCopy() {
        listOf(
            "搜索表盘" to "Search watch faces",
            "全部" to "All",
            "重试" to "Retry",
            "没有匹配的表盘" to "No matching watch faces",
            "上一页" to "Previous page",
            "下一页" to "Next page",
            "下载完成" to "Download complete",
            "打开编辑" to "Open for editing",
            "复制路径" to "Copy path",
            "路径已复制到剪贴板。" to "Path copied to the clipboard.",
            "接下来要做什么？" to "What would you like to do next?",
            "已保存到：" to "Saved to:",
        ).forEach { (source, expected) -> assertEquals(source, expected, DesktopI18n.text(source, Locale.ENGLISH)) }
    }

    @Test
    fun marketScreenTypeChipsDoNotReuseTheInspectorShapeNames() {
        // "圆形"/"方形"/"矩形" already name layer shapes in the property inspector with
        // different wording (Circle/Square/Rectangle), so the market must not inherit them.
        listOf("圆形", "方形", "矩形", "圆角矩形").forEach { shape ->
            listOf(MarketScreenType.ROUND, MarketScreenType.SQUARE, MarketScreenType.RECTANGULAR)
                .forEach { type -> assertNotEquals("市场筛选项不能复用形状名 $shape", shape, type.label) }
        }
    }

    @Test
    fun searchDepthLabelsDoNotReuseTheThinkingDepthTranslations() {
        // "快速"/"深度" already mean reasoning effort in the agent panel; reusing those keys
        // would relabel that dropdown with search wording.
        assertEquals("快速", DesktopI18n.text("快速", Locale.ENGLISH))
        assertEquals("深度", DesktopI18n.text("深度", Locale.ENGLISH))
    }
}
