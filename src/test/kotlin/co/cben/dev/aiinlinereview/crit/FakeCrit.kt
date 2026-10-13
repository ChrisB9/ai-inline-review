package co.cben.dev.aiinlinereview.crit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class FakeCrit : AutoCloseable {
    data class Recorded(val method: String, val path: String, val query: String?, val body: String, val contentType: String?)
    data class Canned(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    val requests = CopyOnWriteArrayList<Recorded>()
    private val routes = ConcurrentHashMap<String, ArrayDeque<Canned>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { handle(it) }
        server.start()
    }

    // Replies are served in order; the last one repeats.
    fun on(method: String, path: String, vararg replies: Canned) {
        routes["$method $path"] = ArrayDeque(replies.toList())
    }

    fun on(method: String, path: String, body: String) = on(method, path, Canned(200, body))

    fun fixture(name: String) = javaClass.getResource("/crit/$name")!!.readText()

    private fun handle(exchange: HttpExchange) {
        val uri = exchange.requestURI
        val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
        requests += Recorded(exchange.requestMethod, uri.path, uri.rawQuery, body, exchange.requestHeaders.getFirst("Content-Type"))
        val queue = routes["${exchange.requestMethod} ${uri.path}"]
        val reply = when {
            queue == null -> Canned(404, "no route")
            queue.size > 1 -> queue.removeFirst()
            else -> queue.first()
        }
        reply.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        val bytes = reply.body.toByteArray()
        exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }

    override fun close() = server.stop(0)
}
