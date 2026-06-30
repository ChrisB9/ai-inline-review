package co.cben.dev.claude.review

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import java.util.UUID

/**
 * Project-level store of pending review comments. Persisted in the workspace file, so notes
 * survive IDE restarts until they are sent to Claude.
 */
@Service(Service.Level.PROJECT)
@State(name = "ClaudeReviewStore", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReviewStore : PersistentStateComponent<ReviewStore.State> {

    class State {
        @JvmField var comments: MutableList<ReviewComment> = mutableListOf()
        @JvmField var sessions: MutableList<ReviewSession> = mutableListOf()
        @JvmField var activeSessionId: String = ""
    }

    private var myState = State()
    private val listeners = mutableListOf<Runnable>()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val comments: List<ReviewComment> get() = myState.comments

    val sessions: List<ReviewSession> get() = myState.sessions

    var activeSessionId: String
        get() = myState.activeSessionId
        set(value) {
            myState.activeSessionId = value
            fireChanged()
        }

    val activeSession: ReviewSession? get() = myState.sessions.firstOrNull { it.id == myState.activeSessionId }

    /** Notes belonging to the active session. */
    val activeComments: List<ReviewComment> get() = myState.comments.filter { it.sessionId == myState.activeSessionId }

    /** Make sure there is at least one session and a valid active id; migrate orphan notes. */
    fun ensureSession() {
        if (myState.sessions.isEmpty()) {
            val session = ReviewSession().apply {
                id = UUID.randomUUID().toString()
                name = "Main"
                kind = ReviewSession.KIND_CODE
                createdAt = System.currentTimeMillis()
            }
            myState.sessions.add(session)
        }
        if (myState.sessions.none { it.id == myState.activeSessionId }) {
            myState.activeSessionId = myState.sessions.first().id
        }
        var migrated = false
        myState.comments.forEach {
            if (it.sessionId.isBlank()) {
                it.sessionId = myState.activeSessionId
                migrated = true
            }
        }
        if (migrated) fireChanged()
    }

    fun addSession(name: String, kind: String): ReviewSession {
        val session = ReviewSession().apply {
            id = UUID.randomUUID().toString()
            this.name = name
            this.kind = kind
            createdAt = System.currentTimeMillis()
        }
        myState.sessions.add(session)
        myState.activeSessionId = session.id
        fireChanged()
        return session
    }

    /** Finish a session: drop it and all its notes. */
    fun removeSession(id: String) {
        myState.comments.removeAll { it.sessionId == id }
        myState.sessions.removeAll { it.id == id }
        if (myState.sessions.none { it.id == myState.activeSessionId }) {
            myState.activeSessionId = myState.sessions.firstOrNull()?.id ?: ""
        }
        ensureSession()
        fireChanged()
    }

    fun add(comment: ReviewComment) {
        myState.comments.add(comment)
        fireChanged()
    }

    fun remove(comment: ReviewComment) {
        myState.comments.remove(comment)
        fireChanged()
    }

    fun clear() {
        myState.comments.clear()
        fireChanged()
    }

    /** Comment is mutated in place by the caller; just notify listeners to re-render. */
    fun update() {
        fireChanged()
    }

    /** Insert or replace a note by id (used when reloading externally-edited note files). */
    fun upsert(comment: ReviewComment) {
        val index = myState.comments.indexOfFirst { it.id == comment.id }
        if (index >= 0) myState.comments[index] = comment else myState.comments.add(comment)
        fireChanged()
    }

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    private fun fireChanged() {
        listeners.toList().forEach { it.run() }
    }

    companion object {
        fun getInstance(project: Project): ReviewStore = project.getService(ReviewStore::class.java)
    }
}
