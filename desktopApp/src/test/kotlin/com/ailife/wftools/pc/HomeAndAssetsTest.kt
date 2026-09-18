@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.ailife.wftools.pc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.ailife.wftools.pc.core.WatchDocumentStore
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

class HomeAndAssetsTest {
    @get:Rule val compose=createComposeRule()
    @get:Rule val temp=TemporaryFolder()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val docs=mutableListOf<DocumentController>()
    private var app: EditorApplicationModel?=null
    @After fun close(){val all=(docs+app?.documents.orEmpty()).distinct();runBlocking{withTimeoutOrNull(5000){while(all.any{it.busy})delay(5)}};all.forEach{runCatching{it.render.close()};runCatching{it.session.close()}};scope.cancel()}
    @Test fun `home shows recent projects session recovery and both real author credits`() {
        val store=WatchDocumentStore(temp.newFolder().toPath());val preferences=EditorPreferences(temp.newFolder().toPath())
        val source=temp.root.toPath().resolve("recent.watch")
        store.newDocument().use{it.setRootAttribute("name","最近的测试表盘");store.save(it,source)}
        preferences.recordRecent(source,"最近的测试表盘");preferences.recordSession(listOf(source))
        val model=EditorApplicationModel(scope,preferences,store).also{app=it}
        runBlocking{withTimeout(5000){while(model.recentProjects.isEmpty()||model.lastSession.isEmpty())delay(5)}}
        compose.setContent{WFToolsTheme(false){Surface(Modifier.fillMaxSize()){HomeContent(model,{}, {},Modifier.fillMaxSize())}}}
        compose.onNodeWithText("最近打开").assertExists();compose.onNodeWithText("最近的测试表盘").assertExists()
        compose.onNodeWithTag("developer-attribution",useUnmergedTree=true).assertTextContains("柚子皮",substring=true)
        compose.onNodeWithText("QQ · 3513903055").assertIsDisplayed()
        compose.onNodeWithTag("developer-profile-link").assertHasClickAction()
        compose.onNodeWithText("吃葡萄不吐柚子皮ぃ · 哔哩哔哩").assertDoesNotExist()
        compose.onNodeWithTag("upstream-attribution",useUnmergedTree=true).assertTextContains("AILIFE",substring=true)
        compose.onNodeWithText("继续上次的会话").assertExists()
        compose.onNodeWithText("恢复未保存的草稿").assertDoesNotExist()
        val settings = compose.onNodeWithTag("home-settings").getUnclippedBoundsInRoot()
        val profile = compose.onNodeWithTag("developer-profile-link").getUnclippedBoundsInRoot()
        val upstream = compose.onNodeWithTag("upstream-attribution",useUnmergedTree=true).getUnclippedBoundsInRoot()
        assertTrue("设置应固定放在上游链接上方",settings.bottom <= upstream.top)
        assertTrue("开发者点击区只能覆盖头像和左侧文字",profile.right <= upstream.left)
        compose.onNodeWithTag("continue-session").performClick()
        compose.waitUntil(5000){model.documents.size==1&&model.status==null}
        assertEquals(source.toAbsolutePath(),model.documents.single().view.source)
    }

