package com.ailife.wftools.pc.market

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

/** Contracts for the Watchmaker Community v2 client. No test here touches the network. */
class WatchMarketApiTest {
    private class RecordingTransport(private val body: String = "{}", private val failOn: String? = null) : MarketTransport {
        val urls = mutableListOf<String>()
        override fun get(url: String, maxBytes: Int): ByteArray {
            urls += url
            if (failOn != null && url.contains(failOn)) throw MarketException("boom")
            return body.toByteArray(StandardCharsets.UTF_8)
        }
    }

    private val listBody = """
        {"data":[
          {"id":"a1","name":"魈","screen_type":"square","category":{"id":"c1","name":"Analog","slug":"analog"},
           "download_count":12,"favorite_count":1,"views":33,"tags":["极简","插画"],"wm_version":"1.0",
           "created_at":"2026-09-17T14:48:45.639Z","layer_count":4},
          {"id":"b2","name":"田野","screen_type":"round","category":{"name":"Digital"},
           "download_count":5,"favorite_count":0,"views":7,"tags":[],"wm_version":"2.0","created_at":"2026-09-16T10:00:00.000Z"}
        ],"pagination":{"total":137,"page":1}}
    """.trimIndent()

    @Test fun `parses a listing with nested category and pagination total`() {
        val api = WatchMarketApi(RecordingTransport(listBody))
        val page = api.list()
        assertEquals(137, page.total)
        assertEquals(2, page.items.size)
        val first = page.items.first()
        assertEquals("a1", first.id)
        assertEquals("魈", first.name)
        assertEquals("Analog", first.category)
        assertEquals("square", first.screenType)
        assertEquals(12, first.downloadCount)
        assertEquals(listOf("极简", "插画"), first.tags)
        assertEquals("2026-09-17", first.createdAt)
    }

    @Test fun `missing optional fields degrade instead of failing the page`() {
        val api = WatchMarketApi(RecordingTransport("""{"data":[{"id":"x"}]}"""))
        val item = api.list().items.single()
        assertEquals("Watchface x", item.name)
        assertEquals("", item.category)
        assertEquals(0, item.downloadCount)
        assertTrue(item.tags.isEmpty())
    }

    @Test fun `a nameless entry still gets a usable title`() {
        val api = WatchMarketApi(RecordingTransport("""{"data":[{"id":"z9","name":""}]}"""))
        assertEquals("Watchface z9", api.list().items.single().name)
    }

    @Test fun `pagination total falls back to the page size when absent`() {
        val api = WatchMarketApi(RecordingTransport("""{"data":[{"id":"a"},{"id":"b"}]}"""))
        assertEquals(2, api.list().total)
    }

    @Test fun `the request carries page limit and sort`() {
        val transport = RecordingTransport(listBody)
        WatchMarketApi(transport).list(page = 3, limit = 25, sort = MarketSort.POPULAR)
        val url = transport.urls.single()
        assertTrue(url, url.contains("page=3"))
        assertTrue(url, url.contains("limit=25"))
        assertTrue(url, url.contains("sort=popular"))
    }

    @Test fun `category and search are percent encoded`() {
        val transport = RecordingTransport(listBody)
        WatchMarketApi(transport).list(category = "极简 风格", search = "a&b=c")
        val url = transport.urls.single()
        assertTrue("分类必须编码", url.contains("category=%E6%9E%81%E7%AE%80+%E9%A3%8E%E6%A0%BC"))
        assertFalse("不能出现裸 & 或 =", url.contains("search=a&b=c"))
        assertTrue(url.contains("search=a%26b%3Dc"))
    }

    @Test fun `any screen type omits the parameter entirely`() {
        val transport = RecordingTransport(listBody)
        WatchMarketApi(transport).list(screenType = MarketScreenType.ANY)
        assertFalse(transport.urls.single().contains("screen_type"))
    }

    @Test fun `a concrete screen type is sent`() {
        val transport = RecordingTransport(listBody)
        WatchMarketApi(transport).list(screenType = MarketScreenType.ROUND)
        assertTrue(transport.urls.single().contains("screen_type=round"))
    }

    @Test fun `an out of range page size is clamped to the server maximum`() {
        val transport = RecordingTransport(listBody)
        WatchMarketApi(transport).list(limit = 5000)
        assertTrue(transport.urls.single().contains("limit=${WatchMarketApi.MAX_LIMIT}"))
    }

    @Test fun `malformed json raises a typed error rather than crashing`() {
        val api = WatchMarketApi(RecordingTransport("not json at all"))
        assertThrows(MarketException::class.java) { api.list() }
    }

    @Test fun `a transport failure surfaces as a market error`() {
        val api = WatchMarketApi(RecordingTransport(listBody, failOn = "/watchfaces"))
        assertThrows(MarketException::class.java) { api.list() }
    }

    @Test fun `detail returns uploader and description`() {
        val body = """{"id":"a1","name":"魈","description":"说明","uploader":{"username":"柚子皮"},"tags":["x"]}"""
        val detail = WatchMarketApi(RecordingTransport(body)).detail("a1")
        assertNotNull(detail)
        assertEquals("柚子皮", detail!!.author)
        assertEquals("说明", detail.description)
    }

    @Test fun `detail failure returns null instead of throwing`() {
        assertNull(WatchMarketApi(RecordingTransport("{}", failOn = "/watchfaces")).detail("a1"))
    }

    @Test fun `categories parse into slug and name pairs`() {
        val body = """{"data":[{"slug":"analog","name":"Analog"},{"slug":"","name":"Broken"},{"slug":"digital"}]}"""
        val categories = WatchMarketApi(RecordingTransport(body)).categories()
        assertEquals(listOf("analog" to "Analog"), categories)
    }

    @Test fun `a failing category request degrades to an empty list`() {
        assertTrue(WatchMarketApi(RecordingTransport("{}", failOn = "/categories")).categories().isEmpty())
    }

    @Test fun `download returns the archive bytes`() {
        val bytes = WatchMarketApi(RecordingTransport("PK\u0003\u0004payload")).download("a1")
        assertTrue(bytes.isNotEmpty())
        assertEquals("PK", String(bytes.copyOfRange(0, 2), StandardCharsets.UTF_8))
    }

    @Test fun `a failing preview degrades to null`() {
        assertNull(WatchMarketApi(RecordingTransport("{}", failOn = "/preview")).preview("a1"))
    }

    @Test fun `sort and screen type parse unknown values back to their defaults`() {
        assertEquals(MarketSort.LATEST, MarketSort.parse("nonsense"))
        assertEquals(MarketSort.LATEST, MarketSort.parse(null))
        assertEquals(MarketSort.TRENDING, MarketSort.parse(" trending "))
        assertEquals(MarketScreenType.ANY, MarketScreenType.parse("nonsense"))
        assertEquals(MarketScreenType.SQUARE, MarketScreenType.parse("square"))
    }

    @Test fun `the client targets the documented community endpoint`() {
        val transport = RecordingTransport(listBody)
        WatchMarketApi(transport).list()
        assertTrue(transport.urls.single().startsWith("https://jbwatchface.ai-life.xyz/api/watchfaces"))
    }
}
