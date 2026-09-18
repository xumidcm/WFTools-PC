package com.ailife.wftools.pc.market

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer

class MarketAccountApiTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeTransport(private val responseBody: String, private val status: Int = 200) : MarketAccountTransport {
        val requests = mutableListOf<MarketAccountRequest>()
        override fun execute(request: MarketAccountRequest): MarketAccountResponse {
            requests += request
            return MarketAccountResponse(status, responseBody.toByteArray(StandardCharsets.UTF_8), "application/json")
        }
    }

    private class ThrowingTransport(private val failure: Exception) : MarketAccountTransport {
        override fun execute(request: MarketAccountRequest): MarketAccountResponse = throw failure
    }

    @Test fun `login sends credentials and parses the returned bearer session`() {
        val transport = FakeTransport("""
            {"token":"jwt-token","user":{"id":"u1","username":"sensei","role":"user"}}
        """.trimIndent())
        val session = MarketAccountApi("https://example.test/api", transport).login("sensei", "secret1")
        assertEquals("jwt-token", session.token)
        assertEquals("sensei", session.user.username)
        assertEquals("POST", transport.requests.single().method)
        assertTrue(transport.requests.single().url.endsWith("/auth/login"))
        assertTrue(String(requireNotNull(transport.requests.single().body), StandardCharsets.UTF_8).contains("\"username\":\"sensei\""))
    }

    @Test fun `dashboard endpoints attach the jwt and parse owned faces and notifications`() {
        val transport = FakeTransport("""
            {"data":[{"id":"wf1","name":"晨光","description":"demo","screen_type":"square","status":"pending",
              "category":{"id":"c1","name":"Analog","slug":"analog"},"tags":["demo"],"wm_version":"2.0",
              "wm_protected":true,"layer_count":4,"download_count":2,"favorites":1,"views":3,"comment_count":0,
              "created_at":"2026-09-17T00:00:00Z"}],"unread":2}
        """.trimIndent())
        val api = MarketAccountApi("https://example.test/api", transport)
        val faces = api.myWatchfaces("jwt-token")
        assertEquals("wf1", faces.single().id)
        assertTrue(faces.single().protected)
        val notifications = api.notifications("jwt-token")
        assertEquals(2, notifications.unread)
        assertTrue(transport.requests.all { it.headers["Authorization"] == "Bearer jwt-token" })
    }

    @Test fun `parse and upload use the two-step multipart contract`() {
        val file = temp.newFile("face.watch").toPath().also { Files.writeString(it, "watch-bytes") }
        val transport = FakeTransport("""
            {"parsed":{"title":"晨光","description":"demo","tags":["demo"],"screen_type":"square","wm_version":"2.0","wm_protected":false,"layer_count":4},
             "scan":{"success":true,"verdict":"pass","errors":[],"warnings":[]}}
        """.trimIndent())
        val api = MarketAccountApi("https://example.test/api", transport)
        val parsed = api.parseWatchface("jwt-token", file)
        assertEquals("晨光", parsed.parsed.title)
        assertEquals("file", transport.requests.single().multipart?.file?.field)
        assertEquals("face.watch", transport.requests.single().multipart?.file?.path?.fileName?.toString())

        val uploadTransport = FakeTransport("""
            {"id":"wf1","name":"晨光","description":"demo","screen_type":"square","status":"pending",
             "category":{"id":"c1","name":"Analog","slug":"analog"},"tags":["demo"]}
        """.trimIndent(), status = 201)
        MarketAccountApi("https://example.test/api", uploadTransport)
            .uploadWatchface("jwt-token", file, MarketWatchfaceForm("晨光", "demo", "square", "c1", "demo"))
        val upload = uploadTransport.requests.single()
        assertTrue(upload.url.endsWith("/my/watchfaces"))
        assertEquals("c1", upload.multipart?.fields?.get("category_id"))
        assertEquals("Bearer jwt-token", upload.headers["Authorization"])
    }

    @Test fun `non success responses become actionable account errors`() {
        val transport = FakeTransport("{\"error\":\"Account disabled\"}", status = 403)
        val error = assertThrows(MarketAccountException::class.java) {
            MarketAccountApi("https://example.test/api", transport).login("sensei", "secret1")
        }
        assertEquals(403, error.status)
        assertEquals("Account disabled", error.message)
    }

    @Test fun `domain transport errors are not relabeled as network failures`() {
        val error = assertThrows(MarketAccountException::class.java) {
            MarketAccountApi("https://example.test/api", ThrowingTransport(MarketAccountException("本地文件不可读"))).login("sensei", "secret1")
        }
        assertEquals("本地文件不可读", error.message)
    }

    @Test fun `missing upload files fail before a network request`() {
        val transport = FakeTransport("{}")
        val missing = temp.root.toPath().resolve("missing.watch")
        val error = assertThrows(MarketAccountException::class.java) {
            MarketAccountApi("https://example.test/api", transport).parseWatchface("jwt-token", missing)
        }
        assertTrue(error.message!!.contains("找不到可读取的文件"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `profile updates use the backend patch contract`() {
        val transport = FakeTransport("""{"id":"u1","username":"sensei","description":"new","timezone":"Asia/Shanghai","language":"zh-CN"}""")
        val account = MarketAccountApi("https://example.test/api", transport)
            .updateProfile("jwt-token", description = "new", timezone = "Asia/Shanghai", language = "zh-CN")
        assertEquals("new", account.description)
        val request = transport.requests.single()
        assertEquals("PATCH", request.method)
        assertTrue(request.url.endsWith("/my/profile"))
        assertEquals("Bearer jwt-token", request.headers["Authorization"])
        assertTrue(String(requireNotNull(request.body), StandardCharsets.UTF_8).contains("Asia/Shanghai"))
    }

    @Test fun `real transport can send patch without falling back to an unsupported jdk method`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/profile") { exchange ->
            assertEquals("PATCH", exchange.requestMethod)
            exchange.requestBody.use { it.readBytes() }
            val body = "{\"ok\":true}".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val response = UrlMarketAccountTransport().execute(
                MarketAccountRequest(
                    method = "PATCH",
                    url = "http://127.0.0.1:${server.address.port}/profile",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "{}".toByteArray(StandardCharsets.UTF_8),
                )
            )
            assertEquals(200, response.status)
            assertEquals("{\"ok\":true}", String(response.body, StandardCharsets.UTF_8))
        } finally {
            server.stop(0)
        }
    }

    @Test fun `real transport does not follow redirects for authenticated requests`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var redirected = false
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            redirected = true
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.start()
        try {
            val response = UrlMarketAccountTransport().execute(
                MarketAccountRequest(
                    method = "GET",
                    url = "http://127.0.0.1:${server.address.port}/redirect",
                    headers = mapOf("Authorization" to "Bearer secret-token"),
                )
            )
            assertEquals(302, response.status)
            assertFalse(redirected)
        } finally {
            server.stop(0)
        }
    }
}
