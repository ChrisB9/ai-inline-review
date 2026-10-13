package co.cben.dev.aiinlinereview.crit

import co.cben.dev.aiinlinereview.crit.FakeCrit.Canned
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CritClientWriteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fake = FakeCrit()
    private val client = CritClient(fake.baseUrl)
    private val comment = """{"id":"c_1","start_line":3,"end_line":4,"body":"b"}"""

    @After
    fun tearDown() = fake.close()

    @Test
    fun `creates a line comment`() {
        fake.on("POST", "/api/file/comments", Canned(201, comment))
        val created = client.createComment("src/App.kt", CommentDraft(startLine = 3, endLine = 4, body = "b", author = "me", quote = "q"))
        assertEquals("c_1", created.id)
        val request = fake.requests.last()
        assertEquals("path=src%2FApp.kt", request.query)
        assertEquals("application/json", request.contentType)
        val sent = CritJson.parse<CommentDraft>(request.body)
        assertEquals(CommentDraft(startLine = 3, endLine = 4, body = "b", author = "me", quote = "q"), sent)
    }

    @Test
    fun `creates a review comment`() {
        fake.on("POST", "/api/comments", Canned(201, """{"id":"r_1","body":"all good","scope":"review"}"""))
        assertEquals("r_1", client.createReviewComment("all good", "me").id)
        assertEquals("""{"body":"all good","author":"me"}""", fake.requests.last().body)
    }

    @Test
    fun `routes file and review comments differently`() {
        fake.on("PUT", "/api/comment/c_1", comment)
        fake.on("PUT", "/api/review-comment/r_1", """{"id":"r_1"}""")
        client.editComment("src/App.kt", "c_1", "new")
        assertEquals("path=src%2FApp.kt", fake.requests.last().query)
        client.editComment(null, "r_1", "new")
        assertEquals(null, fake.requests.last().query)
        assertEquals("""{"body":"new"}""", fake.requests.last().body)
    }

    @Test
    fun `deletes, resolves and replies`() {
        fake.on("DELETE", "/api/comment/c_1", """{"status":"deleted"}""")
        fake.on("PUT", "/api/comment/c_1/resolve", comment)
        fake.on("POST", "/api/review-comment/r_1/replies", Canned(201, """{"id":"rp_1","body":"ok","author":"me"}"""))
        client.deleteComment("src/App.kt", "c_1")
        client.resolve("src/App.kt", "c_1", false)
        assertEquals("""{"resolved":false}""", fake.requests.last().body)
        assertEquals("rp_1", client.reply(null, "r_1", "ok", "me").id)
        assertEquals(listOf("DELETE", "PUT", "POST"), fake.requests.map { it.method })
    }

    @Test
    fun `finishes and asks the agent`() {
        fake.on("POST", "/api/finish", fake.fixture("finish.json"))
        fake.on("POST", "/api/agent/request", Canned(202, """{"status":"accepted"}"""))
        assertFalse(client.finish().approved)
        client.askAgent("src/App.kt", "c_1")
        assertEquals("""{"comment_id":"c_1","file_path":"src/App.kt"}""", fake.requests.last().body)
    }

    @Test
    fun `uploads an image as multipart`() {
        val image = tmp.newFile("shot.png").toPath().also { it.toFile().writeBytes(byteArrayOf(1, 2, 3)) }
        fake.on("POST", "/api/attachments", Canned(201, """{"filename":"u.png","url":"attachments/u.png"}"""))
        assertEquals("attachments/u.png", client.upload(image).url)
        val request = fake.requests.last()
        assertTrue(request.contentType!!.startsWith("multipart/form-data; boundary="))
        assertTrue(request.body.contains("""name="file"; filename="shot.png""""))
    }
}
