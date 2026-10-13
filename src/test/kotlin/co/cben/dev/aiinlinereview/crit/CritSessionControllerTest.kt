package co.cben.dev.aiinlinereview.crit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CritSessionControllerTest {
    private val api = FakeApi()
    private var nudges = 0
    private val controller = CritSessionController(SessionEntry(key = "k"), api) { nudges++ }
    private val published = mutableListOf<CritSnapshot>()

    private fun loaded(): CritSessionController {
        controller.addListener { published += it }
        controller.load()
        return controller
    }

    @Test
    fun `rejects unsupported api`() {
        api.health = Health("ok", 2)
        assertThrows(UnsupportedCritApi::class.java) { controller.load() }
    }

    @Test
    fun `load fetches only files with comments`() {
        api.comments["a.kt"] = mutableListOf(Comment(id = "c_a"))
        loaded()
        assertEquals(listOf("c_a"), controller.snapshot!!.commentsFor("a.kt").map { it.id })
        assertEquals(listOf("fileComments a.kt"), api.calls)
    }

    @Test
    fun `create fills author, nudges and publishes`() {
        loaded()
        controller.createComment("a.kt", CommentDraft(startLine = 1, endLine = 1, body = "x"))
        assertEquals("Reviewer", controller.snapshot!!.commentsFor("a.kt").single().author)
        assertEquals(1, nudges)
        assertEquals(2, published.size)
    }

    @Test
    fun `resolve and file replies do not nudge, review replies do`() {
        api.comments["a.kt"] = mutableListOf(Comment(id = "c_a"))
        loaded()
        val review = controller.createReviewComment("overall")
        nudges = 0
        controller.resolve("a.kt", "c_a", true)
        controller.reply("a.kt", "c_a", "ok")
        assertEquals(0, nudges)
        controller.reply(null, review.id, "ok")
        assertEquals(1, nudges)
        assertEquals("Reviewer", controller.snapshot!!.reviewComments.single().replies.single().author)
    }

    @Test
    fun `refresh publishes only on change`() {
        api.comments["a.kt"] = mutableListOf(Comment(id = "c_a"))
        loaded()
        assertFalse(controller.refreshComments())
        api.comments.getValue("a.kt") += Comment(id = "c_b")
        assertTrue(controller.refreshComments())
        assertEquals(2, published.size)
    }

    @Test
    fun `file whose last comment was deleted elsewhere drops out`() {
        api.comments["a.kt"] = mutableListOf(Comment(id = "c_a"))
        loaded()
        api.comments.getValue("a.kt").clear()
        controller.refreshComments()
        assertEquals(emptyMap<String, List<Comment>>(), controller.snapshot!!.fileComments)
    }

    @Test
    fun `events drive round state`() {
        loaded()
        controller.handle(CritEvent("edit-detected", "3"))
        assertEquals(3, controller.snapshot!!.edits)
        controller.handle(CritEvent("finish", """{"approved":false}"""))
        assertEquals(RoundState.AGENT_WORKING, controller.snapshot!!.state)
        controller.handle(CritEvent("file-changed", "session"))
        assertEquals(RoundState.REVIEWING, controller.snapshot!!.state)
        assertEquals(0, controller.snapshot!!.edits)
    }

    @Test
    fun `finish switches to agent working unless approved`() {
        loaded()
        api.finishResult = FinishResult(approved = true)
        controller.finish()
        assertEquals(RoundState.REVIEWING, controller.snapshot!!.state)
        api.finishResult = FinishResult(approved = false)
        controller.finish()
        assertEquals(RoundState.AGENT_WORKING, controller.snapshot!!.state)
    }

    @Test
    fun `unresolved count spans files and review comments`() {
        api.comments["a.kt"] = mutableListOf(Comment(id = "c_a"), Comment(id = "c_b", resolved = true))
        api.session = api.session.copy(reviewComments = listOf(Comment(id = "r_a")))
        loaded()
        assertEquals(2, controller.snapshot!!.unresolvedCount)
    }

    @Test
    fun `resolve nudger re-sends the state of an unresolved comment`() {
        api.comments["a.kt"] = mutableListOf(Comment(id = "c_done", resolved = true), Comment(id = "c_open"))
        loaded()
        api.calls.clear()
        ResolveNudger(api).nudge(controller.snapshot!!)
        assertEquals(listOf("resolve c_open false"), api.calls)
    }

    @Test
    fun `resolve nudger falls back to review comments and skips when all are resolved`() {
        api.comments["a.kt"] = mutableListOf(Comment(id = "c_done", resolved = true))
        api.session = api.session.copy(reviewComments = listOf(Comment(id = "r_open")))
        loaded()
        api.calls.clear()
        ResolveNudger(api).nudge(controller.snapshot!!)
        assertEquals(listOf("resolve r_open false"), api.calls)
        api.session = api.session.copy(reviewComments = listOf(Comment(id = "r_done", resolved = true)))
        controller.refreshComments()
        api.calls.clear()
        ResolveNudger(api).nudge(controller.snapshot!!)
        assertEquals(emptyList<String>(), api.calls)
    }
}
