package co.cben.dev.aiinlinereview.crit

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CritClient(
    private val baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : CritApi {

    override fun health(): Health = CritJson.parse(get("/api/health"))
    override fun config(): CritConfig = CritJson.parse(get("/api/config"))
    override fun session(): SessionInfo = CritJson.parse(get("/api/session"))
    override fun fileComments(path: String): List<Comment> = CritJson.parse(get("/api/file/comments", "path" to path))

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
