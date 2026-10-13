package co.cben.dev.aiinlinereview.crit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CritModelTest {
    private fun fixture(name: String) = javaClass.getResource("/crit/$name")!!.readText()

    @Test
    fun `parses session info`() {
        val session = CritJson.parse<SessionInfo>(fixture("session.json"))
        assertEquals("git", session.mode)
        assertEquals("abc123", session.baseRef)
        assertEquals(2, session.reviewRound)
        assertEquals(listOf("src/App.kt", "README.md"), session.files.map { it.path })
        assertTrue(session.files[1].orphaned)
        assertEquals("r_aaa111", session.reviewComments.single().id)
    }

    @Test
    fun `parses comments and turns null lists into empty lists`() {
        val comments = CritJson.parse<List<Comment>>(fixture("file-comments.json"))
        assertEquals(1, comments[0].replies.size)
        assertTrue(comments[0].carriedForward)
        assertEquals(emptyList<Reply>(), comments[1].replies)
        assertTrue(comments[1].isOld)
        assertTrue(comments[1].resolved)
    }

    @Test
    fun `missing lists default to empty`() {
        val comment = CritJson.parse<Comment>("""{"id":"c_1","body":"x"}""")
        assertEquals(emptyList<Reply>(), comment.replies)
    }

    @Test
    fun `parses registry entry`() {
        val entry = CritJson.parse<SessionEntry>(fixture("session-entry.json"))
        assertEquals("http://127.0.0.1:42815", entry.baseUrl)
        assertFalse(entry.isPlan)
        assertTrue(entry.copy(args = listOf("/home/me/.crit/plans/x/current.md")).isPlan)
    }

    @Test
    fun `parses config, health and finish`() {
        assertEquals("Reviewer", CritJson.parse<CritConfig>(fixture("config.json")).author)
        assertEquals(1, CritJson.parse<Health>(fixture("health.json")).apiVersion)
        val finish = CritJson.parse<FinishResult>(fixture("finish.json"))
        assertFalse(finish.approved)
        assertEquals(2, finish.stats.commentsSubmitted)
    }

    @Test
    fun `writes drafts in snake case`() {
        val json = CritJson.gson.toJson(CommentDraft(startLine = 3, endLine = 4, body = "b", author = "me"))
        assertTrue(json, "\"start_line\":3" in json && "\"end_line\":4" in json)
    }
}
