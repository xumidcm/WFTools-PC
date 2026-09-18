@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.ailife.wftools.pc.market

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.ailife.wftools.pc.MarketDownloadAction
import com.ailife.wftools.pc.WFToolsTheme
import com.ailife.wftools.pc.WatchMarket
import com.ailife.wftools.pc.marketDownloadActions
import com.ailife.wftools.pc.marketRevealLabel
import com.ailife.wftools.pc.platform.DesktopOperatingSystem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** UI contracts for the market window and its post-download dialog. */
class WatchMarketWindowTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val listBody = """
        {"data":[{"id":"a1","name":"魈","screen_type":"square","category":{"name":"Analog"},
         "download_count":12,"favorite_count":1,"views":33,"tags":["极简"],"wm_version":"1.0",
         "created_at":"2026-09-17T14:48:45.639Z"}],"pagination":{"total":40}}
    """.trimIndent()

    private fun api(body: String = listBody) = WatchMarketApi(MarketTransport { _, _ -> body.toByteArray(StandardCharsets.UTF_8) })

    @Test fun `the market offers an open in browser button pointing at the store web app`() {
        val opened = mutableListOf<String>()
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
            WatchMarketWindow(api(), temp.newFolder().toPath(), {}, {}, openExternal = { opened += it })
        } } }
        compose.onNodeWithTag("market-open-web").assertExists().performClick()
        compose.runOnIdle { assertEquals(listOf(WatchMarket.HOME_URL), opened) }
    }

    @Test fun `the store web app is the community front end and not the api root`() {
        assertEquals("https://jbwatchface.ai-life.xyz/", WatchMarket.HOME_URL)
        assertFalse("网页版不能指向 API 根", WatchMarket.HOME_URL.contains("/api"))
        assertEquals("预览资源必须统一为 512×512 方形", 512, MARKET_PREVIEW_SIZE_PX)
    }

    @Test fun `the market account button opens the native login panel`() {
        val accountApi = MarketAccountApi("https://example.test/api", MarketAccountTransport { request ->
            val body = if (request.url.endsWith("/config")) "{\"features\":{\"registration_enabled\":true,\"max_upload_size_mb\":20}}" else "{}"
            MarketAccountResponse(200, body.toByteArray(StandardCharsets.UTF_8), "application/json")
        })
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
            WatchMarketWindow(api(), temp.newFolder().toPath(), {}, {}, accountApi = accountApi)
        } } }
        compose.onNodeWithTag("market-account-button").assertExists().performClick()
        compose.onNodeWithTag("market-account-dialog").assertExists()
        compose.onNodeWithTag("market-auth-username").assertExists()
        compose.onNodeWithTag("market-auth-password").assertExists()
        compose.onNodeWithTag("market-login-submit").assertExists()
        compose.onNodeWithTag("market-register-tab").assertExists()
    }

    @Test fun `the listing renders cards from the api`() {
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
            WatchMarketWindow(api(), temp.newFolder().toPath(), {}, {})
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("market-card-a1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("魈").assertExists()
        compose.onNodeWithText("已加载 1 / 40 项").assertExists()
        compose.onNodeWithText("下一页").assertDoesNotExist()
    }

    @Test fun `an api failure shows a retry affordance instead of an empty grid`() {
        val failing = WatchMarketApi(MarketTransport { _, _ -> throw MarketException("市场返回 HTTP 500") })
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
            WatchMarketWindow(failing, temp.newFolder().toPath(), {}, {})
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("市场返回 HTTP 500").assertExists()
    }

    @Test fun `the market uses continuous scrolling instead of page buttons`() {
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
            WatchMarketWindow(api(), temp.newFolder().toPath(), {}, {})
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("market-card-a1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("上一页").assertDoesNotExist()
        compose.onNodeWithText("下一页").assertDoesNotExist()
    }

    @Test fun `scrolling near the end requests and appends the next batch`() {
        fun page(start: Int, count: Int, total: Int): String = buildString {
            append("{\"data\":[")
            repeat(count) { index ->
                val id = "scroll-${start + index}"
                if (index > 0) append(',')
                append("{\"id\":\"$id\",\"name\":\"$id\",\"screen_type\":\"square\",\"category\":{\"name\":\"Analog\"},\"created_at\":\"2026-09-17\"}")
            }
            append("],\"pagination\":{\"total\":$total}}")
        }
        val requested = mutableListOf<String>()
        val transport = MarketTransport { url, _ ->
            requested += url
            when {
                url.contains("/watchfaces?") && url.contains("page=2") -> page(20, 1, 21).toByteArray(StandardCharsets.UTF_8)
                url.contains("/watchfaces?") -> page(0, 20, 21).toByteArray(StandardCharsets.UTF_8)
                else -> "{\"data\":[]}".toByteArray(StandardCharsets.UTF_8)
            }
        }
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
            WatchMarketWindow(WatchMarketApi(transport), temp.newFolder().toPath(), {}, {})
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("market-card-scroll-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("market-grid").performScrollToIndex(19)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("market-card-scroll-20").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("滚动到底部必须请求第二批", requested.any { it.contains("page=2") })
    }

    @Test fun `protected market item shows an explicit protection badge`() {
        val protectedBody = listBody.replace("\"created_at\"", "\"wm_protected\":true,\"created_at\"")
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
            WatchMarketWindow(api(protectedBody), temp.newFolder().toPath(), {}, {})
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("market-card-a1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("market-card-a1").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("market-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("market-detail-protected").assertExists()
        compose.onNodeWithText("受保护表盘", substring = true).assertExists()
    }

    @Test fun `detail dialog offers the selected face page in the browser`() {
        val detailBody = """
            {"id":"a1","name":"魈","screen_type":"square","category":{"name":"Analog"},
             "download_count":12,"favorite_count":1,"views":33,"tags":["极简"],"wm_version":"1.0",
             "created_at":"2026-09-17T14:48:45.639Z","description":"detail"}
        """.trimIndent()
        val transport = MarketTransport { url, _ ->
            when {
                url.contains("/watchfaces?") -> listBody.toByteArray(StandardCharsets.UTF_8)
                url.endsWith("/watchfaces/a1") -> detailBody.toByteArray(StandardCharsets.UTF_8)
                url.endsWith("/categories") -> "{\"data\":[]}".toByteArray(StandardCharsets.UTF_8)
                else -> ByteArray(0)
            }
        }
        val opened = mutableListOf<String>()
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
            WatchMarketWindow(WatchMarketApi(transport), temp.newFolder().toPath(), {}, {}, openExternal = { opened += it })
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("market-card-a1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("market-card-a1").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("market-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("market-detail-open-web").assertExists().performClick()
        compose.runOnIdle { assertEquals(listOf(WatchMarket.watchFaceUrl("a1")), opened) }
    }

    @Test fun `a download dialog exposes every action the platform supports`() {
        val file = temp.newFile("face.watch").toPath()
        Files.writeString(file, "PK")
        val download = MarketDownload(file, MarketItem("a1", "魈", "", "Analog", "square", 1, 0, 0, emptyList(), "1.0", "2026-09-17"))
        var opened = false
        var revealed = false
        var copied = false
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
            MarketDownloadDialog(download, onDismiss = {}, onOpen = { opened = true }, onReveal = { revealed = true }, onSaveAs = {}, onCopyPath = { copied = true })
        } } }
        compose.onNodeWithTag("market-action-open").performClick()
        compose.onNodeWithTag("market-action-copy-path").performClick()
        compose.runOnIdle { assertTrue("打开编辑应可点击", opened); assertTrue("复制路径应可点击", copied) }
        // The reveal button exists only when the platform provides one.
        val revealPresent = compose.onAllNodesWithTag("market-action-reveal").fetchSemanticsNodes().isNotEmpty()
        assertEquals(MarketDownloadAction.REVEAL_IN_FILE_MANAGER in marketDownloadActions(), revealPresent)
        if (revealPresent) {
            compose.onNodeWithTag("market-action-reveal").performClick()
            compose.runOnIdle { assertTrue(revealed) }
        }
    }

    @Test fun `the download dialog names the file it just saved`() {
        val file = temp.newFile("田野.watch").toPath()
        Files.writeString(file, "PK")
        val download = MarketDownload(file, MarketItem("b2", "田野", "", "Digital", "round", 5, 1, 7, emptyList(), "2.0", "2026-09-16"))
        compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
            MarketDownloadDialog(download, {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("田野").assertExists()
        compose.onNodeWithText(file.toAbsolutePath().toString(), substring = true).assertExists()
    }

    @Test fun `a platform without a file manager hides only the reveal action`() {
        val gated = marketDownloadActions(DesktopOperatingSystem.LINUX, fileManagerAvailable = false)
        assertFalse(gated.contains(MarketDownloadAction.REVEAL_IN_FILE_MANAGER))
        assertTrue(gated.contains(MarketDownloadAction.OPEN_IN_EDITOR))
        assertTrue(gated.contains(MarketDownloadAction.SAVE_AS))
        assertTrue(gated.contains(MarketDownloadAction.COPY_PATH))
    }

    @Test fun `the market window routes its text through the desktop i18n layer`() {
        // The market UI lives in a subpackage, where "androidx.compose.material3.*" would win
        // over the project's localized Text and leave the English UI in Chinese.
        com.ailife.wftools.pc.DesktopI18n.setLanguage(com.ailife.wftools.pc.DesktopLanguage.ENGLISH)
        try {
            compose.setContent { WFToolsTheme(false) { Surface(Modifier.fillMaxSize()) {
                WatchMarketWindow(api(), temp.newFolder().toPath(), {}, {})
            } } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Watch face market").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Open in browser").assertExists()
            compose.onNodeWithText("表盘市场").assertDoesNotExist()
        } finally {
            com.ailife.wftools.pc.DesktopI18n.setLanguage(com.ailife.wftools.pc.DesktopLanguage.CHINESE)
        }
    }

    @Test fun `each platform reveal label matches its file manager`() {
        assertEquals("在资源管理器中显示", marketRevealLabel(DesktopOperatingSystem.WINDOWS))
        assertEquals("在文件管理器中显示", marketRevealLabel(DesktopOperatingSystem.LINUX))
        assertEquals("在 Finder 中显示", marketRevealLabel(DesktopOperatingSystem.MACOS))
    }
}
