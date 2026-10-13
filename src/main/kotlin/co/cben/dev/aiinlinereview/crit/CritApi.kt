package co.cben.dev.aiinlinereview.crit

import java.nio.file.Path

class CritHttpException(val status: Int, body: String) : RuntimeException("crit HTTP $status: $body")

class UnsupportedCritApi(val apiVersion: Int) :
    RuntimeException("crit API v$apiVersion unsupported, plugin supports API v$SUPPORTED_API_VERSION")

const val SUPPORTED_API_VERSION = 1

interface CritApi {
    fun health(): Health
    fun config(): CritConfig
    fun session(): SessionInfo
    fun fileComments(path: String): List<Comment>
    fun createComment(path: String, draft: CommentDraft): Comment
    fun createReviewComment(body: String, author: String): Comment
    fun editComment(path: String?, id: String, body: String): Comment
    fun deleteComment(path: String?, id: String)
    fun resolve(path: String?, id: String, resolved: Boolean): Comment
    fun reply(path: String?, id: String, body: String, author: String): Reply
    fun finish(): FinishResult
    fun askAgent(path: String, id: String)
    fun upload(file: Path): Attachment

    fun requireSupported(): Health {
        val health = health()
        if (health.apiVersion != SUPPORTED_API_VERSION) throw UnsupportedCritApi(health.apiVersion)
        return health
    }
}

interface CritConnection : CritApi {
    fun openEvents(): CritEventStream
}