    @Test fun `recent context menu separates deleting the record from deleting the watch file`() = runBlocking {
        val store=WatchDocumentStore(temp.newFolder("recent-delete-work").toPath());val preferences=EditorPreferences(temp.newFolder("recent-delete-prefs").toPath())
        val source=temp.root.toPath().resolve("recent-delete.watch")
        store.newDocument().use { store.save(it,source) }
        preferences.recordRecent(source,"要保留的原文件")
        val model=EditorApplicationModel(scope,preferences,store).also{app=it}
        withTimeout(5000){while(model.recentProjects.isEmpty())delay(5)}
        compose.setContent{WFToolsTheme(false){Surface(Modifier.fillMaxSize()){HomeContent(model,{}, {},Modifier.fillMaxSize())}}}
        val recentNode=compose.onNodeWithTag("recent-project-${source.fileName}")
        val recentBounds=recentNode.getUnclippedBoundsInRoot()
        recentNode.performMouseInput{click(center+Offset(100f,0f),button=MouseButton.Secondary)}
        compose.onNodeWithTag("recent-context-menu").assertExists()
        val menuBounds=compose.onNodeWithTag("recent-context-menu").getUnclippedBoundsInRoot()
        assertTrue("最近项目菜单必须锚定右键位置，而不是列表左边",menuBounds.left > recentBounds.left + (recentBounds.right-recentBounds.left)*.45f)
        compose.onNode(hasText("删除记录") and hasAnyAncestor(hasTestTag("recent-context-menu"))).assertExists()
        compose.onNode(hasText("删除表盘") and hasAnyAncestor(hasTestTag("recent-context-menu"))).assertExists()
        compose.onNode(hasText("删除记录") and hasAnyAncestor(hasTestTag("recent-context-menu"))).performClick()
        withTimeout(5000){while(model.recentProjects.isNotEmpty())delay(5)}
        assertTrue("删除记录不能删除原始表盘",Files.isRegularFile(source))
    }
    @Test fun `continue opens only the last active document and prefers its exact recovery draft`() = runBlocking {
        val store=WatchDocumentStore(temp.newFolder("resume-work").toPath());val preferences=EditorPreferences(temp.newFolder("resume-prefs").toPath())
        val firstPath=temp.root.toPath().resolve("first.watch");val lastPath=temp.root.toPath().resolve("last.watch")
        val first=store.newDocument();store.save(first,firstPath);first.setRootAttribute("name","first draft");first.close()
        val last=store.newDocument();store.save(last,lastPath);last.setRootAttribute("name","last active draft");val lastId=last.id;last.close()
        preferences.recordRecent(firstPath,"first");preferences.recordRecent(lastPath,"last");preferences.saveLastActive(lastPath,lastId)
        val model=EditorApplicationModel(scope,preferences,store).also{app=it}
        model.refreshRecovery().join()
        withTimeout(5000){while(model.lastActivePath==null)delay(5)}
        model.continueSession()
        withTimeout(5000){while(model.documents.size!=1||model.status!=null)delay(5)}
        assertEquals(lastId,model.documents.single().session.id)
        assertEquals("last active draft",model.documents.single().view.title)
        assertEquals(1,model.documents.size)
    }
    @Test fun `last unsaved draft is restored only through the last session button`() = runBlocking {
        val store=WatchDocumentStore(temp.newFolder("last-draft-work").toPath());val preferences=EditorPreferences(temp.newFolder("last-draft-prefs").toPath())
        val draft=store.newDocument();draft.setRootAttribute("name","最后未保存表盘");val id=draft.id;draft.close()
        preferences.recordRecentDraft(id,"最后未保存表盘");preferences.saveLastActive(null,id)
        val model=EditorApplicationModel(scope,preferences,store).also{app=it};model.refreshRecovery().join()
        withTimeout(5000){while(model.lastActiveDraftId==null||model.recovery.none{it.id==id})delay(5)}
        compose.setContent{WFToolsTheme(false){Surface(Modifier.fillMaxSize()){HomeContent(model,{}, {},Modifier.fillMaxSize())}}}
        compose.onNodeWithText("继续上次的会话").assertIsDisplayed()
        compose.onNodeWithText("恢复未保存的草稿").assertDoesNotExist()
        compose.onNodeWithTag("continue-session").performClick()
        withTimeout(5000){while(model.documents.none{it.session.id==id}||model.status!=null)delay(5)}
        assertEquals("最后未保存表盘",model.documents.single().view.title)
    }
    @Test fun `home settings offers an in-app watch association action instead of opening Windows settings`() {
        val store=WatchDocumentStore(temp.newFolder("settings-work").toPath());val preferences=EditorPreferences(temp.newFolder("settings-prefs").toPath())
        val model=EditorApplicationModel(scope,preferences,store).also{app=it}
        compose.setContent{WFToolsTheme(false){Surface(Modifier.fillMaxSize()){HomeContent(model,{}, {},Modifier.fillMaxSize())}}}
        compose.onNodeWithTag("home-settings").performClick()
        compose.onNodeWithTag("watch-association-action").assertExists()
        compose.onNodeWithText("在 Windows 设置中管理文件关联").assertDoesNotExist()
    }

