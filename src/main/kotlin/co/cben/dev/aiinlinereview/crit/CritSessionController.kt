package co.cben.dev.aiinlinereview.crit

import java.util.concurrent.CopyOnWriteArrayList

enum class RoundState { REVIEWING, AGENT_WORKING }

data class CritSnapshot(
    val entry: SessionEntry,
    val config: CritConfig,
    val session: SessionInfo,
    val fileComments: Map<String, List<Comment>>,
    val state: RoundState = RoundState.REVIEWING,
    val edits: Int = 0,
) {
    val reviewComments: List<Comment> get() = session.reviewComments
    val unresolvedCount: Int get() = (fileComments.values.flatten() + reviewComments).count { !it.resolved }
    fun commentsFor(path: String): List<Comment> = fileComments[path].orEmpty()
}

fun interface CritNudger {
    fun nudge(snapshot: CritSnapshot)
}

// crit v0.21.0 emits no SSE for some writes; re-sending an unchanged resolve state makes it broadcast comments-changed.
class ResolveNudger(private val api: CritApi) : CritNudger {
    override fun nudge(snapshot: CritSnapshot) {
        val (path, comment) = snapshot.fileComments.flatMap { (path, list) -> list.map { path to it } }
            .plus(snapshot.reviewComments.map { null to it })
            .firstOrNull { !it.second.resolved } ?: return
        api.resolve(path, comment.id, false)
    }
}

class CritSessionController(
    val entry: SessionEntry,
    private val api: CritApi,
    private val nudger: CritNudger,
) {
    @Volatile
    var snapshot: CritSnapshot? = null
        private set

    private val listeners = CopyOnWriteArrayList<(CritSnapshot) -> Unit>()

    fun addListener(listener: (CritSnapshot) -> Unit) {
        listeners += listener
    }

    fun load(): CritSnapshot {
        api.requireSupported()
        return publish(fetch(api.config(), RoundState.REVIEWING, 0, emptySet()))
    }

    fun refreshComments(): Boolean {
        val current = snapshot ?: return false
        val next = fetch(current.config, current.state, current.edits, current.fileComments.keys)
        if (next == current) return false
        publish(next)
        return true
    }

    fun handle(event: CritEvent) {
        val current = snapshot ?: return
        when (event.type) {
            "comments-changed" -> refreshComments()
            "file-changed" -> publish(fetch(current.config, RoundState.REVIEWING, 0, current.fileComments.keys))
            "edit-detected" -> publish(current.copy(edits = event.content.toIntOrNull() ?: current.edits))
            "finish" -> if (!approved(event.content)) publish(current.copy(state = RoundState.AGENT_WORKING))
        }
    }

    fun createComment(path: String, draft: CommentDraft): Comment =
        write(silent = true) { api.createComment(path, draft.copy(author = author())) }

    fun createReviewComment(body: String): Comment = write(silent = true) { api.createReviewComment(body, author()) }

    fun editComment(path: String?, id: String, body: String): Comment = write(silent = true) { api.editComment(path, id, body) }

    fun deleteComment(path: String?, id: String) = write(silent = path == null) { api.deleteComment(path, id) }

    fun resolve(path: String?, id: String, resolved: Boolean): Comment = write(silent = false) { api.resolve(path, id, resolved) }

    fun reply(path: String?, id: String, body: String): Reply = write(silent = path == null) { api.reply(path, id, body, author()) }

    fun askAgent(path: String, id: String) = api.askAgent(path, id)

    fun finish(): FinishResult {
        val result = api.finish()
        val current = snapshot
        if (!result.approved && current != null) publish(current.copy(state = RoundState.AGENT_WORKING))
        return result
    }

    private fun author(): String = snapshot?.config?.author.orEmpty()

    private fun <T> write(silent: Boolean, call: () -> T): T {
        val result = call()
        refreshComments()
        if (silent) snapshot?.let(nudger::nudge)
        return result
    }

    private fun fetch(config: CritConfig, state: RoundState, edits: Int, known: Set<String>): CritSnapshot {
        val session = api.session()
        val paths = session.files.filter { it.commentCount > 0 }.map { it.path }.toSet() + known
        val fileComments = paths.associateWith { path -> commentsOrEmpty(path) }.filterValues { it.isNotEmpty() }
        return CritSnapshot(entry, config, session, fileComments, state, edits)
    }

    private fun commentsOrEmpty(path: String): List<Comment> = try {
        api.fileComments(path)
    } catch (e: CritHttpException) {
        if (e.status == 404) emptyList() else throw e
    }

    private fun publish(next: CritSnapshot): CritSnapshot {
        snapshot = next
        listeners.forEach { it(next) }
        return next
    }

    private fun approved(content: String): Boolean =
        runCatching { CritJson.parse<FinishResult>(content).approved }.getOrDefault(false)
}
