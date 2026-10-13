package co.cben.dev.aiinlinereview.crit

import co.cben.dev.aiinlinereview.crit.FakeCrit.Canned
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CritClientReadTest {
    private val fake = FakeCrit()
    private val sleeps = mutableListOf<Long>()
    private val client = CritClient(fake.baseUrl, sleep = { sleeps += it })

    @After
    fun tearDown() = fake.close()

    @Test
    fun `reads session`() {
        fake.on("GET", "/api/session", fake.fixture("session.json"))
        assertEquals("abc123", client.session().baseRef)
    }

    @Test
    fun `encodes awkward paths in the query`() {
        fake.on("GET", "/api/file/comments", "[]")
        client.fileComments("src/My File#1&ü.kt")
        assertEquals("path=src%2FMy+File%231%26%C3%BC.kt", fake.requests.last().query)
    }

    @Test
    fun `retries while crit is loading`() {
        val loading = Canned(503, """{"status":"loading"}""", mapOf("Retry-After" to "1"))
        fake.on("GET", "/api/config", loading, Canned(200, fake.fixture("config.json")))
        assertEquals("Reviewer", client.config().author)
        assertEquals(listOf(1000L), sleeps)
    }

    @Test
    fun `gives up after ten seconds of loading`() {
        fake.on("GET", "/api/config", Canned(503, "", mapOf("Retry-After" to "1")))
        val error = assertThrows(CritHttpException::class.java) { client.config() }
        assertEquals(503, error.status)
        assertEquals(10_000L, sleeps.sum())
    }

    @Test
    fun `surfaces http errors with body`() {
        fake.on("GET", "/api/session", Canned(500, "boom"))
        val error = assertThrows(CritHttpException::class.java) { client.session() }
        assertEquals("crit HTTP 500: boom", error.message)
    }

    @Test
    fun `accepts api version 1 only`() {
        fake.on("GET", "/api/health", fake.fixture("health.json"))
        assertEquals(1, client.requireSupported().apiVersion)
        fake.on("GET", "/api/health", """{"status":"ok","api_version":2}""")
        assertEquals(2, assertThrows(UnsupportedCritApi::class.java) { client.requireSupported() }.apiVersion)
        fake.on("GET", "/api/health", """{"status":"ok"}""")
        assertEquals(0, assertThrows(UnsupportedCritApi::class.java) { client.requireSupported() }.apiVersion)
    }
}