    @Test fun `home settings explains canvas controls and edits the shared input switches`() {
        val preferences=EditorPreferences(temp.newFolder("canvas-settings-prefs").toPath())
        val model=EditorApplicationModel(scope,preferences,WatchDocumentStore(temp.newFolder("canvas-settings-work").toPath())).also{app=it}
        compose.setContent{WFToolsTheme(false){Surface(Modifier.fillMaxSize()){HomeContent(model,{}, {},Modifier.fillMaxSize())}}}
        compose.onNodeWithTag("home-settings").performClick()
        compose.onNodeWithTag("home-middle-button-pan-toggle").assertExists()
        compose.onNodeWithTag("home-right-button-pan-toggle").assertExists().performClick()
        compose.onNodeWithTag("home-wheel-layer-scale-toggle").assertExists()
        compose.onNodeWithTag("home-automatic-snapping-toggle").assertExists().performClick()
        compose.onNodeWithTag("home-transform-handles-toggle").assertExists()
        compose.onNodeWithTag("home-operation-status-toggle").assertExists()
        compose.onNodeWithText("侧边栏选中的图层优先",substring=true).assertExists()
        compose.onNodeWithText("空格 + 左键",substring=true).assertExists()
        compose.runOnIdle{assertFalse(model.canvasInput.rightButtonPan);assertFalse(model.canvasInput.snapping);assertTrue(model.canvasInput.middleButtonPan)}
    }

    @Test fun `home offers a market entry that opens the store window without duplicating the web action`() {
        val model=EditorApplicationModel(scope,EditorPreferences(temp.newFolder("market-prefs").toPath()),WatchDocumentStore(temp.newFolder("market-work").toPath())).also{app=it}
        var marketOpened=false
        val opened=mutableListOf<String>()
        compose.setContent{WFToolsTheme(false){Surface(Modifier.fillMaxSize()){HomeContent(model,{}, {},Modifier.fillMaxSize(),openExternal={opened+=it},onMarket={marketOpened=true})}}}
        compose.onNodeWithTag("home-watch-market").assertExists().performClick()
        compose.runOnIdle{assertTrue("点击卡片应打开市场窗口",marketOpened)}
        compose.onNodeWithTag("home-market-web").assertDoesNotExist()
        compose.runOnIdle{assertTrue("首页不应再偷偷打开浏览器",opened.isEmpty())}
    }

