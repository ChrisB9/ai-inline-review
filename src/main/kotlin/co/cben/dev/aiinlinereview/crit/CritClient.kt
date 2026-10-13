package co.cben.dev.aiinlinereview.crit

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class CritClient(
    private val baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : CritConnection {

    override fun health(): Health = CritJson.parse(get("/api/health"))
    override fun config(): CritConfig = CritJson.parse(get("/api/config"))
    override fun session(): SessionInfo = CritJson.parse(get("/api/session"))
    override fun fileComments(path: String): List<Comment> = CritJson.parse(get("/api/file/comments", "path" to path))

    override fun createComment(path: String, draft: CommentDraft): Comment =
        CritJson.parse(sendJson("POST", "/api/file/comments", draft, "path" to path))

    override fun createReviewComment(body: String, author: String): Comment =
        CritJson.parse(sendJson("POST", "/api/comments", linkedMapOf("body" to body, "author" to author)))

    override fun editComment(path: String?, id: String, body: String): Comment =
        CritJson.parse(sendJson("PUT", commentUrl(path, id), mapOf("body" to body), *pathQuery(path)))

    override fun deleteComment(path: String?, id: String) {
        send("DELETE", commentUrl(path, id), pathQuery(path), null)
    }

    override fun resolve(path: String?, id: String, resolved: Boolean): Comment =
        CritJson.parse(sendJson("PUT", commentUrl(path, id) + "/resolve", mapOf("resolved" to resolved), *pathQuery(path)))

    override fun reply(path: String?, id: String, body: String, author: String): Reply =
        CritJson.parse(sendJson("POST", commentUrl(path, id) + "/replies", linkedMapOf("body" to body, "author" to author), *pathQuery(path)))

    override fun finish(): FinishResult = CritJson.parse(send("POST", "/api/finish", emptyArray(), null))

    override fun askAgent(path: String, id: String) {
        sendJson("POST", "/api/agent/request", linkedMapOf("comment_id" to id, "file_path" to path))
    }

    override fun upload(file: Path): Attachment {
        val boundary = "crit-${UUID.randomUUID()}"
        val type = Files.probeContentType(file) ?: "application/octet-stream"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.fileName}\"\r\n" +
            "Content-Type: $type\r\n\r\n"
        val bytes = head.toByteArray() + Files.readAllBytes(file) + "\r\n--$boundary--\r\n".toByteArray()
        val payload = "multipart/form-data; boundary=$boundary" to HttpRequest.BodyPublishers.ofByteArray(bytes)
        return CritJson.parse(send("POST", "/api/attachments", emptyArray(), payload))
    }

    private fun sendJson(method: String, path: String, body: Any, vararg query: Pair<String, String>): String =
        send(method, path, query, "application/json" to HttpRequest.BodyPublishers.ofString(CritJson.gson.toJson(body)))

    private fun commentUrl(path: String?, id: String): String =
        (if (path == null) "/api/review-comment/" else "/api/comment/") + URLEncoder.encode(id, Charsets.UTF_8)

    private fun pathQuery(path: String?): Array<Pair<String, String>> =
        if (path == null) emptyArray() else arrayOf("path" to path)

    override fun openEvents(): CritEventStream {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/events"))
            .header("Accept", "text/event-stream")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() != 200) {
            response.body().close()
            throw CritHttpException(response.statusCode(), "events")
        }
        return CritEventStream(response.body())
    }

    private fun get(path: String, vararg query: Pair<String, String>) = send("GET", path, query, null)

    private fun send(
        method: String,
        path: String,
        query: Array<out Pair<String, String>>,
        payload: Pair<String, HttpRequest.BodyPublisher>?,
    ): String {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path + encode(query)))
            .timeout(REQUEST_TIMEOUT)
            .method(method, payload?.second ?: HttpRequest.BodyPublishers.noBody())
        payload?.let { builder.header("Content-Type", it.first) }
        val request = builder.build()
        var waitedMs = 0L
        while (true) {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            if (status == 503 && waitedMs < LOADING_BUDGET_MS) {
                val retryMs = (response.headers().firstValue("Retry-After").orElse("1").toLongOrNull() ?: 1L) * 1000
                sleep(retryMs)
                waitedMs += retryMs
                continue
            }
            if (status !in 200..299) throw CritHttpException(status, response.body().trim())
            return response.body()
        }
    }

    private fun encode(query: Array<out Pair<String, String>>): String =
        if (query.isEmpty()) "" else query.joinToString("&", prefix = "?") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }

    companion object {
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val LOADING_BUDGET_MS = 10_000L
    }
}
