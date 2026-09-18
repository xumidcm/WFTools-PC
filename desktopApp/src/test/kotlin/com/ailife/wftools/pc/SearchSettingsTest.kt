package com.ailife.wftools.pc

import com.ailife.wftools.pc.platform.DesktopOperatingSystem
import org.junit.Assert.*
import org.junit.Test

/** Contracts for the search-result settings and the market's platform-dependent actions. */
class SearchSettingsTest {
    @Test fun `search result count clamps to the range Tavily accepts`() {
        assertEquals(SearchResultCount.MIN, SearchResultCount.clamp(0))
        assertEquals(SearchResultCount.MIN, SearchResultCount.clamp(-4))
        assertEquals(SearchResultCount.MAX, SearchResultCount.clamp(21))
        assertEquals(7, SearchResultCount.clamp(7))
    }

    @Test fun `search result count parses input and falls back to the default`() {
        assertEquals(12, SearchResultCount.parse("12"))
        assertEquals(SearchResultCount.MAX, SearchResultCount.parse("999"))
        assertEquals(SearchResultCount.DEFAULT, SearchResultCount.parse(""))
        assertEquals(SearchResultCount.DEFAULT, SearchResultCount.parse("abc"))
    }

    @Test fun `in-range reporting drives the field error state`() {
        assertTrue(SearchResultCount.isInRange("1"))
        assertTrue(SearchResultCount.isInRange("20"))
        assertFalse(SearchResultCount.isInRange("0"))
        assertFalse(SearchResultCount.isInRange("99"))
        assertFalse(SearchResultCount.isInRange(""))
    }

    @Test fun `search depth accepts only the two Tavily values`() {
        assertEquals(SearchDepth.ADVANCED, SearchDepth.parse("advanced"))
        assertEquals(SearchDepth.ADVANCED, SearchDepth.parse(" Advanced "))
        assertEquals(SearchDepth.BASIC, SearchDepth.parse("basic"))
        assertEquals(SearchDepth.BASIC, SearchDepth.parse("deep"))
        assertEquals(SearchDepth.BASIC, SearchDepth.parse(null))
        assertEquals("advanced", SearchDepth.ADVANCED.wire)
    }

    @Test fun `search depth labels do not collide with the thinking-depth dropdown`() {
        // DesktopAgentPanel reuses the plain "快速"/"深度" strings for reasoning effort, so the
        // search chips must use distinct keys or their English translations would be wrong.
        assertNotEquals("快速", SearchDepth.BASIC.label)
        assertNotEquals("深度", SearchDepth.ADVANCED.label)
    }

    @Test fun `every market action is offered where the platform supports it`() {
        listOf(DesktopOperatingSystem.WINDOWS, DesktopOperatingSystem.LINUX).forEach { os ->
            val actions = marketDownloadActions(os)
            assertTrue(actions.contains(MarketDownloadAction.OPEN_IN_EDITOR))
            assertTrue(actions.contains(MarketDownloadAction.SAVE_AS))
            assertTrue(actions.contains(MarketDownloadAction.COPY_PATH))
        }
    }

    @Test fun `revealing the download is hidden when no file manager command exists`() {
        assertTrue(marketDownloadActions(DesktopOperatingSystem.LINUX, fileManagerAvailable = true).contains(MarketDownloadAction.REVEAL_IN_FILE_MANAGER))
        assertFalse(marketDownloadActions(DesktopOperatingSystem.LINUX, fileManagerAvailable = false).contains(MarketDownloadAction.REVEAL_IN_FILE_MANAGER))
    }

    @Test fun `the reveal label names the platform's file manager`() {
        assertEquals("在资源管理器中显示", marketRevealLabel(DesktopOperatingSystem.WINDOWS))
        assertEquals("在文件管理器中显示", marketRevealLabel(DesktopOperatingSystem.LINUX))
    }

    @Test fun `the market destinations stay on https`() {
        assertTrue(WatchMarket.HOME_URL.startsWith("https://"))
        assertTrue(WatchMarket.API_ROOT.startsWith("https://"))
    }
}
