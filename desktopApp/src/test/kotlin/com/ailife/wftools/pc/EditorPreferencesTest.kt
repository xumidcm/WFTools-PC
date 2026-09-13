package com.ailife.wftools.pc

import com.ailife.wftools.pc.preview.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class EditorPreferencesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `agent defaults off and persists independently of normal preferences`() {
        val dir = temp.newFolder().toPath(); val preferences = EditorPreferences(dir)
        assertFalse(preferences.agentSettings().enabled)
        preferences.saveAgentSettings(preferences.agentSettings().copy(enabled = true, defaultModelId = "vers-1"))
        preferences.saveApplication(ThemePreference.DARK, PanelPreferences(), WindowPreferences())
        val restored = EditorPreferences(dir).agentSettings()
        assertTrue(restored.enabled); assertEquals("vers-1", restored.defaultModelId)
    }
    @Test fun `per-file viewport workspace and simulation survive restart without sharing between files`() {
        val dir = temp.newFolder().toPath(); val preferences = EditorPreferences(dir)
        val value = PreviewPreferences(DevicePreviewSettings(320,386,ScreenShape.ROUND,CanvasPreset.STRETCH,1.5,.7,20.0,-15.0), WorkspaceViewState(false,2.0,45.0,30.0), mapOf("steps" to "1234", "weatherLocation" to "北京"),snapping = false,operationMode = CanvasOperationMode.SELECT_THEN_DRAG)
        preferences.savePreview("C:/one.watch", value)
        assertEquals(value, EditorPreferences(dir).preview("C:/one.watch"))
        assertEquals(PreviewPreferences(), preferences.preview("C:/two.watch"))
        assertFalse(Files.list(dir).use { files -> files.anyMatch { it.fileName.toString().endsWith(".watch") } })
    }
    @Test fun `malformed or out of range preferences fall back without throwing`() {
        val dir = temp.newFolder().toPath(); val preferences = EditorPreferences(dir)
        preferences.savePreview("one", PreviewPreferences())
        val file = Files.list(dir).use { it.findFirst().get() }
        Files.writeString(file,"width=3\nheight=999999\nshape=TRIANGLE\npreset=invalid\nzoom=NaN\nzoomX=Infinity\noffsetY=hello\noperationMode=UNKNOWN\nsnapping=invalid\n")
        assertEquals(PreviewPreferences(), preferences.preview("one"))
        Files.writeString(dir.resolve("application.properties"),"width=1\nheight=oops\nleft=-500\nright=Infinity\ntheme=invalid\n")
        assertEquals(WindowPreferences(), preferences.window()); assertEquals(PanelPreferences(), preferences.panels()); assertEquals(ThemePreference.SYSTEM, preferences.theme())
    }
    @Test fun `theme divider and window choices survive independent preview writes`() {
        val preferences = EditorPreferences(temp.newFolder().toPath())
        val panels = PanelPreferences(220f,330f,180f); val window = WindowPreferences(1100,600)
        preferences.saveApplication(ThemePreference.DARK,panels,window)
        preferences.savePreview("face",PreviewPreferences(DevicePreviewSettings(410,502)))
        assertEquals(ThemePreference.DARK,preferences.theme()); assertEquals(panels,preferences.panels()); assertEquals(window,preferences.window())
    }
    @Test fun `application preferences persist desktop language`() {
        val dir = temp.newFolder().toPath(); val preferences = EditorPreferences(dir)
        DesktopI18n.setLanguage(DesktopLanguage.ENGLISH)
        preferences.saveApplication(ThemePreference.SYSTEM, PanelPreferences(), WindowPreferences())
        assertEquals(DesktopLanguage.ENGLISH, EditorPreferences(dir).savedLanguage())
        DesktopI18n.setLanguage(DesktopLanguage.CHINESE)
    }
    @Test fun `canvas input switches are application wide and survive restart`() {
        val dir=temp.newFolder().toPath();val preferences=EditorPreferences(dir)
        assertEquals(CanvasInputPreferences(),preferences.canvasInput())
        val changed=CanvasInputPreferences(middleButtonPan=false,rightButtonPan=true,wheelScalesSelectedLayer=false,
            snapping=false,showTransformHandles=false,showOperationStatus=false)
        preferences.saveApplication(ThemePreference.LIGHT,PanelPreferences(),WindowPreferences(),changed)
        assertEquals(changed,EditorPreferences(dir).canvasInput())
        preferences.savePreview("face",PreviewPreferences())
        assertEquals(changed,EditorPreferences(dir).canvasInput())
    }
    @Test fun `transform handles default on and device preview defaults off with independent saved choices`() {
        val preferences=EditorPreferences(temp.newFolder().toPath())
        val initial=preferences.preview("face")
        assertTrue(initial.showTransformHandles);assertFalse(initial.devicePreviewEnabled)
        preferences.savePreview("face",initial.copy(showTransformHandles=false,devicePreviewEnabled=true,snapping=false,operationMode=CanvasOperationMode.SELECT_THEN_DRAG))
        val restored=preferences.preview("face")
        assertFalse(restored.showTransformHandles);assertTrue(restored.devicePreviewEnabled);assertFalse(restored.snapping);assertEquals(CanvasOperationMode.SELECT_THEN_DRAG,restored.operationMode)
        assertEquals(PreviewPreferences(),preferences.preview("other-face"))
    }
    @Test fun `document shape owns both normal canvas and device simulation masks`() {
        val hidden = PreviewPreferences(device = DevicePreviewSettings(320,386,ScreenShape.ROUND),devicePreviewEnabled = false)
        assertEquals(DevicePreviewSettings(512,512,ScreenShape.DOCUMENT,CanvasPreset.FIT),effectivePreviewDevice(hidden))
        val enabled = hidden.copy(devicePreviewEnabled = true)
        assertEquals(320,effectivePreviewDevice(enabled).width);assertEquals(386,effectivePreviewDevice(enabled).height)
        assertEquals("Device simulation must not override the saved watch shape",ScreenShape.DOCUMENT,effectivePreviewDevice(enabled).shape)
    }
    @Test fun `last active document is persisted separately from recent project history`() {
        val dir = temp.newFolder().toPath(); val preferences = EditorPreferences(dir)
        preferences.recordRecent(java.nio.file.Path.of("C:/old.watch"),"旧表盘")
        preferences.recordRecent(java.nio.file.Path.of("C:/last.watch"),"最后表盘")
        preferences.saveLastActive(java.nio.file.Path.of("C:/last.watch"))
        val restored = EditorPreferences(dir)
        assertEquals(java.nio.file.Path.of("C:/last.watch").toAbsolutePath().normalize(),restored.lastActive())
        assertEquals(2,restored.recentProjects().size)
    }

    @Test fun `recent records can be removed without touching the source and drafts have stable entries`() {
        val dir = temp.newFolder().toPath(); val preferences = EditorPreferences(dir)
        val source = dir.resolve("face.watch")
        preferences.recordRecent(source,"已保存表盘")
        assertEquals(0,preferences.removeRecent(RecentProject(source.toString(),"已保存表盘",0L)).size)
        assertTrue(preferences.recentProjects().isEmpty())
        preferences.recordRecentDraft("draft-123","未命名表盘")
        val draft = preferences.recentProjects().single()
        assertEquals("draft-123",draft.draftId)
        assertEquals("未命名表盘",draft.title)
        assertEquals(0,preferences.removeRecent(draft).size)
        assertTrue(preferences.recentProjects().isEmpty())
    }

    @Test fun `recording a saved project ignores synthetic draft paths`() {
        val preferences = EditorPreferences(temp.newFolder().toPath())
        preferences.recordRecentDraft("1230d3b4-42a4-4232-972f-9c7a1f1e5455", "未保存草稿")

        val saved = temp.root.toPath().resolve("saved.watch")
        val records = preferences.recordRecent(saved, "已保存表盘")

        assertEquals("已保存表盘", records.first().title)
        assertTrue(records.any { it.draftId != null })
    }
}
