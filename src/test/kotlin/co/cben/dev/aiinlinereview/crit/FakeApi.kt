package co.cben.dev.aiinlinereview.crit

import java.nio.file.Path
import java.util.stream.Stream

class FakeApi : CritConnection {
    var health = Health("ok", 1)
    var config = CritConfig(author = "Reviewer")
    var session = SessionInfo(mode = "git", branch = "main", reviewRound = 1)
    var finishResult = FinishResult(approved = false)
    var sessionError: Exception? = null
    var events: () -> CritEventStream = { CritEventStream(Stream.empty()) }
    val comments = linkedMapOf<String, MutableList<Comment>>()
    val calls = mutableListOf<String>()
    private var next = 0

    override fun health() = health
    override fun config() = config
    override fun openEvents() = events()

    override fun session(): SessionInfo {
        sessionError?.let { throw it }
        return session.copy(
            files = comments.filterValues { it.isNotEmpty() }.map { (path, list) -> FileEntry(path = path, commentCount = list.size) },
        )
    }
    override fun fileComments(path: String) = comments[path].orEmpty().toList().also { calls += "fileComments $path" }

    override fun createComment(path: String, draft: CommentDraft): Comment {
        val comment = Comment(id = "c_${next++}", startLine = draft.startLine, endLine = draft.endLine, body = draft.body, author = draft.author)
        comments.getOrPut(path) { mutableListOf() } += comment
        calls += "create $path"
        return comment
    }

    override fun createReviewComment(body: String, author: String): Comment {
        val comment = Comment(id = "r_${next++}", body = body, author = author, scope = "review")
        session = session.copy(reviewComments = session.reviewComments + comment)
        calls += "createReview"
        return comment
    }

    override fun editComment(path: String?, id: String, body: String) = update(path, id) { it.copy(body = body) }.also { calls += "edit $id" }

    override fun deleteComment(path: String?, id: String) {
        if (path == null) session = session.copy(reviewComments = session.reviewComments.filter { it.id != id })
        else comments[path]?.removeIf { it.id == id }
        calls += "delete $id"
    }

    override fun resolve(path: String?, id: String, resolved: Boolean) =
        update(path, id) { it.copy(resolved = resolved) }.also { calls += "resolve $id $resolved" }

    override fun reply(path: String?, id: String, body: String, author: String): Reply {
        val reply = Reply(id = "rp_${next++}", body = body, author = author)
        update(path, id) { it.copy(replies = it.replies + reply) }
        calls += "reply $id"
        return reply
    }

    override fun finish() = finishResult.also { calls += "finish" }
    override fun askAgent(path: String, id: String) { calls += "ask $id" }
    override fun upload(file: Path) = Attachment(filename = file.fileName.toString())

    private fun update(path: String?, id: String, change: (Comment) -> Comment): Comment {
        if (path == null) {
            val updated = change(session.reviewComments.first { it.id == id })
            session = session.copy(reviewComments = session.reviewComments.map { if (it.id == id) updated else it })
            return updated
        }
        val list = comments.getValue(path)
        val index = list.indexOfFirst { it.id == id }
        list[index] = change(list[index])
        return list[index]
    }
}