    @Test fun `home keeps header and footer fixed while only the recent list scrolls`() {
        val store=WatchDocumentStore(temp.newFolder("fixed-home-work").toPath());val preferences=EditorPreferences(temp.newFolder("fixed-home-prefs").toPath())
        repeat(14){index->val source=temp.root.toPath().resolve("recent-$index.watch");store.newDocument().use{store.save(it,source)};preferences.recordRecent(source,"最近表盘 $index")}
        val model=EditorApplicationModel(scope,preferences,store).also{app=it}
        runBlocking{withTimeout(5000){while(model.recentProjects.size<14)delay(5)}}
        compose.setContent{WFToolsTheme(false){Surface(Modifier.width(900.dp).height(720.dp)){HomeContent(model,{}, {},Modifier.fillMaxSize())}}}
        val headerBefore=compose.onNodeWithTag("home-fixed-header").getUnclippedBoundsInRoot()
        val footerBefore=compose.onNodeWithTag("home-fixed-footer").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("home-recent-list").performScrollToIndex(13)
        val headerAfter=compose.onNodeWithTag("home-fixed-header").getUnclippedBoundsInRoot()
        val footerAfter=compose.onNodeWithTag("home-fixed-footer").getUnclippedBoundsInRoot()
        assertEquals(headerBefore.top,headerAfter.top);assertEquals(footerBefore.bottom,footerAfter.bottom)
    }
    @Test fun `five logo clicks open the hidden bilibili video`() {
        val model=EditorApplicationModel(scope,EditorPreferences(temp.newFolder("egg-prefs").toPath()),WatchDocumentStore(temp.newFolder("egg-work").toPath())).also{app=it}
        val opened=mutableListOf<String>()
        compose.setContent{WFToolsTheme(false){Surface(Modifier.fillMaxSize()){HomeContent(model,{}, {},Modifier.fillMaxSize(),openExternal={opened+=it})}}}
        repeat(4){compose.onNodeWithTag("home-logo").performClick()}
        compose.runOnIdle{assertTrue(opened.isEmpty())}
        compose.onNodeWithTag("home-logo").performClick()
        compose.runOnIdle{assertEquals(listOf("https://www.bilibili.com/video/BV1GJ411x7h7"),opened)}
    }
    private fun assets(): Pair<DocumentController,java.nio.file.Path> = runBlocking {
        val source=temp.root.toPath().resolve("red.png")
        val bitmap=BufferedImage(20,20,BufferedImage.TYPE_INT_ARGB);for(y in 0 until 20)for(x in 0 until 20)bitmap.setRGB(x,y,0xffff2244.toInt())
        ImageIO.write(bitmap,"png",source.toFile())
        val store=WatchDocumentStore(temp.newFolder().toPath());val preferences=EditorPreferences(temp.newFolder().toPath());val session=store.newDocument();session.importAssets(listOf(source),createLayers=false)
        val doc=DocumentController.create(session,store,scope,preferences).also{it.render.playing=false;docs.add(it)}
        val model=EditorApplicationModel(scope,preferences,store).also{app=it};val commands=CommandDispatcher(model,doc){}
        compose.setContent{WFToolsTheme(false){Surface(Modifier.fillMaxSize()){EditorWorkspace(model,doc,commands,PanelPreferences(),{},DropRegistry())}}}
        compose.onNodeWithText("素材").performClick()
        doc to source
    }
    @Test fun `asset card renders source pixels and internal drag creates a layer without copying another resource`() {
        val (doc,source)=assets();val before=Files.readAllBytes(source)
        val card=compose.onNodeWithTag("asset-card-images/red.png")
        compose.waitUntil(5000){val image=card.captureToImage();val pixels=image.toPixelMap();var count=0;for(y in 0 until image.height)for(x in 0 until image.width){val c=pixels[x,y];if(c.red>.85f&&c.green<.25f&&c.blue<.4f)count++};count>100}
        card.performMouseInput{moveTo(center);press();moveTo(center+Offset(-60f,0f))}
        compose.onNodeWithTag("watch-preview").performMouseInput{moveTo(center);release()}
        compose.waitUntil(5000){!doc.busy&&doc.view.layers.size==1}
        assertEquals("image",doc.view.layers.single().type);assertEquals("red.png",doc.view.layers.single().attrs["path"])
        assertEquals("Existing assets are reused, not duplicated",1,doc.view.assets.size);assertArrayEquals(before,Files.readAllBytes(source))
    }
    @Test fun `material asset delete menu preserves referenced resources and allows deletion after removing references`() {
        val (doc,source)=assets();doc.createLayerFromAsset(doc.view.assets.single())
        compose.waitUntil(5000){!doc.busy&&doc.view.layers.size==1}
        fun requestDelete(){compose.onNodeWithTag("asset-card-images/red.png").performMouseInput{click(button=MouseButton.Secondary)};compose.onNodeWithTag("asset-context-menu").assertExists();compose.onNode(hasText("删除素材") and hasAnyAncestor(hasTestTag("asset-context-menu"))).performClick();compose.onNodeWithText("删除",substring=false).performClick()}
        requestDelete();compose.waitUntil(5000){!doc.busy&&doc.error!=null}
        assertEquals(1,doc.view.assets.size);assertEquals("red.png",doc.view.layers.single().attrs["path"])
        compose.runOnIdle{doc.command{deleteSelected()}};compose.waitUntil(5000){!doc.busy&&doc.view.layers.isEmpty()}
        requestDelete();compose.waitUntil(5000){!doc.busy&&doc.view.assets.isEmpty()}
        assertTrue(Files.isRegularFile(source))
        compose.runOnIdle{doc.command(refreshAssets=true){undo()}};compose.waitUntil(5000){!doc.busy&&doc.view.assets.size==1}
    }
}
